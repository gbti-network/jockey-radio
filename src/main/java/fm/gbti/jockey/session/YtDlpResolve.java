package fm.gbti.jockey.session;

import fm.gbti.jockey.disc.PlatformPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure, dependency-free logic for the PLATFORM (yt-dlp) connector — the argv we hand yt-dlp, the
 * parsing of what it prints back, the ffmpeg command we build from it, and the live/repeat rules.
 *
 * <p><b>Why this class exists separately from {@link LinkResolvedFeed}.</b> The feed itself extends
 * {@link AbstractFfmpegFeed} and needs a running server, a jitter buffer and a live ffmpeg process,
 * so none of it is unit-testable. Everything here is static and side-effect free (it never spawns a
 * process or touches the network), so the connector contract can be pinned by fast offline tests —
 * see {@code YtDlpResolveTest}. {@code LinkResolvedFeed} delegates to this class so the tested code
 * really is the shipped code.</p>
 *
 * <p><b>Security invariants asserted by the tests, not just by review:</b> {@code file:} is never in
 * the ffmpeg protocol whitelist, and yt-dlp is invoked as argv with a trailing {@code --} so an
 * admin-supplied page URL can never be parsed as an option.</p>
 */
public final class YtDlpResolve {

	private YtDlpResolve() {
	}

	/**
	 * yt-dlp format selector. Prefers the best audio delivered over a protocol ffmpeg can demux
	 * (progressive http/https or HLS) and explicitly AVOIDS MPEG-DASH ({@link #DASH_PROTOCOL}): the
	 * shipped ffmpeg build carries only the {@code webm_dash_manifest} demuxer, so a resolved
	 * {@code .mpd} fails to open with "Invalid data found" (observed on Mixcloud, which offers HLS and
	 * progressive http alternatives). A bare {@code bestaudio/best} picks DASH on those sources.
	 *
	 * <p>The trailing {@code /bestaudio/best} keeps a DASH-only source resolving — it then fails at
	 * ffmpeg and rides the reconnect ladder, exactly as before — rather than returning no URL at all.
	 * Most sources are unaffected: their {@code bestaudio} is opus/aac over plain https, not DASH.</p>
	 */
	public static final String FORMAT_SELECTOR =
			"bestaudio[protocol!=http_dash_segments]/best[protocol!=http_dash_segments]/bestaudio/best";

	/** yt-dlp's protocol name for MPEG-DASH segment streams — the one this ffmpeg cannot demux. */
	public static final String DASH_PROTOCOL = "http_dash_segments";

	/**
	 * ffmpeg protocol whitelist for platform input. {@code file:} is deliberately absent: the resolved
	 * URL is always http/https, and a hostile or redirecting remote must never be able to turn ffmpeg
	 * into a local-file reader.
	 */
	public static final String PROTOCOL_WHITELIST = "http,https,tcp,tls,crypto,hls";

	/** A fast-failing input used when resolve fails, so ffmpeg exits quickly and the feed retries. */
	public static final String UNRESOLVED_INPUT = "http://127.0.0.1:1/yt-dlp-resolve-failed";

	/** Field prefixes printed by yt-dlp; parsed by prefix so line order can never misalign them. */
	private static final String P_URL = "URL=";
	private static final String P_LIVE = "LIVE=";
	private static final String P_ABR = "ABR=";
	private static final String P_PROTO = "PROTO=";

	/** One resolve result. {@code url} is null when yt-dlp printed nothing usable. */
	public record Resolved(String url, boolean live, String abr, String proto) {
		/** Whether a usable stream URL came back. */
		public boolean ok() {
			return url != null && !url.isBlank();
		}

		/** Whether the chosen format is MPEG-DASH, which the shipped ffmpeg cannot demux. */
		public boolean dash() {
			return DASH_PROTOCOL.equalsIgnoreCase(proto);
		}
	}

	/**
	 * Per-socket timeout (seconds) handed to yt-dlp. This is defence in depth, not the real
	 * bound: it caps a single stalled socket operation, while the caller's watchdog is what
	 * caps the whole extraction (yt-dlp issues many requests, and DNS resolution is not
	 * covered by a socket timeout at all).
	 */
	public static final String SOCKET_TIMEOUT_SECONDS = "15";

	/** The yt-dlp argv with no operator extra args. */
	public static String[] argv(String ytdlpBinary, String pageUrl) {
		return argv(ytdlpBinary, pageUrl, java.util.List.of());
	}

	/**
	 * The yt-dlp argv. Never a shell: the page URL is a separate argument and the trailing {@code --}
	 * ends option parsing, so a URL beginning with {@code -} cannot be read as a flag.
	 *
	 * <p>{@code extraArgs} (from {@code RadioConfig.ytdlpExtraArgs}) are inserted right before the
	 * {@code --} terminator, so an operator can pass e.g. {@code --cookies <file>}, {@code --proxy
	 * <url>}, or {@code --extractor-args <...>} to work around a platform's server-side blocking
	 * (e.g. datacenter-IP bot detection) without a code change. They still cannot inject the URL as a
	 * flag - it stays after {@code --}.</p>
	 */
	public static String[] argv(String ytdlpBinary, String pageUrl, java.util.List<String> extraArgs) {
		java.util.List<String> a = new java.util.ArrayList<>(java.util.List.of(
				ytdlpBinary, "-f", FORMAT_SELECTOR, "--no-playlist",
				"--no-warnings", "--quiet",
				"--socket-timeout", SOCKET_TIMEOUT_SECONDS,
				"--print", "URL=%(urls)s",
				"--print", "LIVE=%(is_live)s",
				"--print", "ABR=%(abr)s",
				"--print", "PROTO=%(protocol)s"));
		if (extraArgs != null) {
			for (String arg : extraArgs) {
				if (arg != null && !arg.isBlank()) {
					a.add(arg);
				}
			}
		}
		// After the operator's extra args: YouTube stays off even if they pass --use-extractors.
		a.add("--use-extractors");
		a.add(PlatformPolicy.YTDLP_EXTRACTORS);
		a.add("--");
		a.add(pageUrl);
		return a.toArray(new String[0]);
	}

	/**
	 * Parses yt-dlp's printed fields. Matching is by PREFIX and first-wins, so stray output, blank
	 * lines and reordered fields cannot misalign the result. {@code is_live} is true only for a
	 * literal "true" (case-insensitive); "False", "NA" and absence all mean finite.
	 */
	public static Resolved parse(Iterable<String> lines) {
		String url = null;
		String live = null;
		String abr = null;
		String proto = null;
		if (lines != null) {
			for (String line : lines) {
				if (line == null) {
					continue;
				}
				if (url == null && line.startsWith(P_URL)) {
					url = line.substring(P_URL.length()).trim();
				} else if (live == null && line.startsWith(P_LIVE)) {
					live = line.substring(P_LIVE.length()).trim();
				} else if (abr == null && line.startsWith(P_ABR)) {
					abr = line.substring(P_ABR.length()).trim();
				} else if (proto == null && line.startsWith(P_PROTO)) {
					proto = line.substring(P_PROTO.length()).trim();
				}
			}
		}
		if (url != null && url.isBlank()) {
			url = null;
		}
		return new Resolved(url, "true".equalsIgnoreCase(live), abr, proto);
	}

	/**
	 * The ffmpeg command for a resolved platform stream.
	 *
	 * <p>{@code -re} paces reads to the media clock: a resolved VOD edge delivers FASTER than
	 * real-time, over-filling the jitter buffer until it hits the ceiling and must re-anchor (an
	 * audible multi-second skip). It is a no-op for an already-real-time live stream, so it is safe on
	 * every platform source. {@code -reconnect*} lets ffmpeg ride brief drops without a feed respawn.</p>
	 */
	public static String[] ffmpegCommand(String ffmpegBinary, String input) {
		return new String[] {
				ffmpegBinary, "-nostdin", "-hide_banner", "-loglevel", "warning",
				"-re",
				"-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5",
				"-protocol_whitelist", PROTOCOL_WHITELIST,
				"-i", input,
				"-vn", "-f", "s16le", "-ar", "48000", "-ac", "1", "pipe:1"
		};
	}

	/**
	 * sow-012: stop after one play only for a NON-live VOD with repeat off. A LIVE stream keeps
	 * reconnecting regardless of repeat (a live source is a stream, not a finite track); a repeat-on
	 * disc always loops.
	 */
	public static boolean stopAtEndOfInput(boolean repeat, boolean live) {
		return !repeat && !live;
	}

	/** Convenience for tests/callers holding an array of output lines. */
	public static Resolved parse(String... lines) {
		List<String> list = new ArrayList<>();
		if (lines != null) {
			for (String l : lines) {
				list.add(l);
			}
		}
		return parse(list);
	}
}
