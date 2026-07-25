package fm.gbti.jockey.session;

/**
 * File-loop feed (sow-009): ffmpeg loops a finite audio file, reached over HTTP, at
 * real-time and decodes it into the same PCM&nbsp;&rarr;&nbsp;jitter-buffer&nbsp;&rarr;&nbsp;SVC
 * pipeline every other feed uses. Backs {@link fm.gbti.jockey.disc.DiscDefinition.SourceType#FILE}
 * discs - both a direct HTTP audio link and a config-folder file the mod serves over
 * its internal loopback HTTP endpoint.
 *
 * <p><b>Why a distinct feed.</b> Unlike HLS (live edge) and RTMP (receive), a file is
 * FINITE, so:
 * <ul>
 *   <li>{@code -stream_loop -1} loops it forever (a non-looping file would EOF and the
 *       feed would idle / reconnect-spin);</li>
 *   <li>{@code -re} paces the read to real-time - without it ffmpeg races the whole file
 *       into the jitter buffer and overflows it;</li>
 *   <li>no {@code -live_start_index} (a file has no live edge).</li>
 * </ul>
 *
 * <p><b>Guardrail preserved.</b> {@code file:} is deliberately NOT in the protocol
 * whitelist, exactly as {@link HlsPullFeed} - a stream must never turn ffmpeg into a
 * local-file reader. Local files reach ffmpeg only through the internal HTTP server, so
 * an http/https/tcp/tls whitelist suffices. The URL is token-redacted for logs via
 * {@link StationEndpoints.Endpoint#displayUrl()}.
 */
public final class FileLoopFeed extends AbstractFfmpegFeed {

	/**
	 * ffmpeg {@code -rw_timeout} (microseconds): how long a single socket read may stall
	 * before ffmpeg gives up and exits, handing the connection to the reconnect ladder.
	 */
	static final long READ_TIMEOUT_MICROS = 30_000_000L;

	private final String effectiveUrl;
	private final String displayUrl;
	/** sow-012: loop the file forever (true) or play it once and stop (false). */
	private final boolean repeat;

	public FileLoopFeed(String stationKey, StationEndpoints.Endpoint endpoint, String ffmpegBinary,
			int maxBufferFrames, int targetFrames, int prerollFrames, long maxBackoffSeconds,
			boolean reconnectPreserveBuffer, int reanchorLowWaterFrames, int reanchorSustainFrames,
			String smoothingMode, double maxStretchPercent, boolean repeat) {
		super(stationKey, ffmpegBinary, maxBufferFrames, targetFrames, prerollFrames, maxBackoffSeconds,
				reconnectPreserveBuffer, reanchorLowWaterFrames, reanchorSustainFrames,
				smoothingMode, maxStretchPercent);
		this.effectiveUrl = endpoint.effectiveUrl();
		this.displayUrl = endpoint.displayUrl();
		this.repeat = repeat;
		// A FILE disc's URL is routinely a presigned/tokened CDN link. Without this the
		// base sanitize has nothing to swap the display form in for, and the tokened URL
		// reaches the reconnect WARN line and the /jocky diag arg-shape verbatim.
		redactEndpoint(endpoint);
	}

	@Override
	protected String[] buildCommand() {
		java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of(
				ffmpegBinary, "-nostdin", "-hide_banner", "-loglevel", "warning",
				// Give up on a socket that stops delivering (microseconds). Without it a
				// half-open connection - a NAT/LB silently dropping state, an origin that
				// accepts then stalls - leaves ffmpeg blocked in recv() forever: alive, so
				// the ingest loop never reaches its reconnect ladder and the disc is silent
				// with no error. Far longer than any healthy read, so -re pacing is unaffected.
				"-rw_timeout", String.valueOf(READ_TIMEOUT_MICROS),
				// -re paces the read to real-time (INPUT option) - without it ffmpeg races the
				// whole file into the jitter buffer and overflows it.
				"-re"));
		// sow-012: loop forever only when repeat is on; a repeat-off file EOFs once, and the base
		// ingest loop (stopAtEndOfInput) then stops instead of respawning. Both are INPUT options.
		if (repeat) {
			cmd.add("-stream_loop");
			cmd.add("-1");
		}
		cmd.addAll(java.util.List.of(
				// HTTP-only whitelist (no file:) - same guardrail as HlsPullFeed. Local files are
				// served over the mod's internal loopback HTTP endpoint.
				"-protocol_whitelist", "http,https,tcp,tls,crypto",
				"-i", effectiveUrl,
				"-vn", "-f", "s16le", "-ar", "48000", "-ac", "1", "pipe:1"));
		return cmd.toArray(new String[0]);
	}

	/** A file is always finite, so repeat-off stops after one play. */
	@Override
	protected boolean stopAtEndOfInput() {
		return !repeat;
	}

	@Override
	protected String startLogDetail() {
		return "file=" + displayUrl;
	}
}
