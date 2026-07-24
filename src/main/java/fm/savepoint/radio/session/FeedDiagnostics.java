package fm.savepoint.radio.session;

import fm.savepoint.radio.SavePointRadioMod;
import fm.savepoint.radio.config.RadioConfig;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Optional, off-by-default per-feed telemetry dump for offline broadcast-quality
 * analysis. When {@link RadioConfig#feedDiagnostics} is on, every feed appends
 * one CSV row per {@link RadioConfig#feedDiagnosticsIntervalSeconds} to a single
 * file, capturing a live snapshot (buffer occupancy, playout/connection state,
 * WSOLA stretch) plus per-interval DELTAS of the health counters (delivered,
 * silence, underruns, drops, re-anchors, reconnects, ingest).
 *
 * <p>The point is post-hoc correlation: after a test, find rows where
 * {@code silence_d > 0} or {@code deliver_rt} dips below ~1.0 - those are the
 * audible hitches - and read the buffer trend and cause counters around them to
 * see whether it was an underrun (buffer drained), an overflow trim, a reconnect
 * gap, or a re-anchor. {@code ingest_rt} exposes the source clock (the measured
 * ~0.974x real-time deficit); {@code source_rate} is the servo's inferred source
 * content-rate.
 *
 * <p>Writes are handed to a single daemon IO thread so no file I/O ever runs on
 * a feed's delivery (pacing) thread - a periodic drvfs fsync stall must not
 * become playout jitter. The file is TRUNCATED on init so each server run starts
 * clean. Fully fail-safe: any IO problem disables the dump rather than
 * disturbing playback.
 */
public final class FeedDiagnostics {

	/** Column order; must match the row built in {@code AbstractFfmpegFeed}. */
	public static final String CSV_HEADER =
			"epoch_ms,iso_time,station,feed,playout,conn,playing,"
			+ "buffered_frames,buffered_ms,target_ms,max_ms,fill_pct,"
			+ "delivered_d,silence_d,underruns_d,drops_d,overflow_d,reanchors_d,reconnects_d,ingest_d,"
			+ "deliver_rt,ingest_rt,stretch,source_rate,freeze_ms";

	private static final String DEFAULT_FILE = "diagnostics/feed-diagnostics.csv";
	private static final double MIN_INTERVAL_SECONDS = 1.0;
	private static final double MAX_INTERVAL_SECONDS = 60.0;

	/** Wall-clock time-of-day per row, so a tester's "heard a hitch at HH:MM:SS" lines up. */
	public static final DateTimeFormatter TIME_FORMAT =
			DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

	/** null == diagnostics disabled. */
	private static volatile FeedDiagnostics instance;

	private final Path file;
	private final long intervalNanos;
	private final BufferedWriter writer;
	private final ExecutorService io;

	private FeedDiagnostics(Path file, long intervalNanos, BufferedWriter writer, ExecutorService io) {
		this.file = file;
		this.intervalNanos = intervalNanos;
		this.writer = writer;
		this.io = io;
	}

	/** The active dump, or {@code null} when diagnostics are off. */
	public static FeedDiagnostics get() {
		return instance;
	}

	/** Sample interval in nanoseconds (effective granularity is ~1s - see class doc). */
	public long intervalNanos() {
		return intervalNanos;
	}

	/**
	 * (Re)initializes diagnostics from config. Closes any prior dump first, then -
	 * only if enabled - opens a fresh (truncated) file, writes a run header and the
	 * CSV column header, and starts the IO thread. Never throws.
	 */
	public static synchronized void init(RadioConfig config) {
		shutdown();
		if (config == null || !config.feedDiagnostics) {
			return;
		}
		try {
			double sec = config.feedDiagnosticsIntervalSeconds;
			if (!(sec >= MIN_INTERVAL_SECONDS)) {
				sec = MIN_INTERVAL_SECONDS;
			} else if (sec > MAX_INTERVAL_SECONDS) {
				sec = MAX_INTERVAL_SECONDS;
			}
			long intervalNanos = (long) (sec * 1_000_000_000.0);

			String rel = config.feedDiagnosticsFile;
			if (rel == null || rel.isBlank()) {
				rel = DEFAULT_FILE;
			}
			Path file = Paths.get(rel).toAbsolutePath();
			if (file.getParent() != null) {
				Files.createDirectories(file.getParent());
			}
			// TRUNCATE on init: one clean file per server run.
			BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
			writer.write("# SavePoint Radio feed diagnostics - run start "
					+ TIME_FORMAT.format(Instant.now()) + " - interval " + sec + "s");
			writer.newLine();
			writer.write(CSV_HEADER);
			writer.newLine();
			writer.flush();

			ExecutorService io = Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "savepoint-feed-diag");
				t.setDaemon(true);
				return t;
			});
			instance = new FeedDiagnostics(file, intervalNanos, writer, io);
			SavePointRadioMod.LOGGER.info(
					"[radio] feed diagnostics ON -> {} (interval {}s)", file, sec);
		} catch (Throwable t) {
			SavePointRadioMod.LOGGER.warn(
					"[radio] feed diagnostics failed to initialize; staying disabled", t);
			instance = null;
		}
	}

	/**
	 * Queues one CSV row for the IO thread. Cheap and non-blocking on the caller
	 * (the delivery thread); a full/late write can never stall playout.
	 */
	public void record(String row) {
		try {
			io.submit(() -> {
				try {
					writer.write(row);
					writer.newLine();
					writer.flush();
				} catch (IOException ignored) {
					// Disk full / closed: drop the row silently, never disturb playback.
				}
			});
		} catch (RejectedExecutionException ignored) {
			// Shutting down: drop the row.
		}
	}

	/** Flushes and closes the dump (if any). Idempotent; never throws. */
	public static synchronized void shutdown() {
		FeedDiagnostics inst = instance;
		instance = null;
		if (inst == null) {
			return;
		}
		inst.io.shutdown();
		try {
			inst.io.awaitTermination(2, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		try {
			inst.writer.flush();
			inst.writer.close();
		} catch (IOException ignored) {
			// best-effort close
		}
		SavePointRadioMod.LOGGER.info("[radio] feed diagnostics closed ({})", inst.file);
	}
}
