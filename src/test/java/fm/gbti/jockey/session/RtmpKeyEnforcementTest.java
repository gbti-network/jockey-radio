package fm.gbti.jockey.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ffmpeg's RTMP listener only WARNS when a publisher uses the wrong stream name and then
 * accepts the publish, so the stream key is enforced by {@link RtmpReceiveFeed} itself.
 */
class RtmpKeyEnforcementTest {

	private static final String KEY = "RightKey_0123456789abcdef";

	private static RtmpReceiveFeed feed(String ffmpeg, int port) {
		return new RtmpReceiveFeed("test:rtmp", "127.0.0.1", port, KEY, ffmpeg,
				600, 150, 150, 5, true, 50, 0, "rebuffer", 0.0);
	}

	@Test
	void wrongStreamNameIsRejected() {
		RtmpReceiveFeed f = feed("ffmpeg", 1936);
		assertTrue(f.isRejectStderr("[rtmp @ 0x5644] Unexpected stream WRONG, expecting " + KEY));
		assertFalse(f.isRejectStderr("[rtmp @ 0x5644] Address already in use"));
		assertFalse(f.isRejectStderr("[in#0/flv @ 0x5644] Error during demuxing: Input/output error"));
	}

	@Test
	void rejectionLineMasksBothNames() {
		RtmpReceiveFeed f = feed("ffmpeg", 1936);
		String nearMiss = KEY.substring(0, KEY.length() - 1);
		String logged = f.sanitize("[rtmp @ 0x5644] Unexpected stream " + nearMiss + ", expecting " + KEY);
		assertFalse(logged.contains(nearMiss), logged);
		assertFalse(logged.contains(KEY), logged);
		assertEquals("[rtmp @ 0x5644] Unexpected stream ***, expecting ***", logged);
	}

	/**
	 * Live check against a real ffmpeg (opt-in: {@code ./gradlew ffmpegCheck}). A wrong-key
	 * publisher must contribute zero frames; the right key must still stream.
	 */
	@Test
	@Tag("ffmpeg")
	void listenerAdmitsOnlyTheRightKey() throws Exception {
		String ffmpeg = System.getProperty("jockey.ffmpeg", "ffmpeg");
		assumeTrue(Boolean.getBoolean("jockey.ffmpeg.enabled"), "live ffmpeg check not enabled");
		int port = freePort();
		RtmpReceiveFeed f = feed(ffmpeg, port);
		f.start();
		try {
			Thread.sleep(1500); // listener bind
			assertTrue(publish(ffmpeg, port, "WrongKey_0123456789abcdef"), "wrong-key publisher did not run");
			Thread.sleep(500);
			assertEquals(0L, f.framesRead(), "a wrong-key publisher must contribute no audio");

			Thread.sleep(1000); // listener respawn after the rejected connection
			assertTrue(publish(ffmpeg, port, KEY), "right-key publisher did not run");
			assertTrue(f.framesRead() > 0L, "the right key must still stream");
		} finally {
			f.stop();
		}
	}

	/** Pushes 3 s of a test tone; true when the publisher process ran to an exit. */
	private static boolean publish(String ffmpeg, int port, String key) throws IOException, InterruptedException {
		Process p = new ProcessBuilder(ffmpeg, "-hide_banner", "-loglevel", "error", "-re",
				"-f", "lavfi", "-i", "sine=frequency=440:duration=3",
				"-c:a", "aac", "-f", "flv", "rtmp://127.0.0.1:" + port + "/live/" + key)
				.redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.start();
		if (!p.waitFor(15, TimeUnit.SECONDS)) {
			p.destroyForcibly();
			return false;
		}
		return true;
	}

	private static int freePort() throws IOException {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}
