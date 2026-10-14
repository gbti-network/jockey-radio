package fm.gbti.jockey.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A client that connects to the RTMP listener and sends nothing must not lock the real
 * publisher out: ffmpeg's read timeout drops it, and the dropped client counts as idle so the
 * listener comes back on the short idle delay.
 */
class RtmpListenerTimeoutTest {

	private static final String KEY = "RightKey_0123456789abcdef";

	private static RtmpReceiveFeed feed(String ffmpeg, int port) {
		return new RtmpReceiveFeed("test:rtmp-timeout", "127.0.0.1", port, KEY, ffmpeg,
				600, 150, 150, 5, true, 50, 0, "rebuffer", 0.0);
	}

	@Test
	void listenerHasAReadTimeoutBeforeItsInput() {
		List<String> cmd = List.of(feed("ffmpeg", 1936).buildCommand());
		int timeout = cmd.indexOf("-rw_timeout");
		assertTrue(timeout >= 0, "no -rw_timeout in " + cmd);
		assertTrue(timeout < cmd.indexOf("-i"), "-rw_timeout must come before -i to apply to the input");
		assertTrue(cmd.get(timeout + 1).equals(RtmpReceiveFeed.READ_TIMEOUT_MICROS));
	}

	@Test
	void droppedClientIsIdleButABindFailureIsNot() {
		RtmpReceiveFeed f = feed("ffmpeg", 1936);
		// What ffmpeg 9.0.2 prints when the read timeout drops a client mid-handshake.
		assertTrue(f.classifyIdle(drainOf(f,
				"[rtmp @ 0x55cc] Unable to read handshake",
				"[in#0 @ 0x55cc] Error opening input: Input/output error",
				"Error opening input files: Input/output error")));
		// And when the port cannot be bound.
		assertFalse(f.classifyIdle(drainOf(f,
				"[rtmp @ 0x556c] Cannot open connection tcp://127.0.0.1:1936?listen&listen_timeout=-1",
				"[in#0 @ 0x556c] Error opening input: Address already in use",
				"Error opening input files: Address already in use")));
	}

	/**
	 * Live check against a real ffmpeg (opt-in: {@code ./gradlew ffmpegCheck}). Without the read
	 * timeout the idle client holds the single-client listener and the publisher is refused.
	 */
	@Test
	@Tag("ffmpeg")
	void publisherGetsInAfterAnIdleClient() throws Exception {
		String ffmpeg = System.getProperty("jockey.ffmpeg", "ffmpeg");
		assumeTrue(Boolean.getBoolean("jockey.ffmpeg.enabled"), "live ffmpeg check not enabled");
		int port = freePort();
		RtmpReceiveFeed f = feed(ffmpeg, port);
		f.start();
		try (Socket idle = new Socket()) {
			Thread.sleep(1500); // listener bind
			idle.connect(new InetSocketAddress("127.0.0.1", port), 2000);
			// The read timeout is 10 s; give it that plus the respawn.
			Thread.sleep(12_000);
			publish(ffmpeg, port);
			assertTrue(f.framesRead() > 0L, "the publisher was locked out by an idle client");
		} finally {
			f.stop();
		}
	}

	private static AbstractFfmpegFeed.StderrDrain drainOf(RtmpReceiveFeed f, String... lines) {
		AbstractFfmpegFeed.StderrDrain drain = new AbstractFfmpegFeed.StderrDrain();
		for (String line : lines) {
			if (f.isIdleStderr(line)) {
				drain.sawIdle.set(true);
			}
			if (f.isFatalStderr(line)) {
				drain.sawFatal.set(true);
			}
		}
		return drain;
	}

	/** Pushes 3 s of a test tone with the right key. */
	private static void publish(String ffmpeg, int port) throws IOException, InterruptedException {
		Process p = new ProcessBuilder(ffmpeg, "-hide_banner", "-loglevel", "error", "-re",
				"-f", "lavfi", "-i", "sine=frequency=440:duration=3",
				"-c:a", "aac", "-f", "flv", "rtmp://127.0.0.1:" + port + "/live/" + KEY)
				.redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.start();
		if (!p.waitFor(15, TimeUnit.SECONDS)) {
			p.destroyForcibly();
		}
	}

	private static int freePort() throws IOException {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}
