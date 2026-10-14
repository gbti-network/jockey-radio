package fm.gbti.jockey.session;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Ends a child process without waiting for it: SIGTERM now, SIGKILL after a grace period if
 * it is still alive. For callers that must not block, the server thread in
 * {@link AbstractFfmpegFeed#stop()} and the real-time delivery thread's stall watchdog.
 *
 * <p>A single SIGTERM is not enough. ffmpeg acts on its first SIGTERM only at its next
 * main-loop check, which a read blocked on a dead connection never reaches, and nothing else
 * would ever kill it: the ingest thread that normally escalates is parked on the same
 * blocked pipe read.</p>
 */
final class ProcessReaper {

	/** How long a terminated child gets to exit on its own before it is killed. */
	static final long GRACE_MILLIS = 2000L;

	private ProcessReaper() {
	}

	/** SIGTERM now, SIGKILL after {@link #GRACE_MILLIS} if still alive. Never blocks. */
	static void terminate(Process p) {
		terminate(p, GRACE_MILLIS);
	}

	/** {@link #terminate(Process)} with a given grace, for tests. */
	static void terminate(Process p, long graceMillis) {
		if (p == null) {
			return;
		}
		p.destroy();
		CompletableFuture.delayedExecutor(graceMillis, TimeUnit.MILLISECONDS).execute(() -> {
			if (p.isAlive()) {
				p.destroyForcibly();
			}
		});
	}
}
