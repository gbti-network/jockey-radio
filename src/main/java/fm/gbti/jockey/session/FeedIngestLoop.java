package fm.gbti.jockey.session;

import fm.gbti.jockey.JockeyRadioMod;
import fm.gbti.jockey.session.FeedStats.DropCause;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static fm.gbti.jockey.session.AbstractFfmpegFeed.*;

/**
 * The ingest side of an {@link AbstractFfmpegFeed}: spawn ffmpeg, read PCM frames into the
 * jitter buffer, drain stderr, and reconnect with backoff. Runs on one feed executor thread
 * (plus one stderr drainer per process); every piece of shared state stays on the feed.
 */
final class FeedIngestLoop {

	private static final int STDERR_TAIL_LINES = 40;

	/** Healthy-streaming streak that resets the reconnect backoff. */
	private static final long HEALTHY_STREAK_NANOS = TimeUnit.SECONDS.toNanos(30);

	private static final long PROCESS_STOP_GRACE_MILLIS = 2000L;
	/**
	 * Consecutive failed connections, with no healthy streak between them, after which a
	 * play-once disc is finished anyway, so a source that always breaks at the same point
	 * cannot retry forever.
	 */
	static final int MAX_PLAY_ONCE_FAILURES = 5;
	private static final long STDERR_DRAIN_JOIN_MILLIS = 1000L;

	private final AbstractFfmpegFeed feed;
	private final FeedStats stats;

	FeedIngestLoop(AbstractFfmpegFeed feed) {
		this.feed = feed;
		this.stats = feed.stats;
	}

	// ------------------------------------------------------------------
	// Ingest loop (feed executor thread)
	// ------------------------------------------------------------------

	void runIngestLoop() {
		long backoffSeconds = INITIAL_BACKOFF_SECONDS;
		long attempt = 0;
		int playOnceFailures = 0;
		while (feed.running && !Thread.currentThread().isInterrupted()) {
			feed.connectionState = "connecting";
			// Per-connection reset, so a value left by the PREVIOUS connection cannot make
			// the stall watchdog fire against a listener that has not streamed yet.
			feed.lastIngestFrameNanos = 0L;
			long firstFrameNanos = -1L;
			long lastFrameNanos = -1L;
			String reason;
			Process p = null;
			StderrDrain drain = null;
			int exitCode = -1;
			long framesBefore = stats.framesRead.get();
			try {
				p = spawnProcess();
				feed.process = p;
				drain = startStderrDrainer(p);
				feed.afterSpawn();
				long[] frameTimes = readFrames(p, drain);
				firstFrameNanos = frameTimes[0];
				lastFrameNanos = frameTimes[1];
				reason = classifyEnd(p);
			} catch (IOException e) {
				recordStderr("spawn failed: " + e.getMessage());
				reason = "exit";
			} finally {
				feed.process = null;
				if (p != null) {
					destroyProcess(p);
					exitCode = exitValue(p);
				}
			}
			if (!feed.running || Thread.currentThread().isInterrupted()) {
				break;
			}

			awaitStderrDrain(drain);

			// sow-012: a finite source (repeat off) that STREAMED to its end plays once and stops
			// instead of respawning/looping. Gated on firstFrameNanos >= 0 so a resolve/startup
			// failure (never streamed) still retries below rather than parking a never-played disc.
			// Only a CLEAN end finishes it: ffmpeg exited 0 and the input did not break off. A
			// dropped connection mid-track also exits 0, so without the stderr check every
			// network hiccup ended the disc early. The delivery loop drains the retained buffer
			// then goes silent; playoutState() -> "ended".
			if (feed.stopAtEndOfInput() && firstFrameNanos >= 0) {
				boolean clean = "exit".equals(reason) && exitCode == 0
						&& (drain == null || !drain.sawInputError.get());
				if (lastFrameNanos - firstFrameNanos >= HEALTHY_STREAK_NANOS) {
					playOnceFailures = 0;
				}
				if (!clean) {
					playOnceFailures++;
				}
				if (clean || playOnceFailures >= MAX_PLAY_ONCE_FAILURES) {
					if (!clean) {
						JockeyRadioMod.LOGGER.warn("[jockey] feed input failed {} times in a row station={}; "
								+ "ending the play-once disc", playOnceFailures, feed.stationKey);
					}
					feed.inputEnded = true;
					feed.connectionState = "ended";
					JockeyRadioMod.LOGGER.info("[jockey] feed end-of-input station={} (repeat off); playing once, stopping",
							feed.stationKey);
					break;
				}
				feed.onInputFailed(stats.framesRead.get() - framesBefore);
			}

			stats.reconnects.incrementAndGet();
			// sow-005 Phase 4 (D-061): decide the retained jitter buffer's fate for
			// this respawn - reuse it (default, no re-preroll blip) or flush it for a
			// clean re-preroll (legacy knob-off path). Delivery is untouched otherwise.
			handleReconnectBuffer();
			boolean healthyStreak = firstFrameNanos >= 0
					&& lastFrameNanos - firstFrameNanos >= HEALTHY_STREAK_NANOS;
			if (healthyStreak) {
				backoffSeconds = INITIAL_BACKOFF_SECONDS;
				attempt = 0;
			}
			attempt++;

			boolean idle = p != null && feed.classifyIdle(drain);
			// Remember this connection's classification so playoutState() can keep a
			// repeatedly-404 off-air station reading "idle" through the next "connecting"
			// probe instead of flashing "buffering" each ladder cycle (sow-005 Phase A
			// finding). A connection that streamed or failed hard clears it.
			feed.lastClassifiedIdle = idle;
			if (idle) {
				// A prompt-respawn feed (RTMP RECEIVE) pins idle respawns to the
				// short initial delay so a flapping publisher is never locked out
				// of the listen port; HLS keeps its escalating 404-idle backoff.
				if (feed.promptRespawnOnIdle()) {
					backoffSeconds = INITIAL_BACKOFF_SECONDS;
					attempt = 0;
				}
				feed.onIdle(backoffSeconds);
			} else {
				feed.connectionState = "waiting";
				JockeyRadioMod.LOGGER.warn("[jockey] feed reconnect station={} attempt={} reason={} lastStderr={}",
						feed.stationKey, attempt, reason, feed.lastStderrLine());
			}

			try {
				Thread.sleep(Math.max(0L, feed.reconnectDelayMillis(backoffSeconds, idle, attempt)));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
			backoffSeconds = Math.min(backoffSeconds * 2, feed.maxBackoffSeconds);
		}
	}

	private Process spawnProcess() throws IOException {
		String[] cmd = feed.buildCommand();
		// Snapshot for the diag arg-shape, so getDiagLine() never has to rebuild (and
		// possibly re-resolve) the command on a caller's thread.
		feed.lastCommand = cmd;
		return new ProcessBuilder(List.of(cmd)).start();
	}

	/**
	 * Reads full 1920-byte frames from ffmpeg stdout into the ring buffer until
	 * EOF, error, stop or rejection. Returns {@code [firstFrameNanos, lastFrameNanos]}
	 * ({@code -1} when no frame was admitted).
	 */
	private long[] readFrames(Process p, StderrDrain drain) {
		byte[] raw = new byte[FRAME_BYTES];
		long firstFrameNanos = -1L;
		long lastFrameNanos = -1L;
		InputStream in = p.getInputStream();
		try {
			while (feed.running && !Thread.currentThread().isInterrupted()) {
				int n = in.readNBytes(raw, 0, FRAME_BYTES);
				if (n < FRAME_BYTES) {
					break; // EOF (any partial trailing frame is discarded)
				}
				if (firstFrameNanos < 0 && !holdForAdmission()) {
					break;
				}
				if (drain.sawRejected.get()) {
					break; // refused by the stderr drainer; none of its audio is offered
				}
				short[] frame = new short[FRAME_SAMPLES];
				ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(frame);
				lastFrameNanos = System.nanoTime();
				// Published for the stall watchdog, which cannot see this method's local.
				feed.lastIngestFrameNanos = lastFrameNanos;
				if (firstFrameNanos < 0) {
					firstFrameNanos = lastFrameNanos;
					feed.connectionState = "streaming";
				}
				stats.framesRead.incrementAndGet();
				offerFrame(frame);
			}
		} catch (IOException e) {
			if (feed.running) {
				recordStderr("stdout read failed: " + e.getMessage());
			}
		}
		return new long[] {firstFrameNanos, lastFrameNanos};
	}

	/** Waits {@link AbstractFfmpegFeed#admissionDelayMillis()}; false when interrupted (stop). */
	private boolean holdForAdmission() {
		long delay = feed.admissionDelayMillis();
		if (delay <= 0L) {
			return true;
		}
		try {
			Thread.sleep(delay);
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/** After stdout ended: {@code exit} if the process is (about to be) dead, else {@code eof}. */
	private static String classifyEnd(Process p) {
		try {
			if (p.waitFor(1, TimeUnit.SECONDS)) {
				return "exit";
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return "eof";
	}

	/**
	 * Enqueue one frame, dropping the OLDEST on overflow at the hard
	 * {@code maxBufferFrames} cap. This bound only fires on a genuine flood (an
	 * upstream reconnect re-sending seconds of audio); steady-state latency is
	 * walked back to target by the delivery loop's gentle trim, not here.
	 */
	private void offerFrame(short[] frame) {
		long dropped = 0L;
		synchronized (feed.bufferLock) {
			while (feed.buffer.size() >= feed.maxBufferFrames) {
				feed.buffer.pollFirst();
				dropped++;
			}
			feed.buffer.addLast(frame);
		}
		stats.recordDrops(dropped, DropCause.OVERFLOW);
	}

	/**
	 * Buffer-preserving reconnect (sow-005 Phase 4, the dev's D-061 win). Runs on
	 * the ingest thread between a process ending and the next spawn.
	 *
	 * <p>With {@link AbstractFfmpegFeed#reconnectPreserveBuffer} on (default) the retained buffer is
	 * left INTACT: playout keeps draining the frames it already holds while the new
	 * ffmpeg process refills them, so a reconnect gap shorter than the buffered depth
	 * (e.g. SavePoint's ~2&nbsp;s RTMP publisher restart) is bridged with no flush and
	 * no re-preroll blip. Only when the buffer still holds at least the pre-roll
	 * cushion is this counted a {@code bufferPreservedReconnects}; below the cushion
	 * (cold start or a long outage) nothing is done here - the delivery loop
	 * re-prerolls naturally on its next underrun, exactly as before.</p>
	 *
	 * <p>The preserve default reproduces the pre-Phase-4 behaviour, where a respawn never
	 * cleared the buffer (the only flush was in {@link AbstractFfmpegFeed#stop()}). With the knob off the
	 * buffer is instead flushed under {@code bufferLock} so playout re-prerolls cleanly
	 * from empty on every respawn - a NEW opt-in flush-and-re-preroll mode, not a
	 * restoration of prior behaviour. Either way this only touches the buffer under its
	 * lock and never blocks - the delivery and server threads are unaffected.</p>
	 */
	private void handleReconnectBuffer() {
		if (feed.reconnectPreserveBuffer) {
			int buffered;
			synchronized (feed.bufferLock) {
				buffered = feed.buffer.size();
			}
			if (buffered >= feed.prerollFrames) {
				stats.bufferPreservedReconnects.incrementAndGet();
			}
			// else: below the cushion - leave the buffer intact and let the delivery
			// loop re-preroll on its next underrun (cold start / long outage).
			return;
		}
		// Opt-in flush mode (knob off): clear so playout re-prerolls from empty on every
		// respawn. This is NEW behaviour - the pre-Phase-4 code never flushed on respawn.
		synchronized (feed.bufferLock) {
			feed.buffer.clear();
		}
	}

	// ------------------------------------------------------------------
	// Stderr drainer (feed executor thread, one per process)
	// ------------------------------------------------------------------

	private StderrDrain startStderrDrainer(Process p) {
		StderrDrain drain = new StderrDrain();
		Runnable drainer = () -> {
			try {
				drainStderr(p, drain);
			} finally {
				drain.done.countDown();
			}
		};
		ExecutorService executor = FeedRegistry.get().feedExecutor();
		if (executor != null) {
			try {
				executor.submit(drainer);
				return drain;
			} catch (RejectedExecutionException ignored) {
				// fall through to the plain daemon thread
			}
		}
		// Registry already shutting down - stderr must STILL be drained or the
		// pipe fills and ffmpeg wedges until our destroy. Cheap daemon thread.
		Thread thread = new Thread(drainer, "jockey-radio-stderr-" + feed.stationKey);
		thread.setDaemon(true);
		thread.start();
		return drain;
	}

	private void drainStderr(Process p, StderrDrain drain) {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				if (feed.isIdleStderr(line)) {
					drain.sawIdle.set(true);
				}
				if (feed.isFatalStderr(line)) {
					drain.sawFatal.set(true);
				}
				if (isInputError(line)) {
					drain.sawInputError.set(true);
				}
				if (feed.isRejectStderr(line) && drain.sawRejected.compareAndSet(false, true)) {
					JockeyRadioMod.LOGGER.warn("[jockey] feed rejected a connection station={}: {}",
							feed.stationKey, feed.sanitize(line));
					// Non-blocking: the ingest thread's finally still runs the graceful destroy.
					p.destroyForcibly();
				}
				recordStderr(line);
			}
		} catch (IOException ignored) {
			// Process destroyed under us - expected on stop/reconnect.
		}
	}

	/**
	 * ffmpeg's lines for an input that broke off before its end: a read error is reported as
	 * "Error during demuxing", and an HTTP body shorter than its Content-Length as "Stream ends
	 * prematurely". ffmpeg 9.0.2 prints them and still exits 0.
	 */
	static boolean isInputError(String line) {
		return line.contains("Error during demuxing") || line.contains("Stream ends prematurely");
	}

	/** The exit status of a process that has been destroyed, or -1 if it is still running. */
	private static int exitValue(Process p) {
		try {
			return p.exitValue();
		} catch (IllegalThreadStateException stillRunning) {
			return -1;
		}
	}

	/** Bounded join on the connection's stderr drainer; safe to call with null. */
	private static void awaitStderrDrain(StderrDrain drain) {
		if (drain == null) {
			return;
		}
		try {
			drain.done.await(STDERR_DRAIN_JOIN_MILLIS, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void recordStderr(String line) {
		line = feed.sanitize(line);
		synchronized (feed.stderrTail) {
			while (feed.stderrTail.size() >= STDERR_TAIL_LINES) {
				feed.stderrTail.pollFirst();
			}
			feed.stderrTail.addLast(line);
		}
	}

	// ------------------------------------------------------------------
	// Process teardown
	// ------------------------------------------------------------------

	/**
	 * destroy, 2&nbsp;s grace, then destroyForcibly. Safe on an already-dead
	 * process. Only ever called from the ingest loop's {@code finally} block (a
	 * feed executor thread) - never from {@link AbstractFfmpegFeed#stop()}, whose caller is
	 * frequently the server thread and must not wait on the grace period.
	 */
	private static void destroyProcess(Process p) {
		p.destroy();
		try {
			if (!p.waitFor(PROCESS_STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
				p.destroyForcibly();
			}
		} catch (InterruptedException e) {
			p.destroyForcibly();
			Thread.currentThread().interrupt();
		}
	}
}
