package fm.savepoint.radio.session;

import fm.savepoint.radio.SavePointRadioMod;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Platform-link feed (sow-007): plays a SoundCloud / Mixcloud / Bandcamp / Twitch / Kick
 * <em>page</em> URL by resolving it to a real audio stream with <b>yt-dlp</b>, then handing
 * that stream to the shared {@link AbstractFfmpegFeed} pipeline. Backs
 * {@link fm.savepoint.radio.disc.DiscDefinition.SourceType#PLATFORM} discs.
 *
 * <p><b>Why a resolver.</b> A {@code soundcloud.com/…} page is NOT a media stream, so ffmpeg
 * cannot open it. {@code yt-dlp -f bestaudio -g <url>} prints a direct audio (or self-refreshing
 * live-HLS) URL, which ffmpeg plays with the same {@code -vn -f s16le} decode every other feed
 * uses. Audio-only ({@code bestaudio}) matches the existing {@code -vn} and saves bandwidth.
 *
 * <p><b>Resolve-per-spawn.</b> {@link #buildCommand()} re-resolves on every (re)connect, so an
 * expired signed CDN URL or a rolled live edge is refreshed for free by the existing
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
	/** Operator extra args for yt-dlp (cookies/proxy/extractor-args); never null. */
	private final List<String> ytdlpExtraArgs;
	/**
	 * Directory of {@link #ytdlpBinary} when it is a real path, else null. Prepended to the yt-dlp
	 * subprocess PATH so a co-located JS runtime (the bundled {@code deno}) is auto-detected - yt-dlp
	 * needs it to solve some platforms' signature challenge.
	 */
	private final String ytdlpBinDir;
	/** sow-012: play once and stop (false) vs loop (true) - only honoured for a NON-live VOD. */
	private final boolean repeat;
	/** sow-012: whether the LAST resolve reported a live source ({@code is_live=true}); volatile, set per resolve. */
	private volatile boolean lastResolvedLive;
	private volatile boolean loggedResolveFailure;
	/** The in-flight yt-dlp child, published so {@link #onStop()} can kill it; null when idle. */
	private volatile Process resolveProcess;

	public LinkResolvedFeed(String stationKey, StationEndpoints.Endpoint endpoint, String ytdlpBinary,
			List<String> ytdlpExtraArgs, String ffmpegBinary, int maxBufferFrames, int targetFrames,
			int prerollFrames, long maxBackoffSeconds, boolean reconnectPreserveBuffer,
			int reanchorLowWaterFrames, int reanchorSustainFrames, String smoothingMode,
			double maxStretchPercent, boolean repeat) {
		super(stationKey, ffmpegBinary, maxBufferFrames, targetFrames, prerollFrames, maxBackoffSeconds,
				reconnectPreserveBuffer, reanchorLowWaterFrames, reanchorSustainFrames,
				smoothingMode, maxStretchPercent);
		this.pageUrl = endpoint.effectiveUrl();
		this.displayUrl = endpoint.displayUrl();
		this.ytdlpBinary = ytdlpBinary;
		this.ytdlpExtraArgs = ytdlpExtraArgs == null ? List.of() : List.copyOf(ytdlpExtraArgs);
		this.ytdlpBinDir = binDirOf(ytdlpBinary);
		this.repeat = repeat;
		// The page URL is usually public, but the RESOLVED media URL that ends up in the
		// ffmpeg argv is a signed CDN link. Registering here means the base sanitize masks
		// it in the diag arg-shape and in any stderr line that echoes it.
		redactEndpoint(endpoint);
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
		CompletableFuture<Void> killer = null;
		AtomicBoolean timedOut = new AtomicBoolean();
		try {
			// argv (no shell) - the page URL cannot inject; built by YtDlpResolve so the exact
			// arguments (format selector, printed fields, extra args, trailing "--") are pinned by
			// unit tests. redirectErrorStream folds yt-dlp's stderr INTO stdout: the prefix parser
			// ignores non-prefix lines, and on failure we keep the tail as the real reason (so a
			// "Sign in to confirm you're not a bot" / JS-runtime error reaches the operator's log
			// instead of a bare "exit=1"). One merged stream also means no second pipe to drain.
			ProcessBuilder pb = new ProcessBuilder(YtDlpResolve.argv(ytdlpBinary, pageUrl, ytdlpExtraArgs))
					.redirectErrorStream(true);
			if (ytdlpBinDir != null) {
				// Put the bundled bin dir on PATH so yt-dlp finds the co-located `deno` JS runtime
				// (enabled by default in yt-dlp) - required for some platforms' signature challenge.
				String path = pb.environment().getOrDefault("PATH", "");
				pb.environment().put("PATH",
						path.isEmpty() ? ytdlpBinDir : ytdlpBinDir + java.io.File.pathSeparator + path);
			}
			p = pb.start();
			// Publish the handle so stop() can kill a resolve that is still running. Without
			// this the child is reachable only from this frame: an interrupt cannot break a
			// blocking pipe read, so neither stop() nor shutdown() could ever free it.
			resolveProcess = p;
			// Close the publish race: if stop() ran between start() and this publish, its
			// onStop() read a null resolveProcess and killed nothing, so kill it ourselves.
			if (!isRunning()) {
				p.destroyForcibly();
				return null;
			}
			// THE deadline. It has to arm BEFORE the read, not after: draining stdout to EOF
			// first meant the waitFor below only ever bounded the gap between EOF and exit, so
			// a yt-dlp that stalls while holding stdout open was unbounded. Killing the child
			// closes the pipe, which is what actually ends the read.
			Process child = p;
			killer = CompletableFuture.runAsync(
					() -> {
						timedOut.set(true);
						child.destroyForcibly();
					},
					CompletableFuture.delayedExecutor(RESOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
			List<String> out = new ArrayList<>();
			try (BufferedReader r = new BufferedReader(
					new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					out.add(line);
				}
			}
			// Prefer a good result over the timeout flag: yt-dlp can print every field and close
			// stdout in the same instant the watchdog fires, and a resolve that actually produced
			// a URL must not be thrown away as "timed out" (a needless reconnect + spurious WARN).
			YtDlpResolve.Resolved resolved = YtDlpResolve.parse(out);
			if (!resolved.ok()) {
				// yt-dlp's own error text (its stderr, merged in), sanitized and tail-trimmed.
				String detail = ytdlpErrorTail(out);
				String suffix = detail.isEmpty() ? "" : ": " + detail;
				if (timedOut.get()) {
					return resolveFailed("timed out after " + RESOLVE_TIMEOUT_SECONDS + "s" + suffix);
				}
				if (!p.waitFor(RESOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
					return resolveFailed("timed out after " + RESOLVE_TIMEOUT_SECONDS + "s" + suffix);
				}
				if (p.exitValue() != 0) {
					return resolveFailed("yt-dlp exit=" + p.exitValue() + suffix);
				}
				return resolveFailed("no URL printed" + suffix);
			}
			// "True" => live (keeps reconnecting even with repeat off); "False"/"NA"/absent => finite.
			lastResolvedLive = resolved.live();
			loggedResolveFailure = false;
			// One line per successful (re)resolve: confirms is_live, the chosen protocol (so a source
			// that only offers DASH - which this ffmpeg build cannot demux - is visible in the log),
			// and that we get the best audio abr.
			SavePointRadioMod.LOGGER.info("[jukebox] platform resolved station={} live={} proto={} abr={}kbps link={}",
					stationKey(), lastResolvedLive, resolved.proto(), resolved.abr(), displayUrl);
			return resolved.url();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (Throwable t) {
			return resolveFailed(t.getClass().getSimpleName() + ": " + t.getMessage());
		} finally {
			if (killer != null) {
				// Disarms the watchdog: a cancelled CompletableFuture skips its action, so a
				// resolve that finished in time never destroys anything.
				killer.cancel(false);
			}
			resolveProcess = null;
			// Always reap the process: a no-op once it has exited normally, and the fix for
			// the leak the review found (an exception after start() left it running).
			if (p != null) {
				p.destroyForcibly();
			}
		}
	}

	/** Kills a resolve still in flight when the feed is stopped (see {@link #resolveProcess}). */
	@Override
	protected void onStop() {
		Process p = resolveProcess;
		if (p != null) {
			p.destroyForcibly();
		}
	}

	/** The parent directory of a real yt-dlp path (for the deno PATH), or null for a bare PATH name. */
	private static String binDirOf(String binary) {
		if (binary == null || binary.isBlank()) {
			return null;
		}
		java.nio.file.Path p = java.nio.file.Path.of(binary);
		// A bare "yt-dlp" (PATH lookup) has no usable parent dir; only a real path co-locates deno.
		if (!p.isAbsolute() && p.getNameCount() == 1) {
			return null;
		}
		java.nio.file.Path parent = p.toAbsolutePath().getParent();
		return parent == null ? null : parent.toString();
	}

	/**
	 * yt-dlp's own error/warning text, extracted from the merged output: every line that is NOT one
	 * of our {@code URL=}/{@code LIVE=}/{@code ABR=}/{@code PROTO=} prints is yt-dlp's stderr. Returns
	 * the last couple of such lines, sanitized (so a page URL/token in the message is redacted) and
	 * length-capped, or "" when yt-dlp said nothing.
	 */
	private String ytdlpErrorTail(List<String> out) {
		List<String> errs = new ArrayList<>();
		for (String line : out) {
			if (line == null) {
				continue;
			}
			String t = line.strip();
			if (t.isEmpty() || t.startsWith("URL=") || t.startsWith("LIVE=")
					|| t.startsWith("ABR=") || t.startsWith("PROTO=")) {
				continue;
			}
			errs.add(t);
		}
		if (errs.isEmpty()) {
			return "";
		}
		String tail = String.join(" | ", errs.subList(Math.max(0, errs.size() - 2), errs.size()));
		tail = sanitize(tail);
		return tail.length() > 240 ? tail.substring(0, 240) + "…" : tail;
	}

	/** Logs a resolve failure once per failure streak (avoids spamming the reconnect loop). */
	private String resolveFailed(String why) {
		if (!loggedResolveFailure) {
			loggedResolveFailure = true;
			SavePointRadioMod.LOGGER.warn(
					"[jukebox] yt-dlp could not resolve station={} link={} ({}) - is '{}' installed and current? "
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
