package fm.gbti.jockey.session;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The counters, rolling windows and occupancy ring behind a feed's stats and diag lines. The
 * ingest and delivery threads write them and any thread reads them, so every field is an
 * atomic, a self-synchronized window or guarded by {@link #occupancyLock}.
 */
final class FeedStats {

	/** Rolling drop-rate window (seconds) for the sow-005 micro-glitch metric. */
	static final int DROP_WINDOW_SECONDS = 60;
	/** Buffer-occupancy ring depth: one sample/second over the last ~minute. */
	private static final int OCCUPANCY_WINDOW_SAMPLES = 60;

	final AtomicLong framesRead = new AtomicLong();
	final AtomicLong framesDelivered = new AtomicLong();
	final AtomicLong silenceFrames = new AtomicLong();
	final AtomicLong drops = new AtomicLong();
	/**
	 * Overflow-only drop total: frames shed by the hard-cap drop-oldest bound in
	 * {@link FeedIngestLoop#offerFrame} (an upstream flood re-sending seconds of audio), NOT the
	 * hysteresis catch-up or the stall resync. The "feed drops high / raise
	 * maxBufferSeconds" WARN gates on THIS delta so a bounded latency correction -
	 * which the {@code maxBufferSeconds} knob does not address - never misfires
	 * that remediation advice (sow-005 Phase 1).
	 */
	final AtomicLong overflowDrops = new AtomicLong();
	final AtomicLong reconnects = new AtomicLong();
	/**
	 * ffmpeg respawns that REUSED the retained jitter buffer (sow-005 Phase 4,
	 * D-061): the buffer still held at least the pre-roll cushion when the process
	 * ended, so playout bridged the reconnect gap with no re-preroll blip. A subset
	 * of {@code reconnects}; the remainder either flushed (knob off) or were already
	 * below the cushion (cold start / long outage, re-prerolled naturally).
	 */
	final AtomicLong bufferPreservedReconnects = new AtomicLong();
	/**
	 * Controlled sustained-drain re-anchors (sow-005 Phase 4): one per occurrence
	 * that the buffer stayed below the low watermark long enough to trigger a single
	 * clean playout pause rebuilding the target cushion, instead of a cluster of
	 * ragged micro-underruns. Also counted as one glitch event each.
	 */
	final AtomicLong reanchors = new AtomicLong();
	/** Playing -> silence transitions (one per underrun, not per silence frame). */
	final AtomicLong underruns = new AtomicLong();
	/**
	 * Host/VM freeze events (see the delivery-loop detector): times the OS/hypervisor
	 * suspended this process so the monotonic clock fell &gt;= {@link FeedDeliveryLoop#HOST_FREEZE_THRESHOLD_MS}
	 * behind the wall clock. An ENVIRONMENT metric (e.g. a WSL2 host freezing the guest),
	 * not a mod fault; ~0 on healthy hardware. {@code hostFreezeMillis} totals the frozen time.
	 */
	final AtomicLong hostFreezeEvents = new AtomicLong();
	final AtomicLong hostFreezeMillis = new AtomicLong();
	/**
	 * Audible-discontinuity EVENT total: one increment per occurrence - each
	 * hysteresis catch-up, each stall resync, each underrun - regardless of how
	 * many frames it shed. This is the sow-005 proof metric: a single bounded
	 * catch-up sheds up to ~{@code targetFrames} frames but is ONE glitch, so
	 * counting events (not frames) keeps steady state at a true 0 and stops one
	 * rare correction from reading like the old ~120/min continuous nibble.
	 */
	final AtomicLong glitchEvents = new AtomicLong();

	/**
	 * Rolling per-second window of dropped FRAMES (all causes) for sow-005 drop /
	 * bandwidth accounting; {@code dropWindow.sum()} totals the last
	 * {@link #DROP_WINDOW_SECONDS}.
	 */
	final RollingWindow dropWindow = new RollingWindow(DROP_WINDOW_SECONDS);
	/**
	 * Rolling per-second window of glitch EVENTS (catch-up / resync / underrun
	 * occurrences) for the sow-005 micro-glitch proof metric; reads a true 0 in
	 * steady state and 1 per rare bounded correction (never the shed frame count).
	 */
	final RollingWindow glitchEventWindow = new RollingWindow(DROP_WINDOW_SECONDS);

	/**
	 * Small ring of recent buffered-frame occupancy (one sample/second) for the
	 * {@code /jockey diag} min/max/avg note. Guarded by {@link #occupancyLock}.
	 */
	private final int[] occupancySamples = new int[OCCUPANCY_WINDOW_SAMPLES];
	private int occupancyCount;
	private int occupancyHead;
	private final Object occupancyLock = new Object();

	/** What produced a batch of dropped frames, so diagnostics can separate causes. */
	enum DropCause {
		/**
		 * Hard-cap overflow in {@link FeedIngestLoop#offerFrame} (an upstream flood re-sending
		 * seconds of audio). This is the ONLY cause the {@code maxBufferSeconds}
		 * knob bounds, so only this feeds the "raise maxBufferSeconds" WARN.
		 */
		OVERFLOW,
		/**
		 * A bounded latency correction - the hysteresis high-water catch-up or the
		 * delivery-stall resync. One audible discontinuity per occurrence: it may
		 * shed many frames but counts as a single glitch event. Raising
		 * {@code maxBufferSeconds} does not reduce these (if anything it lets latency
		 * creep further before the same correction), so they never feed that WARN.
		 */
		CATCH_UP
	}

	/**
	 * A fixed-width rolling per-second counter: {@link #add} bumps the current
	 * second's bucket, {@link #sum} totals the last {@code windowSeconds}. Each
	 * bucket stamps the second it holds, so a bucket a full window old is ignored
	 * on read without any sweeping. All access is synchronized on the instance, so
	 * the ingest/delivery writer threads and the any-thread diag reader are safe.
	 */
	static final class RollingWindow {
		private final int windowSeconds;
		private final long[] counts;
		private final long[] seconds;

		RollingWindow(int windowSeconds) {
			this.windowSeconds = windowSeconds;
			this.counts = new long[windowSeconds];
			this.seconds = new long[windowSeconds];
		}

		synchronized void add(long count) {
			long second = System.nanoTime() / 1_000_000_000L;
			int idx = (int) Math.floorMod(second, (long) windowSeconds);
			if (seconds[idx] != second) {
				seconds[idx] = second;
				counts[idx] = 0L;
			}
			counts[idx] += count;
		}

		synchronized long sum() {
			long second = System.nanoTime() / 1_000_000_000L;
			long cutoff = second - windowSeconds + 1;
			long total = 0L;
			for (int i = 0; i < windowSeconds; i++) {
				if (seconds[i] >= cutoff && seconds[i] <= second) {
					total += counts[i];
				}
			}
			return total;
		}
	}

	/**
	 * Records {@code count} dropped frames from {@code cause}: always bumps the
	 * grand {@code drops} total and the all-causes {@code dropWindow} (frame-level
	 * drop/bandwidth accounting). An {@link DropCause#OVERFLOW} batch additionally
	 * feeds the overflow-only counter the "raise maxBufferSeconds" WARN gates on; a
	 * {@link DropCause#CATCH_UP} batch instead records ONE glitch event (via
	 * {@link #recordGlitchEvent()}) no matter how many frames it shed, so a single
	 * bounded correction never reads as many micro-glitches. No-op for a
	 * non-positive count; must never be called while holding {@link AbstractFfmpegFeed#bufferLock}
	 * (it takes only the small window monitors, and nothing under those takes
	 * {@code bufferLock}).
	 */
	void recordDrops(long count, DropCause cause) {
		if (count <= 0) {
			return;
		}
		drops.addAndGet(count);
		dropWindow.add(count);
		if (cause == DropCause.OVERFLOW) {
			overflowDrops.addAndGet(count);
		} else {
			recordGlitchEvent();
		}
	}

	/**
	 * Records ONE audible-discontinuity event - a hysteresis catch-up, a stall
	 * resync, or an underrun: bumps the {@code glitchEvents} total and the rolling
	 * {@code glitchEventWindow}. Per occurrence, never per shed frame - this is the
	 * sow-005 proof metric that reads a true 0 in steady state. Takes only the
	 * window monitor, so it is safe to call outside (never inside) {@code bufferLock}.
	 */
	void recordGlitchEvent() {
		glitchEvents.incrementAndGet();
		glitchEventWindow.add(1L);
	}

	/** Appends one buffered-occupancy sample to the diag ring. */
	void recordOccupancy(int buffered) {
		synchronized (occupancyLock) {
			occupancySamples[occupancyHead] = buffered;
			occupancyHead = (occupancyHead + 1) % OCCUPANCY_WINDOW_SAMPLES;
			if (occupancyCount < OCCUPANCY_WINDOW_SAMPLES) {
				occupancyCount++;
			}
		}
	}

	/** {@code occBuffered(min/max/avg)} over the occupancy ring, or dashes when empty. */
	String occupancySummary() {
		int min = Integer.MAX_VALUE;
		int max = 0;
		long sum = 0L;
		int n;
		synchronized (occupancyLock) {
			n = occupancyCount;
			for (int i = 0; i < n; i++) {
				int v = occupancySamples[i];
				if (v < min) {
					min = v;
				}
				if (v > max) {
					max = v;
				}
				sum += v;
			}
		}
		if (n == 0) {
			return "occBuffered(min/max/avg)=-/-/-";
		}
		return "occBuffered(min/max/avg)=" + min + "/" + max + "/" + (sum / n);
	}
}
