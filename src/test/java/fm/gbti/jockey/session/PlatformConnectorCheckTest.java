package fm.gbti.jockey.session;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * LIVE integrity check for every PLATFORM connector (sow-007 / sow-013). Runs the REAL yt-dlp against
 * one known URL per platform and asserts it still resolves to a format ffmpeg can actually demux.
 *
 * <p><b>This test is expected to break over time</b> — that is its job. When a
 * platform changes its framework, this is the check that says so, and it names the platform that
 * broke. Because it depends on the network, on yt-dlp being installed, and on remote content still
 * existing, it is tagged {@code connector} and is EXCLUDED from the normal test task. Run it
 * deliberately:</p>
 *
 * <pre>
 *   ./gradlew connectorCheck
 *   ./gradlew connectorCheck -Pytdlp=/path/to/yt-dlp
 * </pre>
 *
 * <p><b>Test URLs prefer SavePoint's own content</b> where possible: it is stable, it will not be
 * taken down under us, and it avoids repeatedly hammering third-party copyrighted material just to
 * run a build check. Each URL is overridable by system property so a dead link can be swapped without
 * touching code.</p>
 *
 * <p><b>Offline is not a failure.</b> For live platforms (Twitch/Kick) the channel simply may not be
 * streaming; that is reported as SKIPPED, not FAILED, so the check stays trustworthy.</p>
 */
@Tag("connector")
class PlatformConnectorCheckTest {

	/** yt-dlp is allowed a generous window here: cold DNS + page parse on a CI box. */
	private static final long TIMEOUT_SECONDS = 60L;

	private static String ytdlp() {
		return System.getProperty("jockey.ytdlp", "yt-dlp");
	}

	/** One platform under check. {@code live} marks sources where "offline" is a legitimate skip. */
	private record Target(String platform, String url, boolean live) {
		@Override
		public String toString() {
			return platform;
		}
	}

	private static String url(String key, String fallback) {
		return System.getProperty("jockey.connector." + key, fallback);
	}

	static Stream<Arguments> targets() {
		return Stream.of(
				new Target("Mixcloud", url("mixcloud",
						"https://www.mixcloud.com/jockyapp/dust-decibels-memory-leak-full-album/"), false),
				// Forss - "Flickermood": a long-standing Creative Commons track, so the smoke test does
				// not lean on third-party copyrighted material just to prove the extractor works.
				new Target("SoundCloud", url("soundcloud",
						"https://soundcloud.com/forss/flickermood"), false),
				new Target("Twitch", url("twitch",
						"https://www.twitch.tv/jockyapp"), true),
				new Target("Kick", url("kick",
						"https://kick.com/jockyapp"), true)
		).map(Arguments::of);
	}

	@ParameterizedTest(name = "{0} still resolves to an ffmpeg-demuxable format")
	@MethodSource("targets")
	@DisplayName("platform connector integrity")
	void connectorStillResolves(Target target) throws Exception {
		Assumptions.assumeTrue(
				Boolean.parseBoolean(System.getProperty("jockey.connector.enabled", "false")),
				"connector checks are opt-in — run ./gradlew connectorCheck");

		Result r = runYtDlp(target.url());

		if (r.binaryMissing) {
			Assumptions.abort("yt-dlp not found at '" + ytdlp()
					+ "' — install it or pass -Pytdlp=/path/to/yt-dlp");
		}

		if (r.exit != 0) {
			String err = r.stderr.toLowerCase(Locale.ROOT);
			// A live channel that simply is not streaming, or content pulled by its owner, is not a
			// broken connector — do not cry wolf.
			boolean benign = target.live()
					&& (err.contains("offline") || err.contains("not currently live")
						|| err.contains("no video") || err.contains("is not live"));
			boolean gone = err.contains("unavailable") || err.contains("not exist")
					|| err.contains("private") || err.contains("removed") || err.contains("404");
			if (benign) {
				Assumptions.abort(target.platform() + " channel is offline — connector not exercised");
			}
			if (gone) {
				Assumptions.abort(target.platform() + " test URL is gone; update -Djockey.connector."
						+ target.platform().toLowerCase(Locale.ROOT) + "=<url>. stderr: " + firstLine(r.stderr));
			}
			fail(target.platform() + " CONNECTOR BROKEN — yt-dlp exit=" + r.exit
					+ " for " + target.url() + "\nstderr: " + firstLine(r.stderr));
		}

		YtDlpResolve.Resolved resolved = YtDlpResolve.parse(r.stdout);

		assertTrue(resolved.ok(),
				target.platform() + " CONNECTOR BROKEN — yt-dlp exited 0 but printed no usable URL."
						+ "\noutput: " + r.stdout);

		// The whole point of the DASH-avoiding format selector: if this trips, the shipped ffmpeg
		// (which has no general MPEG-DASH demuxer) would fail to open the stream at runtime.
		assertFalse(resolved.dash(),
				target.platform() + " resolved to MPEG-DASH (" + resolved.proto() + "), which the shipped "
						+ "ffmpeg cannot demux. The format selector no longer protects this platform.");

		System.out.printf("  %-11s ok  live=%-5s proto=%-14s abr=%s%n",
				target.platform(), resolved.live(), resolved.proto(), resolved.abr());
	}

	// ------------------------------------------------------------------

	private record Result(int exit, List<String> stdout, String stderr, boolean binaryMissing) {
		Result(int exit, List<String> stdout, String stderr) {
			this(exit, stdout, stderr, false);
		}
	}

	/** Runs the production argv verbatim, but captures stderr so a failure can be classified. */
	private static Result runYtDlp(String pageUrl) throws Exception {
		ProcessBuilder pb = new ProcessBuilder(YtDlpResolve.argv(ytdlp(), pageUrl));
		Process p;
		try {
			p = pb.start();
		} catch (java.io.IOException missing) {
			return new Result(-1, List.of(), missing.getMessage(), true);
		}
		List<String> out = new ArrayList<>();
		StringBuilder err = new StringBuilder();
		try {
			Thread errPump = new Thread(() -> {
				try (BufferedReader r = new BufferedReader(
						new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
					String line;
					while ((line = r.readLine()) != null) {
						synchronized (err) {
							err.append(line).append('\n');
						}
					}
				} catch (Exception ignored) {
					// stderr is diagnostic only
				}
			});
			errPump.setDaemon(true);
			errPump.start();

			try (BufferedReader r = new BufferedReader(
					new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					out.add(line);
				}
			}
			if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				return new Result(-2, out, "timed out after " + TIMEOUT_SECONDS + "s");
			}
			errPump.join(2000);
			synchronized (err) {
				return new Result(p.exitValue(), out, err.toString());
			}
		} finally {
			p.destroyForcibly();
		}
	}

	private static String firstLine(String s) {
		if (s == null || s.isBlank()) {
			return "(no stderr)";
		}
		int nl = s.indexOf('\n');
		return (nl > 0 ? s.substring(0, nl) : s).trim();
	}
}
