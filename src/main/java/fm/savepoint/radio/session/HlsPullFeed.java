package fm.savepoint.radio.session;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * HLS-pull feed: ffmpeg fetches a SavePoint trunk playlist and decodes it to
 * PCM. This is sow-001's original ingest, now expressed as a subclass of
 * {@link AbstractFfmpegFeed}; it serves both admin HLS discs (URL built by
 * {@link StationEndpoints}) and sow-001's static config stations.
 *
 * <p>An idle station answers the playlist with HTTP 404 (per SavePoint
 * semantics), so a 404 in stderr classifies the ended connection as idle
 * rather than an error. The access token must never reach the logs: the
 * effective URL is rewritten to its display form, the literal encoded token
 * becomes {@code ***}, and any leftover {@code token=} query value (segment /
 * variant URLs inherit the query) is redacted by pattern.</p>
 */
public final class HlsPullFeed extends AbstractFfmpegFeed {

	/**
	 * Defensive redaction of any {@code token=} query value (segment/variant
	 * URLs inherit the playlist's query string). The exact configured token is
	 * additionally replaced literally in {@link #sanitize(String)}.
	 */
	private static final Pattern TOKEN_QUERY_PATTERN = Pattern.compile("([?&]token=)[^&\\s'\"]+");

	/**
	 * HLS reconnect ladder (sow-005 Phase A, item 3; the dev's Discord-proven values):
	 * 5x2&nbsp;s, then 10x30&nbsp;s, then 10x60&nbsp;s, then 5x1&nbsp;h, holding at the
	 * last tier thereafter. Keyed off the 1-based consecutive reconnect {@code attempt}
	 * the ingest loop passes; the loop's healthy-streak reset (30&nbsp;s of streaming)
	 * zeroes {@code attempt}, snapping the ladder back to the top. This REPLACES the
	 * base geometric backoff for HLS (the base doubling still runs but HLS's
	 * {@link #reconnectDelayMillis} ignores it, exactly as RTMP ignores it for its fast
	 * re-listen). {@code reconnectBackoffMaxSeconds} is intentionally NOT applied here -
	 * the ladder is the authoritative HLS schedule.
	 */
	private static final int[] LADDER_TIER_COUNTS  = {5, 10, 10, 5};
	private static final long[] LADDER_TIER_SECONDS = {2L, 30L, 60L, 3600L};

	/**
	 * Decoded 20&nbsp;ms frames per SavePoint trunk HLS segment: 2&nbsp;s segments at
	 * 50&nbsp;frames/s = 100. Used to size a spawn-time backlog grab against the jitter
	 * buffer's remaining headroom (sow-005 Phase A finding), so {@code -live_start_index}
	 * never re-floods a non-empty buffer past its hard cap.
	 */
	private static final int FRAMES_PER_SEGMENT = 100;

	private final String effectiveUrl;
	private final String displayUrl;
	/** Encoded token embedded in {@link #effectiveUrl}; null when none. */
	private final String secret;
	/**
	 * How many HLS segments behind the live edge ffmpeg starts on connect
	 * ({@code -live_start_index -<N>}, sow-005 Phase A, item 1). {@code > 0} grabs the
	 * retained backlog so the deep jitter buffer fills fast; {@code 0} keeps ffmpeg's
	 * default live-edge start. Clamped by config to 0..45 (the trunk's 120&nbsp;s window
	 * with a &gt;=30&nbsp;s deletion margin). The effective per-spawn depth is further
	 * reduced by {@link #backlogSegmentsForSpawn()} to the buffer's current headroom.
	 */
	private final int startBehindSegments;
	/** Last reconnect ladder tier (seconds) chosen, for {@link #diagExtra()}. */
	private volatile long reconnectTierSeconds;

	public HlsPullFeed(String stationKey, StationEndpoints.Endpoint endpoint,
			String ffmpegBinary, int maxBufferFrames, int targetFrames, int prerollFrames, long maxBackoffSeconds,
			boolean reconnectPreserveBuffer, int reanchorLowWaterFrames, int reanchorSustainFrames,
			String smoothingMode, double maxStretchPercent, int startBehindSegments) {
		super(stationKey, ffmpegBinary, maxBufferFrames, targetFrames, prerollFrames, maxBackoffSeconds,
				reconnectPreserveBuffer, reanchorLowWaterFrames, reanchorSustainFrames,
				smoothingMode, maxStretchPercent);
		this.effectiveUrl = endpoint.effectiveUrl();
		this.displayUrl = endpoint.displayUrl();
		this.secret = endpoint.secret();
		this.startBehindSegments = Math.max(0, startBehindSegments);
	}

	@Override
	protected String[] buildCommand() {
		List<String> cmd = new ArrayList<>(List.of(
				ffmpegBinary, "-nostdin", "-hide_banner", "-loglevel", "warning"));
		// START-BEHIND (sow-005 Phase A, item 1): an HLS demuxer INPUT option (must
		// precede -i). -live_start_index -<N> makes ffmpeg begin N segments behind the
		// live edge, grabbing the retained backlog on connect so the deep jitter buffer
		// fills fast (matching how browser/Discord/etc. buffer behind the edge). The
		// depth is sized to the buffer's REMAINING headroom so a buffer-preserving
		// reconnect does not re-grab the full backlog and flood the still-draining buffer
		// past its hard cap (sow-005 Phase A finding); 0 keeps ffmpeg's default live edge.
		int behind = backlogSegmentsForSpawn();
		if (behind > 0) {
			cmd.add("-live_start_index");
			cmd.add("-" + behind);
		}
		// Input-option protocol whitelist (must precede -i). Enables the
		// owner's plain-http stream URL while blocking dangerous protocols.
		// file: is deliberately NOT whitelisted: streamUrl can never be a
		// file:// URL (validate() forces http/https), and the HLS demuxer
		// opens segment/AES-key URIs found INSIDE the fetched playlist
		// subject only to this whitelist - a hostile/redirecting remote
		// stream must not be able to turn ffmpeg into a local-file reader.
		// No pipe/ftp/concat/data either.
		cmd.add("-protocol_whitelist");
		cmd.add("http,https,tcp,tls,crypto,hls");
		cmd.add("-i");
		cmd.add(effectiveUrl);
		cmd.add("-vn");
		cmd.add("-f");
		cmd.add("s16le");
		cmd.add("-ar");
		cmd.add("48000");
		cmd.add("-ac");
		cmd.add("1");
		cmd.add("pipe:1");
		return cmd.toArray(new String[0]);
	}

	/**
	 * The start-behind depth to request for THIS spawn (sow-005 Phase A finding): the
	 * configured {@link #startBehindSegments}, reduced so the backlog ffmpeg re-grabs
	 * on connect cannot flood a non-empty jitter buffer past its hard cap.
	 *
	 * <p>On a cold start (empty buffer) the full configured depth is used, so the deep
	 * buffer fills fast from the retained backlog. On a buffer-preserving reconnect
	 * (playout still draining retained frames) the depth shrinks to the headroom left
	 * below the steady-state fill depth ({@code max(target, preroll)}), or to 0 when the
	 * buffer already holds that depth - so the re-grab only TOPS UP toward the target and
	 * never overflow-drops the very frames the buffer-preserving reconnect is bridging on.
	 * Without this an HLS reconnect re-requested the entire backlog while the retained
	 * buffer was near full, overflow-dropping the oldest retained frames (an audible
	 * forward skip) and firing the spurious "raise maxBufferSeconds" WARN.</p>
	 *
	 * <p>Called once per spawn on the ingest thread (and by the diag redaction path); a
	 * brief {@code bufferLock} read only. Segment&harr;frame conversion is exact for the
	 * 2&nbsp;s trunk segments ({@link #FRAMES_PER_SEGMENT}); a sub-segment of headroom
	 * (&lt;100 frames) floors to 0 and simply skips the re-grab for that spawn.</p>
	 */
	private int backlogSegmentsForSpawn() {
		if (startBehindSegments <= 0) {
			return 0;
		}
		int fillCeiling = Math.max(targetFrameCount(), prerollFrameCount());
		int headroomFrames = fillCeiling - bufferedFrameCount();
		if (headroomFrames <= 0) {
			return 0;
		}
		return Math.min(startBehindSegments, headroomFrames / FRAMES_PER_SEGMENT);
	}

	@Override
	public boolean isLive() {
		return true; // an HLS pull is a live SavePoint station broadcast
	}

	@Override
	protected String startLogDetail() {
		return "url=" + displayUrl;
	}

	/**
	 * The HLS reconnect ladder (sow-005 Phase A, item 3), keyed off the 1-based
	 * consecutive {@code attempt}; ignores {@code backoffSeconds}/{@code idle} (the
	 * ladder is HLS's authoritative schedule and applies whether the ended connection
	 * was a 404-idle off-air station or a hard error - both should back off toward
	 * 1&nbsp;h rather than hammer the origin). The loop's healthy-streak reset zeroes
	 * {@code attempt} and snaps the ladder back to the top after 30&nbsp;s of streaming.
	 */
	@Override
	protected long reconnectDelayMillis(long backoffSeconds, boolean idle, long attempt) {
		long seconds = ladderSeconds(attempt);
		this.reconnectTierSeconds = seconds;
		return TimeUnit.SECONDS.toMillis(seconds);
	}

	/** Maps a 1-based consecutive attempt to its ladder-tier delay (seconds). */
	private static long ladderSeconds(long attempt) {
		long a = Math.max(1L, attempt);
		long consumed = 0L;
		for (int t = 0; t < LADDER_TIER_SECONDS.length; t++) {
			consumed += LADDER_TIER_COUNTS[t];
			if (a <= consumed) {
				return LADDER_TIER_SECONDS[t];
			}
		}
		// Beyond the defined tiers: hold at the slowest (1 h).
		return LADDER_TIER_SECONDS[LADDER_TIER_SECONDS.length - 1];
	}

	@Override
	protected String diagExtra() {
		return "startBehindSegments=" + startBehindSegments
				+ " reconnectLadderSeconds=" + reconnectTierSeconds;
	}

	@Override
	protected boolean isIdleStderr(String line) {
		// Only ffmpeg's actual HTTP 404 message shapes - a bare "404" substring
		// is far too loose (hex context addresses and HLS sequence numbers can
		// both contain 404 and would misclassify a genuine failure as idle).
		return line.contains("Server returned 404") || line.contains("HTTP error 404");
	}

	@Override
	protected String sanitize(String line) {
		// The exact-token replacements only apply when a token is known, but the
		// defensive token= pattern must run ALWAYS: a config-station URL can carry
		// a token inline with no separate token field (secret left null), and any
		// stderr line echoing that URL would otherwise leak it unredacted.
		if (secret != null) {
			if (line.contains(effectiveUrl)) {
				line = line.replace(effectiveUrl, displayUrl);
			}
			if (line.contains(secret)) {
				line = line.replace(secret, "***");
			}
		}
		return TOKEN_QUERY_PATTERN.matcher(line).replaceAll("$1***");
	}
}
