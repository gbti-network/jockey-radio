package fm.gbti.jockey.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every teardown path ends a child that ignores SIGTERM, without blocking its caller. The
 * child here stands in for an ffmpeg parked on a read that its first SIGTERM cannot interrupt.
 */
class ProcessTeardownTest {

	/** A child that ignores SIGTERM: bash sets the disposition, and exec keeps it for sleep. */
	private static final String[] STUBBORN = {"/bin/bash", "-c", "trap '' TERM; exec sleep 60"};

	private static Process stubborn() throws IOException {
		assumeTrue(new File(STUBBORN[0]).canExecute(), "needs /bin/bash");
		return new ProcessBuilder(STUBBORN).redirectErrorStream(true).start();
	}

	private static boolean diesWithin(Process p, long millis) throws InterruptedException {
		return p.waitFor(millis, TimeUnit.MILLISECONDS);
	}

	@Test
	@DisplayName("the reaper kills a child that ignores SIGTERM once the grace runs out")
	void reaperEscalates() throws Exception {
		Process p = stubborn();
		try {
			Thread.sleep(300); // let bash install the trap before the signal
			long start = System.nanoTime();
			ProcessReaper.terminate(p, 500);
			assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 100, "terminate blocked its caller");
			assertFalse(diesWithin(p, 300), "the child was meant to ignore SIGTERM");
			assertTrue(diesWithin(p, 3000), "still alive after the grace");
		} finally {
			p.destroyForcibly();
		}
	}

	@Test
	@DisplayName("stop() ends a feed whose child ignores SIGTERM")
	void stopEscalates() throws Exception {
		assumeTrue(new File(STUBBORN[0]).canExecute(), "needs /bin/bash");
		StubbornFeed feed = new StubbornFeed();
		feed.start();
		Process child = null;
		try {
			for (int i = 0; i < 100 && (child = feed.process) == null; i++) {
				Thread.sleep(50);
			}
			assertTrue(child != null, "the feed never spawned its child");
			Thread.sleep(300); // let bash install the trap
			feed.stop();
			assertTrue(diesWithin(child, ProcessReaper.GRACE_MILLIS + 3000), "the child outlived stop()");
		} finally {
			if (child != null) {
				child.destroyForcibly();
			}
		}
	}

	@Test
	@DisplayName("the stall watchdog kills a stubborn child and stays armed until it is gone")
	void watchdogEscalatesAndStaysArmed() throws Exception {
		Process p = stubborn();
		try {
			Thread.sleep(300); // let bash install the trap
			StubbornFeed feed = new StubbornFeed();
			feed.running = true;
			feed.process = p;
			long stalledSince = System.nanoTime() - TimeUnit.SECONDS.toNanos(61);
			feed.lastIngestFrameNanos = stalledSince;
			new FeedDeliveryLoop(feed).checkIngestStall();
			assertNotEquals(0L, feed.lastIngestFrameNanos, "the watchdog disarmed itself");
			assertTrue(diesWithin(p, ProcessReaper.GRACE_MILLIS + 3000), "the stalled child was never killed");
		} finally {
			p.destroyForcibly();
		}
	}

	/** A feed whose "ffmpeg" ignores SIGTERM and never writes a frame. */
	private static final class StubbornFeed extends AbstractFfmpegFeed {
		StubbornFeed() {
			super("test:stubborn", STUBBORN[0], 600, 150, 150, 5, true, 50, 0, "rebuffer", 0.0);
		}

		@Override
		protected String[] buildCommand() {
			return STUBBORN.clone();
		}

		@Override
		protected String startLogDetail() {
			return "stubborn test child";
		}
	}
}
