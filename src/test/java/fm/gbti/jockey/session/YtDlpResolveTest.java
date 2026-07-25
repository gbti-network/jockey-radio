package fm.gbti.jockey.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline contract tests for the PLATFORM connector logic. No network, no processes, no Minecraft.
 *
 * <p>These pin the things that have actually broken in production: the DASH-avoiding format selector
 * (the Mixcloud failure), prefix-based parsing of yt-dlp's printed fields, live-vs-VOD repeat
 * semantics, and the ffmpeg security guardrails.</p>
 */
class YtDlpResolveTest {

	@Nested
	@DisplayName("format selector")
	class FormatSelector {

		@Test
		@DisplayName("excludes MPEG-DASH before falling back (regression: Mixcloud would not load)")
		void excludesDashFirst() {
			String sel = YtDlpResolve.FORMAT_SELECTOR;
			int firstDashExclusion = sel.indexOf("protocol!=" + YtDlpResolve.DASH_PROTOCOL);
			int bareFallback = sel.indexOf("/bestaudio/best");

			assertAll(
					() -> assertTrue(firstDashExclusion >= 0,
							"selector must exclude " + YtDlpResolve.DASH_PROTOCOL + ": " + sel),
					() -> assertTrue(bareFallback > firstDashExclusion,
							"the DASH-free preferences must come BEFORE the bare fallback: " + sel));
		}

		@Test
		@DisplayName("keeps a bare fallback so a DASH-only source still resolves")
		void keepsFallback() {
			assertTrue(YtDlpResolve.FORMAT_SELECTOR.endsWith("/bestaudio/best"),
					"a DASH-only source must still resolve (then fail at ffmpeg and retry), "
							+ "rather than returning no URL at all");
		}
	}

	@Nested
	@DisplayName("argv")
	class Argv {

		@Test
		@DisplayName("passes the page URL as its own argument after a terminating --")
		void pageUrlCannotInject() {
			String hostile = "-oh-no-a-flag";
			String[] argv = YtDlpResolve.argv("yt-dlp", hostile);
			List<String> list = Arrays.asList(argv);

			int dashDash = list.indexOf("--");
			assertAll(
					() -> assertTrue(dashDash > 0, "argv must contain a terminating -- : " + list),
					() -> assertEquals(hostile, argv[argv.length - 1], "URL must be the final argument"),
					() -> assertEquals(argv.length - 1, dashDash + 1,
							"the URL must sit immediately after -- so it can never be parsed as an option"));
		}

		@Test
		@DisplayName("requests all four fields and the tested format selector")
		void requestsExpectedFields() {
			List<String> argv = Arrays.asList(YtDlpResolve.argv("yt-dlp", "https://example.test/x"));
			assertAll(
					() -> assertTrue(argv.contains(YtDlpResolve.FORMAT_SELECTOR), "format selector"),
					() -> assertTrue(argv.contains("URL=%(urls)s"), "URL field"),
					() -> assertTrue(argv.contains("LIVE=%(is_live)s"), "is_live field"),
					() -> assertTrue(argv.contains("ABR=%(abr)s"), "abr field"),
					() -> assertTrue(argv.contains("PROTO=%(protocol)s"), "protocol field"),
					() -> assertTrue(argv.contains("--no-playlist"), "a page link must not expand to a playlist"));
		}

		@Test
		@DisplayName("operator extra args are inserted before -- and the URL stays last")
		void extraArgsBeforeTerminator() {
			String[] argv = YtDlpResolve.argv("yt-dlp", "https://x.test/v",
					java.util.List.of("--cookies", "cookies.txt", "--proxy", "http://p:1"));
			List<String> list = Arrays.asList(argv);
			int dashDash = list.indexOf("--");
			assertAll(
					() -> assertTrue(list.contains("--cookies") && list.contains("cookies.txt"), "cookies passed"),
					() -> assertTrue(list.contains("--proxy"), "proxy passed"),
					() -> assertTrue(list.indexOf("--cookies") < dashDash, "extra args come before the -- terminator"),
					() -> assertEquals("https://x.test/v", argv[argv.length - 1], "URL is still last"),
					() -> assertEquals(argv.length - 1, dashDash + 1, "URL sits immediately after --"));
		}

		@Test
		@DisplayName("null/blank extra args are ignored")
		void extraArgsNullSafe() {
			String[] a = YtDlpResolve.argv("yt-dlp", "https://x.test", null);
			String[] b = YtDlpResolve.argv("yt-dlp", "https://x.test", java.util.List.of("", "  "));
			assertEquals("https://x.test", a[a.length - 1]);
			assertEquals("https://x.test", b[b.length - 1]);
		}

		@Test
		@DisplayName("bounds a stalled socket so the child self-limits")
		void passesSocketTimeout() {
			List<String> argv = Arrays.asList(YtDlpResolve.argv("yt-dlp", "https://example.test/x"));
			int idx = argv.indexOf("--socket-timeout");
			assertAll(
					() -> assertTrue(idx >= 0, "--socket-timeout must be passed: " + argv),
					() -> assertEquals(YtDlpResolve.SOCKET_TIMEOUT_SECONDS, argv.get(idx + 1),
							"the timeout value must follow the flag"));
		}

		@Test
		@DisplayName("honours a custom binary path (operator-supplied yt-dlp)")
		void honoursCustomBinary() {
			assertEquals("/opt/bin/yt-dlp", YtDlpResolve.argv("/opt/bin/yt-dlp", "https://x.test")[0]);
		}
	}

	@Nested
	@DisplayName("parsing yt-dlp output")
	class Parsing {

		@Test
		@DisplayName("reads all four fields")
		void readsAllFields() {
			YtDlpResolve.Resolved r = YtDlpResolve.parse(
					"URL=https://cdn.test/a.m3u8",
					"LIVE=False",
					"ABR=130.014",
					"PROTO=m3u8_native");

			assertAll(
					() -> assertEquals("https://cdn.test/a.m3u8", r.url()),
					() -> assertFalse(r.live()),
					() -> assertEquals("130.014", r.abr()),
					() -> assertEquals("m3u8_native", r.proto()),
					() -> assertTrue(r.ok()),
					() -> assertFalse(r.dash()));
		}

		@Test
		@DisplayName("is order-independent and tolerates stray/blank lines")
		void orderIndependent() {
			YtDlpResolve.Resolved r = YtDlpResolve.parse(
					"[mixcloud] Extracting URL: ...",
					"",
					"PROTO=m3u8_native",
					"ABR=64.004",
					"URL=https://cdn.test/stream.m3u8",
					"some other noise",
					"LIVE=NA");

			assertAll(
					() -> assertEquals("https://cdn.test/stream.m3u8", r.url()),
					() -> assertEquals("m3u8_native", r.proto()),
					() -> assertFalse(r.live(), "NA is not live"));
		}

		@ParameterizedTest(name = "LIVE={0} -> live={1}")
		@CsvSource({ "True,true", "true,true", "TRUE,true", "False,false", "NA,false", "'',false" })
		@DisplayName("only a literal true means live")
		void liveParsing(String value, boolean expected) {
			assertEquals(expected, YtDlpResolve.parse("URL=https://x.test/a", "LIVE=" + value).live());
		}

		@Test
		@DisplayName("absent LIVE field means finite, not live")
		void absentLiveIsFinite() {
			assertFalse(YtDlpResolve.parse("URL=https://x.test/a").live());
		}

		@Test
		@DisplayName("no URL, blank URL, or empty output is not ok()")
		void missingUrl() {
			assertAll(
					() -> assertFalse(YtDlpResolve.parse("LIVE=True").ok(), "no URL line"),
					() -> assertFalse(YtDlpResolve.parse("URL=   ").ok(), "blank URL"),
					() -> assertNull(YtDlpResolve.parse("URL=").url(), "blank URL normalises to null"),
					() -> assertFalse(YtDlpResolve.parse().ok(), "no output at all"),
					() -> assertFalse(YtDlpResolve.parse((Iterable<String>) null).ok(), "null input"));
		}

		@Test
		@DisplayName("first occurrence of a field wins")
		void firstWins() {
			assertEquals("https://first.test/a",
					YtDlpResolve.parse("URL=https://first.test/a", "URL=https://second.test/b").url());
		}

		@Test
		@DisplayName("detects a DASH result so the log can explain an ffmpeg failure")
		void detectsDash() {
			YtDlpResolve.Resolved r = YtDlpResolve.parse(
					"URL=https://cdn.test/manifest.mpd", "PROTO=" + YtDlpResolve.DASH_PROTOCOL);
			assertTrue(r.dash(), "a DASH result must be visible - this ffmpeg build cannot demux it");
		}

		@Test
		@DisplayName("a URL containing '=' survives intact")
		void urlWithEquals() {
			String url = "https://cdn.test/a?sig=abc==&x=1";
			assertEquals(url, YtDlpResolve.parse("URL=" + url).url());
		}
	}

	@Nested
	@DisplayName("ffmpeg command")
	class FfmpegCommand {

		@Test
		@DisplayName("never whitelists file: (must not become a local-file reader)")
		void neverAllowsFileProtocol() {
			List<String> cmd = Arrays.asList(YtDlpResolve.ffmpegCommand("ffmpeg", "https://cdn.test/a"));
			int idx = cmd.indexOf("-protocol_whitelist");

			assertAll(
					() -> assertTrue(idx >= 0, "a protocol whitelist must always be set"),
					() -> assertFalse(cmd.get(idx + 1).contains("file"),
							"file: must never be whitelisted: " + cmd.get(idx + 1)),
					() -> assertEquals(YtDlpResolve.PROTOCOL_WHITELIST, cmd.get(idx + 1)));
		}

		@Test
		@DisplayName("paces reads with -re (regression: fast VOD over-filled the buffer and re-anchored)")
		void pacesWithRe() {
			assertTrue(Arrays.asList(YtDlpResolve.ffmpegCommand("ffmpeg", "https://cdn.test/a")).contains("-re"),
					"-re must be present or a fast source over-fills the jitter buffer and skips");
		}

		@Test
		@DisplayName("decodes to the pipeline's expected raw format")
		void decodeTarget() {
			List<String> cmd = Arrays.asList(YtDlpResolve.ffmpegCommand("ffmpeg", "https://cdn.test/a"));
			assertAll(
					() -> assertTrue(cmd.contains("-vn"), "audio only"),
					() -> assertEquals("s16le", cmd.get(cmd.indexOf("-f") + 1)),
					() -> assertEquals("48000", cmd.get(cmd.indexOf("-ar") + 1)),
					() -> assertEquals("1", cmd.get(cmd.indexOf("-ac") + 1), "mono - SVC positional audio"),
					() -> assertEquals("pipe:1", cmd.get(cmd.size() - 1)));
		}

		@Test
		@DisplayName("input is passed as its own argument after -i")
		void inputIsSeparateArgument() {
			String input = "https://cdn.test/a?x=1";
			List<String> cmd = Arrays.asList(YtDlpResolve.ffmpegCommand("ffmpeg", input));
			assertEquals(input, cmd.get(cmd.indexOf("-i") + 1));
		}

		@Test
		@DisplayName("the unresolved sentinel fails fast on loopback rather than hanging")
		void unresolvedSentinel() {
			assertAll(
					() -> assertTrue(YtDlpResolve.UNRESOLVED_INPUT.startsWith("http://127.0.0.1:"),
							"must target loopback so it fails immediately"),
					() -> assertFalse(YtDlpResolve.UNRESOLVED_INPUT.startsWith("file:"),
							"the failure sentinel must never be a file: URL"));
		}
	}

	@Nested
	@DisplayName("repeat / liveness semantics (sow-012)")
	class RepeatSemantics {

		@ParameterizedTest(name = "repeat={0} live={1} -> stopAtEnd={2}")
		@CsvSource({
				// A live stream ignores repeat entirely - it is a stream, not a finite track.
				"true,  true,  false",
				"false, true,  false",
				// A finite VOD stops only when the operator turned repeat off.
				"true,  false, false",
				"false, false, true"
		})
		void truthTable(boolean repeat, boolean live, boolean expected) {
			assertEquals(expected, YtDlpResolve.stopAtEndOfInput(repeat, live));
		}

		@Test
		@DisplayName("a live source never stops, whatever repeat says")
		void liveNeverStops() {
			assertAll(
					() -> assertFalse(YtDlpResolve.stopAtEndOfInput(true, true)),
					() -> assertFalse(YtDlpResolve.stopAtEndOfInput(false, true)));
		}
	}
}
