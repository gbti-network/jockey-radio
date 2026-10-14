package fm.gbti.jockey.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A play-once (repeat off) disc finishes only when its input ends cleanly. ffmpeg exits 0 for
 * a connection that broke off mid-track too, so the ingest loop has to read stderr to tell
 * them apart; a failure retries, and a file resumes where it stopped.
 */
class PlayOnceEndTest {

	private static final String BASH = "/bin/bash";
	/** 100 frames of silence on stdout, then a clean exit. */
	private static final String CLEAN = "head -c 192000 /dev/zero; exit 0";
	/** The same, but the input broke off: ffmpeg 9.0.2's line for it, and still exit 0. */
	private static final String BROKEN =
			"head -c 192000 /dev/zero; echo '[in#0/mp3 @ 0x1] Error during demuxing: Input/output error' >&2; exit 0";

	@Test
	@DisplayName("a clean end finishes the disc after one play")
	void cleanEndFinishes() throws Exception {
		ScriptedFeed feed = run(CLEAN);
		assertTrue(waitFor(() -> feed.inputEnded, 5_000), "a clean end did not finish the disc");
		assertEquals(0, feed.failures.get());
		assertEquals(0L, feed.stats.reconnects.get());
		feed.stop();
	}

	@Test
	@DisplayName("an input that broke off retries, and gives up after five failures in a row")
	void brokenInputRetries() throws Exception {
		ScriptedFeed feed = run(BROKEN);
		assertTrue(waitFor(() -> feed.failures.get() >= 1, 5_000), "a broken input was never retried");
		assertEquals(100L, feed.framesAtFirstFailure.get(), "the retry was not told how far the input got");
		assertTrue(waitFor(() -> feed.inputEnded, 10_000), "a source that always breaks retried forever");
		assertEquals(FeedIngestLoop.MAX_PLAY_ONCE_FAILURES - 1, feed.failures.get());
		feed.stop();
	}

	/**
	 * Live check against a real ffmpeg (opt-in: {@code ./gradlew ffmpegCheck}): the first
	 * transfer of a 6 s file is cut at 40%; the disc must resume and play the rest once.
	 */
	@Test
	@Tag("ffmpeg")
	void cutFileResumesAndPlaysOnce(@TempDir Path dir) throws Exception {
		String ffmpeg = System.getProperty("jockey.ffmpeg", "ffmpeg");
		assumeTrue(Boolean.getBoolean("jockey.ffmpeg.enabled"), "live ffmpeg check not enabled");
		Path mp3 = dir.resolve("tone.mp3");
		Process enc = new ProcessBuilder(ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi",
				"-i", "sine=frequency=440:duration=6", "-c:a", "libmp3lame", mp3.toString())
				.redirectErrorStream(true).start();
		assertEquals(0, enc.waitFor());
		byte[] data = Files.readAllBytes(mp3);

		AtomicInteger requests = new AtomicInteger();
		List<String> ranges = new CopyOnWriteArrayList<>();
		HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		http.createContext("/tone.mp3", ex -> {
			int n = requests.incrementAndGet();
			String range = ex.getRequestHeaders().getFirst("Range");
			ranges.add(String.valueOf(range));
			int start = 0;
			if (range != null && range.startsWith("bytes=") && range.endsWith("-")) {
				start = Integer.parseInt(range.substring(6, range.length() - 1));
			}
			int length = data.length - start;
			ex.getResponseHeaders().set("Accept-Ranges", "bytes");
			if (start > 0) {
				ex.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + (data.length - 1) + "/" + data.length);
			}
			ex.sendResponseHeaders(start > 0 ? 206 : 200, length);
			try (OutputStream out = ex.getResponseBody()) {
				// The first transfer breaks off 40% in, with the full length promised.
				int send = n == 1 ? (int) (data.length * 0.4) : length;
				out.write(data, start, send);
			}
		});
		http.start();
		FileLoopFeed feed = new FileLoopFeed("test:play-once",
				StationEndpoints.forUrl("http://127.0.0.1:" + http.getAddress().getPort() + "/tone.mp3", null),
				ffmpeg, 600, 50, 50, 2, true, 1, 0, "rebuffer", 0.0, false);
		feed.start();
		try {
			assertTrue(waitFor(() -> feed.inputEnded, 30_000), "the disc never finished");
			long frames = feed.framesRead();
			assertTrue(requests.get() >= 2, "the cut transfer was never retried: " + ranges);
			// 6 s is 300 frames. Ending at the cut reads ~120; replaying from the top ~420.
			assertTrue(frames >= 270 && frames <= 345, "read " + frames + " frames over requests " + ranges);
		} finally {
			feed.stop();
			http.stop(0);
		}
	}

	private static ScriptedFeed run(String script) {
		assumeTrue(new File(BASH).canExecute(), "needs /bin/bash");
		ScriptedFeed feed = new ScriptedFeed(script);
		feed.start();
		return feed;
	}

	private static boolean waitFor(java.util.function.BooleanSupplier done, long millis) throws InterruptedException {
		long until = System.currentTimeMillis() + millis;
		while (System.currentTimeMillis() < until) {
			if (done.getAsBoolean()) {
				return true;
			}
			Thread.sleep(20);
		}
		return done.getAsBoolean();
	}

	/** A repeat-off feed whose "ffmpeg" is a shell script, retrying fast. */
	private static final class ScriptedFeed extends AbstractFfmpegFeed {
		final String script;
		final AtomicInteger failures = new AtomicInteger();
		final AtomicLong framesAtFirstFailure = new AtomicLong(-1);

		ScriptedFeed(String script) {
			super("test:scripted", BASH, 600, 50, 50, 2, true, 1, 0, "rebuffer", 0.0);
			this.script = script;
		}

		@Override
		protected String[] buildCommand() {
			return new String[] {BASH, "-c", script};
		}

		@Override
		protected String startLogDetail() {
			return "scripted test input";
		}

		@Override
		protected boolean stopAtEndOfInput() {
			return true;
		}

		@Override
		protected long reconnectDelayMillis(long backoffSeconds, boolean idle, long attempt) {
			return 10L;
		}

		@Override
		protected void onInputFailed(long framesRead) {
			framesAtFirstFailure.compareAndSet(-1, framesRead);
			failures.incrementAndGet();
		}
	}

	@Test
	@DisplayName("ffmpeg's broken-input lines are recognised, and a clean warning is not")
	void inputErrorLines() {
		assertTrue(FeedIngestLoop.isInputError("[in#0/mp3 @ 0x5563] Error during demuxing: Input/output error"));
		assertTrue(FeedIngestLoop.isInputError("[http @ 0x5563] Stream ends prematurely at 19400, should be 48500"));
		assertFalse(FeedIngestLoop.isInputError("[mp3 @ 0x5563] Estimating duration from bitrate, this may be inaccurate"));
	}
}
