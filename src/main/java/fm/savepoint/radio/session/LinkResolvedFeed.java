package fm.savepoint.radio.session;

import fm.savepoint.radio.SavePointRadioMod;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Platform-link feed (sow-007): plays a YouTube / SoundCloud / Mixcloud / Twitch / Kick
 * <em>page</em> URL by resolving it to a real audio stream with <b>yt-dlp</b>, then handing
 * that stream to the shared {@link AbstractFfmpegFeed} pipeline. Backs
 * {@link fm.savepoint.radio.disc.DiscDefinition.SourceType#PLATFORM} discs.
 *
 * <p><b>Why a resolver.</b> A {@code youtube.com/watch?v=…} / {@code soundcloud.com/…} page is
 * NOT a media stream, so ffmpeg cannot open it. {@code yt-dlp -f bestaudio -g <url>} prints a
 * direct audio (or self-refreshing live-HLS) URL, which ffmpeg plays with the same
 * {@code -vn -f s16le} decode every other feed uses. Audio-only ({@code bestaudio}) matches the
 * existing {@code -vn} and saves bandwidth.
 *
 * <p><b>Resolve-per-spawn.</b> {@link #buildCommand()} re-resolves on every (re)connect, so an
 * expired signed URL (YouTube) or a rolled live edge is refreshed for free by the existing
 * reconnect ladder. A finite track EOFs and reconnects (re-resolves + replays); a live stream's
 * ffmpeg-level {@code -reconnect} rides brief drops without a full respawn.
 *
 * <p><b>Fail-soft & safe.</b> {@code buildCommand()} MUST NOT throw (that would kill the ingest
 * thread), so a resolve failure (yt-dlp missing/error/timeout) logs once and returns a fast-failing
 * input, which the normal reconnect backoff retries. yt-dlp is invoked via {@code ProcessBuilder}
 * argv (never a shell), so the admin page URL cannot inject. {@code file:} is never whitelisted;
 * the resolved URL is http/https. The dependency is handled like ffmpeg: a config
 * {@code ytdlpBinary}; yt-dlp shifts fast as platforms change, so the operator keeps it updated.
 */
public final class LinkResolvedFeed extends AbstractFfmpegFeed {

	// The format selector, printed-field parsing, ffmpeg argv and the unresolved-input sentinel all
	// live in YtDlpResolve, which is pure and covered by offline unit tests (YtDlpResolveTest).

	/** yt-dlp extraction can take a while (network + page parse); cap it so a hang reconnects. */
	private static final long RESOLVE_TIMEOUT_SECONDS = 25L;

	private final String pageUrl;
	private final String displayUrl;
	private final String ytdlpBinary;
	/** sow-012: play once and stop (false) vs loop (true) - only honoured for a NON-live VOD. */
	private final boolean repeat;
	/** sow-012: whether the LAST resolve reported a live source ({@code is_live=true}); volatile, set per resolve. */
	private volatile boolean lastResolvedLive;
	private volatile boolean loggedResolveFailure;

	public LinkResolvedFeed(String stationKey, StationEndpoints.Endpoint endpoint, String ytdlpBinary,
			String ffmpegBinary, int maxBufferFrames, int targetFrames, int prerollFrames, long maxBackoffSeconds,
			boolean reconnectPreserveBuffer, int reanchorLowWaterFrames, int reanchorSustainFrames,
			String smoothingMode, double maxStretchPercent, boolean repeat) {
		super(stationKey, ffmpegBinary, maxBufferFrames, targetFrames, prerollFrames, maxBackoffSeconds,
				reconnectPreserveBuffer, reanchorLowWaterFrames, reanchorSustainFrames,
				smoothingMode, maxStretchPercent);
		this.pageUrl = endpoint.effectiveUrl();
		this.displayUrl = endpoint.displayUrl();
		this.ytdlpBinary = ytdlpBinary;
		this.repeat = repeat;
	}

	@Override
	protected String[] buildCommand() {
		String resolved = resolveAudioUrl();
		String input = resolved != null ? resolved : YtDlpResolve.UNRESOLVED_INPUT;
		// Delegated to YtDlpResolve so the exact argv is pinned by offline unit tests
		// (-re pacing, the reconnect flags, and the file:-free protocol whitelist).
		return YtDlpResolve.ffmpegCommand(ffmpegBinary, input);
	}

	/**
	 * Runs yt-dlp and returns the resolved audio URL (or {@code null} on any failure), while also
	 * capturing {@code is_live} (→ {@link #lastResolvedLive}) so a non-live VOD can stop after one
	 * play while a live stream keeps reconnecting. Uses prefixed {@code --print} fields
	 * ({@code URL=}/{@code LIVE=}/{@code ABR=}) parsed by prefix, so line order / stray output can't
	 * misalign them. Never throws.
	 */
	private String resolveAudioUrl() {
		Process p = null;
		try {
			// argv (no shell) - the page URL cannot inject; the trailing "--" ends option parsing.
			// stderr DISCARDed so it can never fill a pipe and stall waitFor.
			// argv (no shell) - the page URL cannot inject; built by YtDlpResolve so the exact
			// arguments (format selector, printed fields, trailing "--") are pinned by unit tests.
			p = new ProcessBuilder(YtDlpResolve.argv(ytdlpBinary, pageUrl))
					.redirectError(ProcessBuilder.Redirect.DISCARD)
					.start();
			List<String> out = new ArrayList<>();
			try (BufferedReader r = new BufferedReader(
					new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					out.add(line);
				}
			}
			if (!p.waitFor(RESOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				return resolveFailed("timed out after " + RESOLVE_TIMEOUT_SECONDS + "s");
			}
			if (p.exitValue() != 0) {
				return resolveFailed("yt-dlp exit=" + p.exitValue());
			}
			YtDlpResolve.Resolved resolved = YtDlpResolve.parse(out);
			if (!resolved.ok()) {
				return resolveFailed("no URL printed");
			}
			// "True" => live (keeps reconnecting even with repeat off); "False"/"NA"/absent => finite.
			lastResolvedLive = resolved.live();
			loggedResolveFailure = false;
			// One line per successful (re)resolve: confirms is_live, the chosen protocol (so a source
			// that only offers DASH - which this ffmpeg build cannot demux - is visible in the log),
			// and that we get the best audio abr.
			SavePointRadioMod.LOGGER.info("[radio] platform resolved station={} live={} proto={} abr={}kbps link={}",
					stationKey(), lastResolvedLive, resolved.proto(), resolved.abr(), displayUrl);
			return resolved.url();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (Throwable t) {
			return resolveFailed(t.getClass().getSimpleName() + ": " + t.getMessage());
		} finally {
			// Always reap the process: a no-op once it has exited normally, and the fix for
			// the leak the review found (an exception after start() left it running).
			if (p != null) {
				p.destroyForcibly();
			}
		}
	}

	/** Logs a resolve failure once per failure streak (avoids spamming the reconnect loop). */
	private String resolveFailed(String why) {
		if (!loggedResolveFailure) {
			loggedResolveFailure = true;
			SavePointRadioMod.LOGGER.warn(
					"[radio] yt-dlp could not resolve station={} link={} ({}) - is '{}' installed and current? "
							+ "retrying on the reconnect ladder",
					stationKey(), displayUrl, why, ytdlpBinary);
		}
		return null;
	}

	/**
	 * sow-012: stop after one play only for a NON-live VOD with repeat off. A LIVE stream
	 * ({@link #lastResolvedLive}) keeps reconnecting regardless of repeat (a live source is a stream,
	 * not a finite track); a repeat-on disc always loops.
	 */
	@Override
	protected boolean stopAtEndOfInput() {
		return YtDlpResolve.stopAtEndOfInput(repeat, lastResolvedLive);
	}

	/** sow-006: a platform disc is "live" only when yt-dlp reported is_live=true (a Twitch/Kick live). */
	@Override
	public boolean isLive() {
		return lastResolvedLive;
	}

	@Override
	protected String startLogDetail() {
		return "link=" + displayUrl + " (yt-dlp bestaudio)";
	}
}
