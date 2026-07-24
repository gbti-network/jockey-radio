package fm.savepoint.radio.session;

import fm.savepoint.radio.SavePointRadioMod;

/**
 * RTMP-receive feed: the mod runs an ffmpeg RTMP listener and a DJ/OBS/relay
 * publishes into it. {@code ffmpeg -listen 1 -i rtmp://<bind>:<port>/live/<key>}
 * blocks until a publisher connects, streams while it is up, and EOFs (the
 * process exits) when it disconnects - so a clean end here is the expected
 * "waiting for a publisher" idle, not an error, and the listener is respawned.
 *
 * <p>One listener serves one publisher at a time and needs one distinct port
 * per RTMP disc. The stream key gates who may publish and is a secret: it never
 * reaches the logs (redacted from stderr, and only the port is printed in the
 * waiting line).</p>
 */
public final class RtmpReceiveFeed extends AbstractFfmpegFeed {

	private final String rtmpBind;
	private final int rtmpPort;
	private final String streamKey;
	private final String listenUrl;

	/**
	 * Idle respawn delay: a publisher disconnect is the normal case, so re-bind the
	 * listener almost immediately (150&nbsp;ms) rather than on the exponential
	 * backoff, keeping SavePoint's ~2&nbsp;s reconnect gap small enough for the
	 * jitter buffer to cover.
	 */
	private static final long IDLE_RESPAWN_DELAY_MILLIS = 150L;
	/**
	 * Error respawn delay: even a bind/setup failure re-listens fast (the publisher
	 * may retry any moment), but a touch slower than the idle path so a persistent
	 * failure (e.g. a port conflict) re-tries about once a second instead of
	 * hammering the port and the log.
	 */
	private static final long ERROR_RESPAWN_DELAY_MILLIS = 1000L;

	public RtmpReceiveFeed(String stationKey, String rtmpBind, int rtmpPort, String streamKey,
			String ffmpegBinary, int maxBufferFrames, int targetFrames, int prerollFrames, long maxBackoffSeconds,
			boolean reconnectPreserveBuffer, int reanchorLowWaterFrames, int reanchorSustainFrames,
			String smoothingMode, double maxStretchPercent) {
		super(stationKey, ffmpegBinary, maxBufferFrames, targetFrames, prerollFrames, maxBackoffSeconds,
				reconnectPreserveBuffer, reanchorLowWaterFrames, reanchorSustainFrames,
				smoothingMode, maxStretchPercent);
		this.rtmpBind = rtmpBind;
		this.rtmpPort = rtmpPort;
		this.streamKey = streamKey;
		this.listenUrl = "rtmp://" + rtmpBind + ":" + rtmpPort + "/live/" + streamKey;
	}

	@Override
	protected String[] buildCommand() {
		return new String[] {
				ffmpegBinary, "-nostdin", "-hide_banner", "-loglevel", "warning",
				// Low-latency input: cap stream probing so the first PCM frame is
				// emitted well under a second after a publisher connects. ffmpeg's
				// defaults (analyzeduration 5s / probesize 5 MB) delay first output
				// ~5-6s on every (re)connect - longer than the jitter buffer's
				// depth, so a publisher restart would drain the buffer to an
				// audible gap before audio resumed. FLV/RTMP declares its AAC track
				// in the header, so a 0.5s / 512 KB probe detects it reliably.
				"-fflags", "nobuffer", "-analyzeduration", "500000", "-probesize", "524288",
				"-listen", "1", "-i", listenUrl,
				"-vn", "-f", "s16le", "-ar", "48000", "-ac", "1", "pipe:1"
		};
	}

	@Override
	public boolean isLive() {
		return true; // an RTMP receive is a live inbound publisher
	}

	@Override
	protected String startLogDetail() {
		// Never the stream key - only the listen address and port.
		return "rtmp=rtmp://" + rtmpBind + ":" + rtmpPort + "/live/*** (listener)";
	}

	// No afterSpawn override: the "feed waiting" line is NOT logged right after
	// spawn, because at that point we cannot yet tell a bound listener from a
	// bind failure (port already in use). Emitting it there made a port conflict
	// spam a misleading "listener up, waiting" INFO before every WARN reconnect.
	// Instead the waiting line is logged only from onIdle - i.e. only once a
	// connection that actually bound has ended idle - mirroring how HLS logs its
	// 404-idle line after a connection ends rather than at spawn.

	/**
	 * A listener that came up and then ended (publisher never connected, or
	 * connected then disconnected) is the expected idle. Only a bind/setup
	 * failure - the port is busy or the bind address is unusable, which ffmpeg
	 * reports before it can listen - is a genuine error worth a reconnect WARN.
	 *
	 * <p>Because a clean publisher disconnect emits no stderr at
	 * {@code -loglevel warning}, idle is the ABSENCE of a fatal line, detected
	 * per-connection: {@link #isFatalStderr} records any bind/setup error THIS
	 * connection produced into {@link StderrDrain#sawFatal}, and we classify on
	 * that. Peeking the shared {@code lastStderrLine()} instead would let a fatal
	 * line left over from an EARLIER connection misclassify this connection's
	 * normal disconnect as an error.</p>
	 */
	@Override
	protected boolean classifyIdle(StderrDrain drain) {
		return drain != null && !drain.sawFatal.get();
	}

	/**
	 * The bind/setup errors ffmpeg reports before it can listen (or on an
	 * immediate teardown). Seeing any of these this connection means it never
	 * served a publisher, so the ended connection is an error, not the expected
	 * "waiting for a publisher" idle.
	 */
	@Override
	protected boolean isFatalStderr(String line) {
		return line.contains("Address already in use")
				|| line.contains("Cannot assign requested address")
				|| line.contains("Error opening input")
				|| line.contains("Immediate exit requested");
	}

	@Override
	protected void onIdle(long backoffSeconds) {
		connectionState = "waiting";
		SavePointRadioMod.LOGGER.info("[radio] feed waiting station={} (rtmp listener :{})",
				stationKeyValue(), rtmpPort);
	}

	/**
	 * A publisher disconnect is the normal idle for a listener whose whole job is
	 * to be ready for the next publisher, so respawn on the short fixed delay
	 * rather than escalating the backoff - otherwise a flapping DJ (each session
	 * under the healthy streak) would drive the respawn delay up toward
	 * {@code reconnectBackoffMaxSeconds} and get locked out of the listen port.
	 * Genuine bind/setup failures are classified NOT idle and still escalate.
	 */
	@Override
	protected boolean promptRespawnOnIdle() {
		return true;
	}

	/**
	 * An RTMP listener must be back on its port fast whether the last connection
	 * ended idle (normal publisher disconnect) or errored (the publisher may retry
	 * any moment), so it never rides the exponential backoff the {@code backoffSeconds}
	 * argument carries - a short fixed delay either way. This is what shrinks the
	 * reconnect gap enough for the jitter buffer to bridge SavePoint's periodic
	 * ~2&nbsp;s upstream restart without an audible dropout.
	 */
	@Override
	protected long reconnectDelayMillis(long backoffSeconds, boolean idle, long attempt) {
		return idle ? IDLE_RESPAWN_DELAY_MILLIS : ERROR_RESPAWN_DELAY_MILLIS;
	}

	@Override
	protected String sanitize(String line) {
		String out = line;
		if (out.contains(listenUrl)) {
			out = out.replace(listenUrl, "rtmp://" + rtmpBind + ":" + rtmpPort + "/live/***");
		}
		if (streamKey != null && !streamKey.isBlank() && out.contains(streamKey)) {
			out = out.replace(streamKey, "***");
		}
		return out;
	}
}
