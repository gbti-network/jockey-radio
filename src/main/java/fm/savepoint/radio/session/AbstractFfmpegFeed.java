package fm.savepoint.radio.session;

import fm.savepoint.radio.SavePointRadioMod;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * All the shared ffmpeg-subprocess machinery, generalized out of sow-001's
 * single-station feed so both {@link HlsPullFeed} and {@link RtmpReceiveFeed}
 * ride the same PCM pipe: spawn ffmpeg from a subclass-supplied command,
 * decode 48&nbsp;kHz s16le mono to 960-sample (20&nbsp;ms) frames, and feed a
 * target-latency JITTER BUFFER that smooths an imperfect upstream (segment/
 * reconnect bursts + brief gaps) while keeping the live edge: pre-roll to
 * {@code prerollFrames} before playout, drain one frame per 20&nbsp;ms to every
 * {@link AudioSink} (silence + re-roll on underrun), hysteresis-trim a
 * burst-inflated backlog back to {@code targetFrames} only once it creeps to
 * high-water, drop-oldest overflow at {@code maxBufferFrames}, nanoTime pacing with drift/stall
 * correction, exponential (or subclass-tuned) reconnect backoff, 30&nbsp;s
 * stats, secret-redacting stderr drain, and an idempotent server-thread-safe
 * {@link #stop()}.
 *
 * <p>Subclasses supply only what differs: the command line
 * ({@link #buildCommand()}), the "feed start" detail suffix
 * ({@link #startLogDetail()}), stderr redaction ({@link #sanitize(String)}),
 * and idle classification / logging ({@link #isIdleStderr(String)},
 * {@link #classifyIdle(StderrDrain)}, {@link #onIdle(long)},
 * {@link #afterSpawn()}). Everything else lives here, once.</p>
 *
 * <p>Three pieces of work run on {@link FeedRegistry#feedExecutor()} daemon
 * threads, never the server thread: the ingest loop (spawn, read frames,
 * reconnect), one stderr drainer per process, and the delivery loop. See
 * sow-001's design notes; the threading contract is unchanged.</p>
 */
public abstract class AbstractFfmpegFeed implements StationFeed {

	protected static final int FRAME_SAMPLES = 960;
	private static final int FRAME_BYTES = FRAME_SAMPLES * 2;
	private static final long FRAME_NANOS = 20_000_000L;
	private static final long FRAME_MILLIS = 20L;
	/** Behind by more than this after a stall: resync to now instead of bursting. */
	private static final long MAX_CATCHUP_LAG_NANOS = 200_000_000L;
	private static final int STDERR_TAIL_LINES = 40;
	private static final long STATS_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);
	/**
	 * How long a live-but-silent ffmpeg is tolerated before {@link #checkIngestStall()}
	 * restarts the connection. Comfortably above the per-feed {@code -rw_timeout} so the
	 * socket-level guard fires first and this stays a genuine backstop.
	 */
	private static final long INGEST_STALL_NANOS = TimeUnit.SECONDS.toNanos(60);
	/** Wall-clock-minus-monotonic gap (ms) at/above which a delivery-loop tick counts as a host/VM freeze. */
	private static final long HOST_FREEZE_THRESHOLD_MS = 100L;
	/** Healthy-streaming streak that resets the reconnect backoff. */
	private static final long HEALTHY_STREAK_NANOS = TimeUnit.SECONDS.toNanos(30);
	private static final long INITIAL_BACKOFF_SECONDS = 2L;
	/**
	 * Click-free resume ramp length in frames (sow-005 Phase A, item 4). Every
	 * playout resume from a preroll / re-anchor / reconnect (the single
	 * {@code playing} false-&gt;true transition) linearly ramps the s16 samples of the
	 * first {@code RESUME_RAMP_FRAMES} delivered frames from 0 to full gain so the
	 * resume carries no click. 2 frames = ~40&nbsp;ms at {@link #FRAME_NANOS}, inside
	 * the 20-50&nbsp;ms window. Applied ONLY on the resume transition, never in steady
	 * state; the ramp allocates a fresh frame (so a shared/dequeued frame is never
	 * mutated) only on those 1-2 frames per resume.
	 *
	 * <p>By design only the RESUME edge (playing false-&gt;true) is ramped; the pause
	 * ENTRY (playing true-&gt;false on a re-anchor or underrun) is NOT faded out. The
	 * spec (Phase A, item 4) scopes the click-free treatment to the resume, where
	 * pre-roll already makes recovery silent and the ramp makes it click-free. A
	 * symmetric fade-out is deliberately omitted: the underrun path has no lookahead
	 * (the last full real frame was already delivered a tick earlier, then silence
	 * follows with nothing left to fade), so only the resume half can be ramped
	 * consistently. WSOLA (Phase B) will smooth the pause edge; until then the pause
	 * entry may carry a small click if the last sample sits far from a zero crossing
	 * (a pre-existing Phase-4 property, not a Phase-A regression).</p>
	 */
	private static final int RESUME_RAMP_FRAMES = 2;
	private static final long PROCESS_STOP_GRACE_MILLIS = 2000L;
	private static final long STDERR_DRAIN_JOIN_MILLIS = 1000L;
	private static final long DROPS_WARN_PER_STATS_INTERVAL = 250L;
	/**
	 * Hysteresis high-water as a multiple of {@code targetFrames}. The live-edge
	 * trim ENGAGES only once the buffer has genuinely crept to this multiple of
	 * target (a wide deadband, ~2x); it then drains the excess straight back to
	 * {@code targetFrames} in one bounded catch-up and DISENGAGES, and cannot
	 * trim again until the buffer climbs back to high-water. Anywhere below
	 * high-water (ordinary jitter around target) ZERO frames are dropped, so
	 * steady-state playout produces no 20&nbsp;ms micro-glitches - only a real
	 * latency creep to ~2x target ever costs a single skip (sow-005 Phase 1).
	 */
	private static final float HYSTERESIS_HIGH_MULTIPLIER = 2.0f;
	/** Rolling drop-rate window (seconds) for the sow-005 micro-glitch metric. */
	private static final int DROP_WINDOW_SECONDS = 60;
	/** Buffer-occupancy ring depth: one sample/second over the last ~minute. */
	private static final int OCCUPANCY_WINDOW_SAMPLES = 60;

	private final String stationKey;
	protected final String ffmpegBinary;
	/** Hard cap on buffered frames (drop-oldest overflow bound). */
	private final int maxBufferFrames;
	/** Steady-state playout depth the live-edge trim walks the buffer back toward. */
	private final int targetFrames;
	/** Fill required before (re)starting playout after a start or an underrun. */
	private final int prerollFrames;
	/** Hysteresis engage point: {@code round(HYSTERESIS_HIGH_MULTIPLIER * targetFrames)}. */
	private final int highWaterFrames;
	/**
	 * When true (default, sow-005 Phase 4, D-061) an ffmpeg respawn REUSES the retained
	 * jitter buffer instead of flushing + re-prerolling from empty: playout keeps
	 * draining the retained frames while the new process refills them, so a gap
	 * shorter than the buffered depth is covered silently with no per-reconnect blip.
	 * This default MATCHES the pre-Phase-4 behaviour, where the buffer was never cleared
	 * on a respawn (the only {@code buffer.clear()} was in {@link #stop()}). When false,
	 * every respawn flushes the buffer - a NEW optional flush-and-re-preroll mode
	 * (forcing a clean re-preroll per respawn), NOT a restoration of any prior behaviour.
	 */
	private final boolean reconnectPreserveBuffer;
	/**
	 * Buffered depth (frames) below which the sustained-drain re-anchor arms
	 * (sow-005 Phase 4); forced strictly below BOTH {@code targetFrames} (so ordinary
	 * jitter around target never arms it) and {@code prerollFrames} (so the re-anchor,
	 * which rebuilds the cushion to {@code prerollFrames}, always lands above its own
	 * arming threshold and fires once per drain episode, never once per sustain window).
	 */
	private final int reanchorLowWaterFrames;
	/**
	 * Consecutive below-low-water delivery ticks (20&nbsp;ms each) required before
	 * ONE controlled re-anchor fires, or {@code 0} to disable the re-anchor entirely
	 * (sow-005 Phase 4).
	 */
	private final int reanchorSustainFrames;
	private final long maxBackoffSeconds;

	// --- sow-005 Phase B: pitch-preserving time-stretch (TEMPO_MATCH) ---
	/** Normalized smoothing mode ({@code "tempo_match"} or {@code "rebuffer"}). */
	private final String smoothingMode;
	/** Hard clamp on the stretch factor's magnitude ({@code factor in 1 +/- this}). */
	private final double maxStretchFraction;
	/**
	 * Whether the WSOLA time-stretch stage is active: {@code "tempo_match"} mode with
	 * a positive stretch budget. When false the delivery loop runs the exact Phase-A
	 * passthrough (safe rollback, zero residual effect).
	 */
	private final boolean tempoMatchEnabled;
	/**
	 * The live stretch factor ({@code output/input}) the occupancy servo last set,
	 * published for {@code /jukeboxdisc diag}. {@code 1.0} means no stretch. Written once
	 * per servo update by the delivery thread; read on any thread.
	 */
	private volatile double currentStretchFactor = 1.0;
	/**
	 * Set true if the time-stretch stage ever throws at runtime: the delivery loop then
	 * permanently falls back to the Phase-A passthrough so audio never breaks (logged
	 * once). Diagnostics only; the authoritative flag is a delivery-thread local.
	 */
	private volatile boolean stretchRuntimeFailed;
	/**
	 * Samples the WSOLA stage currently holds ahead of its analysis cursor - the DSP
	 * ring depth that sits BEHIND the jitter buffer and adds a small fixed latency the
	 * jitter-buffer {@code latencyMs} does not include. Published each PLAYING tick in
	 * TEMPO_MATCH for the {@code /jukeboxdisc diag} ({@code dspRingMs}); stays 0 in
	 * REBUFFER. Delivery-thread write, any-thread read (diagnostic only).
	 */
	private volatile int stretchRingSamples;

	/** Frames per wall-second at 50 fps (20 ms frames); the servo error/gain scale. */
	private static final double FRAMES_PER_SECOND = 1_000_000_000.0 / FRAME_NANOS;
	/**
	 * Occupancy-servo proportional gain (per second of buffer error). Sized with
	 * {@link #STRETCH_KI} for a critically-ish damped loop (damping ratio ~0.7) whose
	 * natural period is ~13&nbsp;min, so the factor adapts over minutes and never
	 * fast-oscillates (the dev's explicit steer). Provides the damping term.
	 */
	private static final double STRETCH_KP = 0.010;
	/**
	 * Occupancy-servo integral gain (per second of buffer error, per ~1&nbsp;s update).
	 * The integrator nulls the steady-state error so occupancy converges to the target
	 * and the factor converges to exactly the source's inverse content-rate (~1.026 for
	 * the SavePoint trunk) with no open-loop rate math. Deliberately tiny (minutes).
	 */
	private static final double STRETCH_KI = 0.00006;
	/**
	 * Initial servo integrator value (added to 1.0). Seeded near the SavePoint trunk's
	 * known ~2.6% deficit so a cold start converges fast with minimal warm-up drain; the
	 * servo self-corrects to any actual source rate (including a real-time RTMP push,
	 * where it settles to ~1.0). Clamped into the stretch budget at runtime.
	 */
	private static final double STRETCH_INITIAL_INTEGRAL = 0.020;
	/**
	 * EMA smoothing coefficient for the occupancy error that feeds the PROPORTIONAL
	 * term (~10&nbsp;s time constant at the 1&nbsp;Hz servo update). A bursty HLS fill or
	 * post-reconnect catch-up can swing per-second occupancy by hundreds of frames; on
	 * the raw error {@code STRETCH_KP} would then saturate the &plusmn;budget and step
	 * the factor across its whole range at 1&nbsp;Hz - an audible tempo staircase. The
	 * integral path stays on the RAW error, so steady-state convergence is unchanged.
	 */
	private static final double STRETCH_ERR_EMA_ALPHA = 0.10;
	/**
	 * Max change in the PUBLISHED stretch factor per servo update (~0.05%/s), so the
	 * tempo only ever moves on the intended minutes timescale even through a large
	 * occupancy transient. Each step is still a fresh WSOLA re-search, hence click-free;
	 * this bounds the audible tempo-modulation RATE (wow/flutter), not click safety.
	 */
	private static final double STRETCH_MAX_SLEW = 0.0005;

	private final List<AudioSink> sinks = new CopyOnWriteArrayList<>();

	/** Ring buffer of decoded frames, guarded by {@code bufferLock}. */
	private final ArrayDeque<short[]> buffer = new ArrayDeque<>();
	private final Object bufferLock = new Object();

	/** Bounded tail of recent (sanitized) ffmpeg stderr lines, guarded by itself. */
	private final ArrayDeque<String> stderrTail = new ArrayDeque<>();

	/**
	 * Any URL embedded in free text (an ffmpeg stderr line, the diag arg-shape). Stops
	 * at whitespace and at the quote/bracket characters that delimit a URL in those two
	 * contexts, so the surrounding text survives the mask.
	 */
	private static final Pattern URL_IN_TEXT = Pattern.compile("[a-zA-Z][a-zA-Z0-9+.\\-]*://[^\\s\"'\\]]+");

	/** Endpoint redaction, registered by {@link #redactEndpoint} (null until then). */
	private volatile String redactEffectiveUrl;
	private volatile String redactDisplayUrl;
	private volatile String redactSecret;

	/**
	 * The argv of the LAST spawn, for the diag arg-shape. Never rebuilt on demand:
	 * {@code buildCommand()} is an ingest-thread-only contract (LinkResolvedFeed's
	 * implementation runs a whole yt-dlp resolve), so calling it from a command handler
	 * froze the server tick loop. Reporting what actually ran is also more accurate.
	 */
	private volatile String[] lastCommand;

	/**
	 * {@code System.nanoTime()} of the most recent frame read from ffmpeg, or {@code 0}
	 * when this connection has not produced one yet. Read by {@link #checkIngestStall()}
	 * on the delivery thread; written by the ingest thread.
	 */
	private volatile long lastIngestFrameNanos;

	private final AtomicLong framesRead = new AtomicLong();
	private final AtomicLong framesDelivered = new AtomicLong();
	private final AtomicLong silenceFrames = new AtomicLong();
	private final AtomicLong drops = new AtomicLong();
	/**
	 * Overflow-only drop total: frames shed by the hard-cap drop-oldest bound in
	 * {@link #offerFrame} (an upstream flood re-sending seconds of audio), NOT the
	 * hysteresis catch-up or the stall resync. The "feed drops high / raise
	 * maxBufferSeconds" WARN gates on THIS delta so a bounded latency correction -
	 * which the {@code maxBufferSeconds} knob does not address - never misfires
	 * that remediation advice (sow-005 Phase 1).
	 */
	private final AtomicLong overflowDrops = new AtomicLong();
	private final AtomicLong reconnects = new AtomicLong();
	/**
	 * ffmpeg respawns that REUSED the retained jitter buffer (sow-005 Phase 4,
	 * D-061): the buffer still held at least the pre-roll cushion when the process
	 * ended, so playout bridged the reconnect gap with no re-preroll blip. A subset
	 * of {@code reconnects}; the remainder either flushed (knob off) or were already
	 * below the cushion (cold start / long outage, re-prerolled naturally).
	 */
	private final AtomicLong bufferPreservedReconnects = new AtomicLong();
	/**
	 * Controlled sustained-drain re-anchors (sow-005 Phase 4): one per occurrence
	 * that the buffer stayed below the low watermark long enough to trigger a single
	 * clean playout pause rebuilding the target cushion, instead of a cluster of
	 * ragged micro-underruns. Also counted as one glitch event each.
	 */
	private final AtomicLong reanchors = new AtomicLong();
	/** Playing -> silence transitions (one per underrun, not per silence frame). */
	private final AtomicLong underruns = new AtomicLong();
	/**
	 * Host/VM freeze events (see the delivery-loop detector): times the OS/hypervisor
	 * suspended this process so the monotonic clock fell &gt;= {@link #HOST_FREEZE_THRESHOLD_MS}
	 * behind the wall clock. An ENVIRONMENT metric (e.g. a WSL2 host freezing the guest),
	 * not a mod fault; ~0 on healthy hardware. {@code hostFreezeMillis} totals the frozen time.
	 */
	private final AtomicLong hostFreezeEvents = new AtomicLong();
	private final AtomicLong hostFreezeMillis = new AtomicLong();
	/**
	 * Audible-discontinuity EVENT total: one increment per occurrence - each
	 * hysteresis catch-up, each stall resync, each underrun - regardless of how
	 * many frames it shed. This is the sow-005 proof metric: a single bounded
	 * catch-up sheds up to ~{@code targetFrames} frames but is ONE glitch, so
	 * counting events (not frames) keeps steady state at a true 0 and stops one
	 * rare correction from reading like the old ~120/min continuous nibble.
	 */
	private final AtomicLong glitchEvents = new AtomicLong();

	/**
	 * Rolling per-second window of dropped FRAMES (all causes) for sow-005 drop /
	 * bandwidth accounting; {@code dropWindow.sum()} totals the last
	 * {@link #DROP_WINDOW_SECONDS}.
	 */
	private final RollingWindow dropWindow = new RollingWindow(DROP_WINDOW_SECONDS);
	/**
	 * Rolling per-second window of glitch EVENTS (catch-up / resync / underrun
	 * occurrences) for the sow-005 micro-glitch proof metric; reads a true 0 in
	 * steady state and 1 per rare bounded correction (never the shed frame count).
	 */
	private final RollingWindow glitchEventWindow = new RollingWindow(DROP_WINDOW_SECONDS);

	/**
	 * Small ring of recent buffered-frame occupancy (one sample/second) for the
	 * {@code /jukeboxdisc diag} min/max/avg note. Guarded by {@link #occupancyLock}.
	 */
	private final int[] occupancySamples = new int[OCCUPANCY_WINDOW_SAMPLES];
	private int occupancyCount;
	private int occupancyHead;
	private final Object occupancyLock = new Object();

	/** nanoTime at {@link #start()}; the denominator for the effective PCM bitrate. */
	private volatile long startNanos;

	private final AtomicBoolean started = new AtomicBoolean();
	private final AtomicBoolean stopped = new AtomicBoolean();
	private volatile boolean running;

	/** Whether the feed is still live (not stopped). Lets a subclass abort an in-flight resolve. */
	protected final boolean isRunning() {
		return running;
	}
	private volatile Process process;
	private volatile Future<?> ingestTask;
	private volatile Future<?> deliveryTask;
	/** Coarse connection state for diagnostics: connecting/streaming/idle/waiting. */
	protected volatile String connectionState = "connecting";
	/**
	 * Whether the DELIVERY loop is currently playing real frames (sow-005 Phase A,
	 * item 2): mirrors the delivery-thread {@code playing} flag so any thread can
	 * read the declared PLAYOUT phase without touching the delivery loop's locals.
	 * {@code connectionState} tracks the INGEST side (is a source connected/streaming);
	 * this tracks the PLAYOUT side (are we past preroll and actually emitting audio).
	 * {@link #playoutState()} combines the two into streaming/buffering/idle.
	 */
	private volatile boolean deliveryPlaying;
	/**
	 * The last finished connection's idle classification (sow-005 Phase A finding):
	 * {@code true} once a connection ended classified idle (an HLS 404 off-air
	 * station), {@code false} after a connection that actually streamed or failed
	 * hard, and {@code false} until the first connection ends. Read only by
	 * {@link #playoutState()} to keep a repeatedly-404 off-air station reporting
	 * {@code "idle"} through the brief {@code "connecting"} probe at the top of each
	 * ladder cycle - before the new process's stderr re-classifies the 404 - instead
	 * of flashing {@code "buffering"} then settling back to idle every cycle. A true
	 * cold start (no prior classification) and the re-anchor/preroll rebuffer still
	 * report {@code "buffering"}.
	 */
	private volatile boolean lastClassifiedIdle;
	/**
	 * sow-012: set once when a FINITE source (repeat off) has streamed to its end and the ingest
	 * loop stopped instead of respawning. The delivery loop drains the retained buffer then delivers
	 * silence, and {@link #playoutState()} reports {@code "ended"} once playout stops - the signal the
	 * jukebox mixin uses to let the disc finish (particles stop) like a vanilla disc.
	 */
	private volatile boolean inputEnded;

	/**
	 * Per-connection stderr drain state: a fresh instance per spawned process
	 * so a late drainer from a previous connection can never contaminate the
	 * idle classification of the current one, plus a latch the ingest loop
	 * joins on before reading {@code sawIdle}/{@code sawFatal}/the stderr tail.
	 *
	 * <p>{@code sawIdle} and {@code sawFatal} are the connection-scoped signals a
	 * subclass should classify on (via {@link #isIdleStderr}/{@link #isFatalStderr}
	 * and {@link #classifyIdle(StderrDrain)}) instead of peeking the shared
	 * {@link #stderrTail}, which outlives the connection and can hold a stale line
	 * from an earlier one.</p>
	 */
	protected static final class StderrDrain {
		final AtomicBoolean sawIdle = new AtomicBoolean();
		final AtomicBoolean sawFatal = new AtomicBoolean();
		final CountDownLatch done = new CountDownLatch(1);
	}

	/** What produced a batch of dropped frames, so diagnostics can separate causes. */
	private enum DropCause {
		/**
		 * Hard-cap overflow in {@link #offerFrame} (an upstream flood re-sending
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
	private static final class RollingWindow {
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

	protected AbstractFfmpegFeed(String stationKey, String ffmpegBinary,
			int maxBufferFrames, int targetFrames, int prerollFrames, long maxBackoffSeconds,
			boolean reconnectPreserveBuffer, int reanchorLowWaterFrames, int reanchorSustainFrames,
			String smoothingMode, double maxStretchPercent) {
		this.stationKey = stationKey;
		this.ffmpegBinary = ffmpegBinary;
		// Config resolves these already, but re-establish the invariants defensively
		// so a bad direct construction can never wedge the delivery loop:
		// 1 <= preroll,target <= max.
		this.maxBufferFrames = Math.max(1, maxBufferFrames);
		this.targetFrames = Math.max(1, Math.min(targetFrames, this.maxBufferFrames));
		this.prerollFrames = Math.max(1, Math.min(prerollFrames, this.maxBufferFrames));
		// Wide hysteresis deadband: the trim engages only once the buffer creeps to
		// ~2x target, so ordinary jitter around target never trips it (zero
		// steady-state drops); a genuine creep triggers one bounded catch-up. The
		// engage point is additionally floored above the pre-roll depth
		// (prerollFrames + 1) so a deliberately DEEP pre-roll (prerollSeconds set
		// past 2x target) is not wiped straight back to target on the very first
		// PLAYING tick - which would discard the configured cushion and underrun on
		// the first upstream gap it was sized to bridge (sow-005 Phase 1). Still
		// capped at the hard maxBufferFrames.
		this.highWaterFrames = Math.min(this.maxBufferFrames,
				Math.max(Math.round(HYSTERESIS_HIGH_MULTIPLIER * this.targetFrames),
						Math.max(this.targetFrames + 1, this.prerollFrames + 1)));
		// sow-005 Phase 4 defensive invariants (config resolves these already):
		// the re-anchor low water is forced strictly below BOTH target and preroll so it
		// can only arm on a genuine drain (< target) AND always rebuilds to a depth above
		// its own arming threshold (a re-anchor re-prerolls to prerollFrames), so it fires
		// once per drain episode and cannot re-fire once per sustain window; >= 1. Sustain
		// >= 0 (0 = disabled).
		this.reconnectPreserveBuffer = reconnectPreserveBuffer;
		this.reanchorLowWaterFrames = Math.max(1,
				Math.min(reanchorLowWaterFrames,
						Math.max(1, Math.min(this.targetFrames - 1, this.prerollFrames - 1))));
		this.reanchorSustainFrames = Math.max(0, reanchorSustainFrames);
		this.maxBackoffSeconds = Math.max(INITIAL_BACKOFF_SECONDS, maxBackoffSeconds);
		// sow-005 Phase B: resolve the smoothing mode and stretch budget defensively
		// (config resolves these already). Anything other than an explicit "rebuffer"
		// with a positive budget runs the passthrough, so a bad direct construction can
		// never silently engage the DSP stage.
		this.smoothingMode = "rebuffer".equals(smoothingMode) ? "rebuffer" : "tempo_match";
		this.maxStretchFraction = Math.max(0.0, Math.min(0.06, maxStretchPercent / 100.0));
		this.tempoMatchEnabled = "tempo_match".equals(this.smoothingMode) && this.maxStretchFraction > 0.0;
	}

	// ------------------------------------------------------------------
	// Subclass extension points
	// ------------------------------------------------------------------

	/** The exact ffmpeg command to spawn (never logged unredacted). */
	protected abstract String[] buildCommand();

	/**
	 * The suffix of the "feed start" log line after {@code ingest=ffmpeg }, e.g.
	 * {@code url=<displayUrl>}. Must contain no secrets.
	 */
	protected abstract String startLogDetail();

	/**
	 * Redacts a single line (stderr, or the diag arg-shape) before it is recorded or
	 * logged. The default is NOT a no-op: it masks every URL in the line, so a feed
	 * that forgets to register its endpoint still cannot leak a tokened URL.
	 *
	 * <p>Subclasses that need extra redaction (a stream key that is not part of a URL,
	 * say) override this and call {@code super.sanitize(line)} first.</p>
	 */
	protected String sanitize(String line) {
		return redactUrls(line);
	}

	/**
	 * Registers the feed's endpoint for redaction. Subclasses whose ffmpeg input is a
	 * URL MUST call this from their constructor; everything that logs or prints a line
	 * then routes through {@link #sanitize(String)} and the secret cannot escape.
	 *
	 * <p>Audit fix: previously each subclass had to remember to store
	 * {@code endpoint.secret()} AND override {@code sanitize}. {@link HlsPullFeed} did;
	 * {@link FileLoopFeed} and {@link LinkResolvedFeed} did not, so a FILE disc whose
	 * URL carried a token printed it verbatim in {@code /jukeboxdisc diag} (gated only at
	 * gamemaster level, BELOW the admin gate that protects the disc UI) and in the
	 * reconnect WARN line. Registering by construction removes the chance to forget.</p>
	 */
	protected final void redactEndpoint(StationEndpoints.Endpoint endpoint) {
		if (endpoint == null) {
			return;
		}
		this.redactEffectiveUrl = endpoint.effectiveUrl();
		this.redactDisplayUrl = endpoint.displayUrl();
		this.redactSecret = endpoint.secret();
	}

	/**
	 * Replaces the registered effective URL with its display form, blanks the exact
	 * secret, and then masks the query values of EVERY remaining URL in the line.
	 *
	 * <p>That last pass is the load-bearing one. It is unconditional because a secret
	 * can ride under any param name - a presigned CDN link uses {@code ?sig=} or
	 * {@code ?X-Amz-Signature=}, and a yt-dlp-resolved media URL carries its own
	 * signature - so a {@code token=}-only pattern would still leak. It reuses
	 * {@link StationEndpoints#redactDisplay} rather than a second regex, so the display
	 * surfaces and the log surfaces cannot drift apart.</p>
	 */
	protected final String redactUrls(String line) {
		if (line == null || line.isEmpty()) {
			return line;
		}
		String effective = redactEffectiveUrl;
		String display = redactDisplayUrl;
		String secret = redactSecret;
		if (effective != null && display != null && line.contains(effective)) {
			line = line.replace(effective, display);
		}
		if (secret != null && !secret.isEmpty() && line.contains(secret)) {
			line = line.replace(secret, "***");
		}
		if (line.indexOf("://") < 0) {
			return line;
		}
		Matcher m = URL_IN_TEXT.matcher(line);
		StringBuilder out = new StringBuilder(line.length());
		while (m.find()) {
			m.appendReplacement(out, Matcher.quoteReplacement(StationEndpoints.redactDisplay(m.group())));
		}
		m.appendTail(out);
		return out.toString();
	}

	/**
	 * Called once from {@link #stop()}, after the ffmpeg child is destroyed and before
	 * the ingest task is cancelled. Subclasses that own an additional child process
	 * override this to kill it. Must not throw (a throw is caught and logged at debug).
	 */
	protected void onStop() {
	}

	/**
	 * True when this stderr line marks the "idle" condition (station off,
	 * publisher gone) rather than a hard error. Used only to set
	 * {@link StderrDrain#sawIdle}; default never idle.
	 */
	protected boolean isIdleStderr(String line) {
		return false;
	}

	/**
	 * True when this stderr line marks a hard error (bind/setup failure) for the
	 * current connection, recorded per-connection into {@link StderrDrain#sawFatal}.
	 * Used by subclasses whose "idle" is the ABSENCE of an error line (an RTMP
	 * listener: a clean publisher disconnect emits nothing, so idle cannot be a
	 * positive marker) rather than a positive idle line. Default never fatal.
	 */
	protected boolean isFatalStderr(String line) {
		return false;
	}

	/**
	 * Classifies a finished connection as idle (expected, quiet) vs error
	 * (worth a reconnect WARN). Default: idle iff a {@link #isIdleStderr} line
	 * was seen this connection. Classify only on the per-connection
	 * {@code drain} ({@code sawIdle}/{@code sawFatal}); never on the shared
	 * {@link #stderrTail}, whose last line may belong to an earlier connection.
	 */
	protected boolean classifyIdle(StderrDrain drain) {
		return drain != null && drain.sawIdle.get();
	}

	/** Logs/records the idle state and sets {@link #connectionState}. */
	protected void onIdle(long backoffSeconds) {
		connectionState = "idle";
		SavePointRadioMod.LOGGER.info("[jukebox] feed idle station={} retry={}s", stationKey, backoffSeconds);
	}

	/**
	 * Whether an idle (not error) end-of-connection should respawn promptly on a
	 * fixed short delay instead of escalating the reconnect backoff. Default
	 * {@code false}: HLS keeps sow-001's exponential 404-idle backoff. An RTMP
	 * RECEIVE listener overrides to {@code true} - a publisher disconnect is the
	 * normal case and the listen port must be re-bound quickly, so a flapping
	 * publisher is never locked out by an escalating backoff. Genuine bind/setup
	 * failures (classified NOT idle) keep escalating for both.
	 */
	protected boolean promptRespawnOnIdle() {
		return false;
	}

	/**
	 * sow-012: whether this feed should STOP (play once) rather than respawn/loop when its input ends
	 * after streaming. Default {@code false} (loop forever - HLS/RTMP and repeat-on discs). A FILE disc
	 * with repeat off overrides to {@code true}; a PLATFORM disc with repeat off overrides to
	 * {@code true} ONLY for a non-live VOD (a live stream keeps reconnecting). The ingest loop only
	 * honours this once a connection actually streamed frames, so a resolve/startup failure still
	 * retries rather than parking a never-played disc.
	 */
	protected boolean stopAtEndOfInput() {
		return false;
	}

	/**
	 * Delay before the ingest loop respawns ffmpeg after a connection ends.
	 * Default {@code backoffSeconds * 1000} - the exponential backoff sow-001
	 * used, which HLS keeps (its 404-idle escalation is fine). An RTMP RECEIVE
	 * listener overrides this to a small fixed delay so the {@code -listen 1}
	 * receiver re-binds almost immediately for the next publisher (SavePoint's
	 * ~2&nbsp;s reconnect), shrinking the gap the jitter buffer must cover.
	 *
	 * @param backoffSeconds the current exponential backoff value (seconds)
	 * @param idle whether the ended connection was classified idle (expected)
	 * @param attempt the 1-based consecutive reconnect attempt since the last
	 *     healthy-streak reset - the index a discrete reconnect ladder keys off
	 *     (HLS overrides on this; sow-005 Phase A, item 3). The base default and the
	 *     RTMP fast-relisten override both ignore it.
	 * @return milliseconds to sleep before the next spawn
	 */
	protected long reconnectDelayMillis(long backoffSeconds, boolean idle, long attempt) {
		return TimeUnit.SECONDS.toMillis(backoffSeconds);
	}

	/** Called once per spawned process, right after spawn. Default no-op. */
	protected void afterSpawn() {
	}

	protected final String stationKeyValue() {
		return stationKey;
	}

	protected final String lastStderrLine() {
		synchronized (stderrTail) {
			String last = stderrTail.peekLast();
			return last != null ? last : "-";
		}
	}

	/**
	 * Current buffered frame count, safe on any thread (a brief {@code bufferLock}
	 * read, no metric calls). A subclass sizes a spawn-time backlog grab against this
	 * so a buffer-preserving reconnect does not re-flood a non-empty jitter buffer
	 * past its hard cap (sow-005 Phase A finding).
	 */
	protected final int bufferedFrameCount() {
		synchronized (bufferLock) {
			return buffer.size();
		}
	}

	/** Steady-state target playout depth in frames (subclass spawn sizing). */
	protected final int targetFrameCount() {
		return targetFrames;
	}

	/** Pre-roll fill depth in frames (subclass spawn sizing). */
	protected final int prerollFrameCount() {
		return prerollFrames;
	}

	// ------------------------------------------------------------------
	// StationFeed
	// ------------------------------------------------------------------

	@Override
	public final String stationKey() {
		return stationKey;
	}

	@Override
	public final void start() {
		if (!started.compareAndSet(false, true)) {
			return;
		}
		ExecutorService executor = FeedRegistry.get().feedExecutor();
		if (executor == null) {
			SavePointRadioMod.LOGGER.warn(
					"[jukebox] feed start refused station={} (feed executor unavailable, server stopping?)", stationKey);
			return;
		}
		running = true;
		startNanos = System.nanoTime();
		SavePointRadioMod.LOGGER.info("[jukebox] feed start station={} ingest=ffmpeg {}", stationKey, startLogDetail());
		try {
			ingestTask = executor.submit(this::runIngestLoop);
			deliveryTask = executor.submit(this::runDeliveryLoop);
		} catch (RejectedExecutionException e) {
			running = false;
			SavePointRadioMod.LOGGER.warn("[jukebox] feed start rejected station={} (executor shut down)", stationKey);
		}
	}

	@Override
	public final void stop() {
		if (!stopped.compareAndSet(false, true)) {
			return;
		}
		running = false;
		deliveryPlaying = false;
		Process p = process;
		if (p != null) {
			p.destroy();
		}
		// A subclass may own a HELPER child that is not the ffmpeg process (LinkResolvedFeed's
		// yt-dlp resolve). Interrupting the ingest thread cannot free it - a read on a process
		// pipe is not interruptible - so the subclass gets an explicit chance to kill it here.
		try {
			onStop();
		} catch (Throwable t) {
			SavePointRadioMod.LOGGER.debug("[jukebox] feed onStop hook failed station={}: {}", stationKey, t.toString());
		}
		Future<?> ingest = ingestTask;
		if (ingest != null) {
			ingest.cancel(true);
		}
		Future<?> delivery = deliveryTask;
		if (delivery != null) {
			delivery.cancel(true);
		}
		synchronized (bufferLock) {
			buffer.clear();
		}
		sinks.clear();
		SavePointRadioMod.LOGGER.info("[jukebox] feed stop station={} ingest=ffmpeg frames={} delivered={} "
						+ "silence={} drops={} reconnects={} underruns={}",
				stationKey, framesRead.get(), framesDelivered.get(), silenceFrames.get(),
				drops.get(), reconnects.get(), underruns.get());
	}

	@Override
	public final void addSink(AudioSink sink) {
		sinks.add(sink);
	}

	@Override
	public final void removeSink(AudioSink sink) {
		sinks.remove(sink);
	}

	@Override
	public final long framesRead() {
		return framesRead.get();
	}

	@Override
	public final String getStatsLine() {
		int buffered;
		synchronized (bufferLock) {
			buffered = buffer.size();
		}
		return "station=" + stationKey
				+ " ingest=ffmpeg state=" + connectionState
				+ " frames=" + framesRead.get()
				+ " delivered=" + framesDelivered.get()
				+ " silence=" + silenceFrames.get()
				+ " drops=" + drops.get()
				+ " reconnects=" + reconnects.get()
				+ " underruns=" + underruns.get()
				+ " buffered=" + buffered
				+ " latencyMs=" + (buffered * FRAME_MILLIS)
				+ " targetMs=" + (targetFrames * FRAME_MILLIS)
				+ " sinks=" + sinks.size();
	}

	/**
	 * The declared PLAYOUT phase (sow-005 Phase A, item 2), safe on any thread:
	 * <ul>
	 *   <li>{@code "streaming"} - the delivery loop is past preroll and emitting real
	 *       frames ({@code deliveryPlaying}), even mid buffer-preserving reconnect;</li>
	 *   <li>{@code "idle"} - the ingest side reports no live source (HLS 404 idle, or an
	 *       RTMP listener waiting for a publisher): {@code connectionState} idle/waiting
	 *       and not currently playing;</li>
	 *   <li>{@code "buffering"} - otherwise: pre-rolling toward {@code prerollFrames} on
	 *       cold start, the re-anchor rebuffer-pause, or an initial connect - a source is
	 *       (being) established but playout has not resumed yet.</li>
	 * </ul>
	 * {@code deliveryPlaying} is authoritative for "streaming" so a buffer-preserving
	 * reconnect (source briefly gone, playout still draining retained frames) never
	 * flaps to idle/buffering.
	 */
	@Override
	public final String playoutState() {
		if (deliveryPlaying) {
			return "streaming";
		}
		// sow-012: a finite source (repeat off) that streamed to its end and drained the buffer. Checked
		// after deliveryPlaying so the ~buffer-worth of trailing audio still plays out ("streaming")
		// before this reports "ended" - so particles stop roughly when the sound actually goes silent.
		if (inputEnded) {
			return "ended";
		}
		String state = connectionState;
		if ("idle".equals(state) || "waiting".equals(state)) {
			return "idle";
		}
		// A repeatedly-404 off-air station re-enters "connecting" at the top of each
		// reconnect-ladder cycle before the new process's stderr re-classifies the 404
		// as idle. Without this it would flash "buffering" then settle back to "idle"
		// every cycle (an extra HUD name packet per nearby player per cycle). If the
		// LAST connection was classified idle, report the connecting probe as idle too.
		// A true cold start (no prior classification -> lastClassifiedIdle false) and the
		// re-anchor/preroll rebuffer (connectionState "streaming") still report
		// "buffering" (sow-005 Phase A finding).
		if ("connecting".equals(state) && lastClassifiedIdle) {
			return "idle";
		}
		return "buffering";
	}

	/**
	 * Subclass diagnostics suffix appended to {@link #getDiagLine()} (sow-005 Phase A,
	 * item 6). Default empty; HLS adds its start-behind depth and current reconnect
	 * ladder tier. Must be allocation-light and safe on any thread.
	 */
	protected String diagExtra() {
		return "";
	}

	/**
	 * A richer one-line snapshot for {@code /jukeboxdisc diag} (sow-005 Phase 0):
	 * everything in {@link #getStatsLine()} plus the rolling dropped-FRAME count
	 * ({@code dropsLast60s}, all causes, for drop/bandwidth accounting), the glitch
	 * EVENT total and rolling glitch-EVENT rate ({@code glitchEvents} /
	 * {@code glitchEventsLast60s}) - the sow-005 micro-glitch proof metric, one per
	 * catch-up/resync/underrun occurrence rather than per shed frame, so it reads a
	 * true 0 in steady state and 1 (not ~250) for a single bounded catch-up - the
	 * effective delivered PCM bitrate, the buffer-occupancy min/max/avg over the
	 * last window, and the feed's redacted ffmpeg arg-shape. Cheap - reads counters
	 * and three small rings; safe on any thread.
	 */
	@Override
	public final String getDiagLine() {
		String extra = diagExtra();
		return getStatsLine()
				+ " playout=" + playoutState()
				+ (extra.isEmpty() ? "" : " " + extra)
				+ " dropsLast" + DROP_WINDOW_SECONDS + "s=" + dropWindow.sum()
				+ " glitchEvents=" + glitchEvents.get()
				+ " glitchEventsLast" + DROP_WINDOW_SECONDS + "s=" + glitchEventWindow.sum()
				+ " pcmKbps=" + effectiveKbps()
				// sow-005 Phase 4: effective target depth (also targetMs above), the
				// buffer-preserving-reconnect count and the sustained-drain re-anchor
				// count + its low-water threshold, so an operator can see both smoothing
				// paths firing. All plain counter/field reads - allocation-light.
				+ " targetFrames=" + targetFrames
				+ " bufferPreservedReconnects=" + bufferPreservedReconnects.get()
				+ " reanchors=" + reanchors.get()
				+ " reanchorLowWaterMs=" + (reanchorLowWaterFrames * FRAME_MILLIS)
				// sow-005 Phase B: smoothing mode + live time-stretch. In TEMPO_MATCH the
				// stretch factor (output/input) the occupancy servo settled on, the implied
				// measured source content-rate (1/stretch, ~0.974 for the trunk), the clamp
				// budget, and a runtime-fallback marker if the DSP stage ever threw.
				+ " smoothingMode=" + smoothingMode
				// dspRingMs is the WSOLA stage's held depth (samples/48), the small fixed
				// latency the jitter-buffer latencyMs above EXCLUDES; true end-to-end
				// TEMPO_MATCH latency is latencyMs + dspRingMs.
				+ (tempoMatchEnabled
						? " stretch=" + round4(currentStretchFactor)
								+ " sourceRate=" + round4(currentStretchFactor > 0.0 ? 1.0 / currentStretchFactor : 1.0)
								+ " maxStretchPct=" + round2(maxStretchFraction * 100.0)
								+ " dspRingMs=" + (stretchRingSamples / 48)
								+ (stretchRuntimeFailed ? " stretchFallback=passthrough" : "")
						: "")
				+ " " + occupancySummary()
				+ " args=[" + redactedCommand() + "]";
	}

	/** Rounds to 4 decimal places for the diag stretch/source-rate note (no locale/format cost). */
	private static double round4(double v) {
		return Math.round(v * 10000.0) / 10000.0;
	}

	/** Rounds to 2 decimal places for the diag stretch-budget note. */
	private static double round2(double v) {
		return Math.round(v * 100.0) / 100.0;
	}

	/**
	 * Records {@code count} dropped frames from {@code cause}: always bumps the
	 * grand {@code drops} total and the all-causes {@code dropWindow} (frame-level
	 * drop/bandwidth accounting). An {@link DropCause#OVERFLOW} batch additionally
	 * feeds the overflow-only counter the "raise maxBufferSeconds" WARN gates on; a
	 * {@link DropCause#CATCH_UP} batch instead records ONE glitch event (via
	 * {@link #recordGlitchEvent()}) no matter how many frames it shed, so a single
	 * bounded correction never reads as many micro-glitches. No-op for a
	 * non-positive count; must never be called while holding {@link #bufferLock}
	 * (it takes only the small window monitors, and nothing under those takes
	 * {@code bufferLock}).
	 */
	private void recordDrops(long count, DropCause cause) {
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
	private void recordGlitchEvent() {
		glitchEvents.incrementAndGet();
		glitchEventWindow.add(1L);
	}

	/** Appends one buffered-occupancy sample to the diag ring. */
	private void recordOccupancy(int buffered) {
		synchronized (occupancyLock) {
			occupancySamples[occupancyHead] = buffered;
			occupancyHead = (occupancyHead + 1) % OCCUPANCY_WINDOW_SAMPLES;
			if (occupancyCount < OCCUPANCY_WINDOW_SAMPLES) {
				occupancyCount++;
			}
		}
	}

	/** {@code occBuffered(min/max/avg)} over the occupancy ring, or dashes when empty. */
	private String occupancySummary() {
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

	/**
	 * Effective delivered PCM bitrate in kbit/s: delivered (real, non-silence)
	 * frames * {@value #FRAME_SAMPLES} samples * 2 bytes * 8 bits over elapsed
	 * seconds since {@link #start()}. ~768 kbit/s when healthy (48&nbsp;kHz mono
	 * s16); a materially lower figure means silence/underruns ate into playout.
	 */
	private long effectiveKbps() {
		long start = startNanos;
		if (start == 0L) {
			return 0L;
		}
		long elapsedNanos = System.nanoTime() - start;
		if (elapsedNanos <= 0L) {
			return 0L;
		}
		double bits = framesDelivered.get() * (double) FRAME_BYTES * 8.0;
		double seconds = elapsedNanos / 1_000_000_000.0;
		return Math.round(bits / seconds / 1000.0);
	}

	/**
	 * The argv of the last spawn as a single string, run through {@link #sanitize(String)}
	 * so any token/stream key is redacted exactly as it is in stderr. Diagnostics only.
	 *
	 * <p>Reads the {@link #lastCommand} snapshot rather than calling
	 * {@code buildCommand()}: that method is an ingest-thread contract and may spawn a
	 * process (LinkResolvedFeed resolves through yt-dlp), which would make this getter -
	 * documented as cheap and safe on any thread, and reached from a Brigadier command
	 * handler - block the main server thread on network I/O.</p>
	 */
	private String redactedCommand() {
		String[] cmd = lastCommand;
		return cmd == null ? "(not spawned yet)" : sanitize(String.join(" ", cmd));
	}

	// ------------------------------------------------------------------
	// Ingest loop (feed executor thread)
	// ------------------------------------------------------------------

	private void runIngestLoop() {
		long backoffSeconds = INITIAL_BACKOFF_SECONDS;
		long attempt = 0;
		while (running && !Thread.currentThread().isInterrupted()) {
			connectionState = "connecting";
			// Per-connection reset, so a value left by the PREVIOUS connection cannot make
			// the stall watchdog fire against a listener that has not streamed yet.
			lastIngestFrameNanos = 0L;
			long firstFrameNanos = -1L;
			long lastFrameNanos = -1L;
			String reason;
			Process p = null;
			StderrDrain drain = null;
			try {
				p = spawnProcess();
				process = p;
				drain = startStderrDrainer(p);
				afterSpawn();
				long[] frameTimes = readFrames(p);
				firstFrameNanos = frameTimes[0];
				lastFrameNanos = frameTimes[1];
				reason = classifyEnd(p);
			} catch (IOException e) {
				recordStderr("spawn failed: " + e.getMessage());
				reason = "exit";
			} finally {
				process = null;
				if (p != null) {
					destroyProcess(p);
				}
			}
			if (!running || Thread.currentThread().isInterrupted()) {
				break;
			}

			awaitStderrDrain(drain);

			// sow-012: a finite source (repeat off) that STREAMED to its end plays once and stops
			// instead of respawning/looping. Gated on firstFrameNanos >= 0 so a resolve/startup
			// failure (never streamed) still retries below rather than parking a never-played disc.
			// The delivery loop drains the retained buffer then goes silent; playoutState() -> "ended".
			if (stopAtEndOfInput() && firstFrameNanos >= 0) {
				inputEnded = true;
				connectionState = "ended";
				SavePointRadioMod.LOGGER.info("[jukebox] feed end-of-input station={} (repeat off) - playing once, stopping",
						stationKey);
				break;
			}

			reconnects.incrementAndGet();
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

			boolean idle = p != null && classifyIdle(drain);
			// Remember this connection's classification so playoutState() can keep a
			// repeatedly-404 off-air station reading "idle" through the next "connecting"
			// probe instead of flashing "buffering" each ladder cycle (sow-005 Phase A
			// finding). A connection that streamed or failed hard clears it.
			lastClassifiedIdle = idle;
			if (idle) {
				// A prompt-respawn feed (RTMP RECEIVE) pins idle respawns to the
				// short initial delay so a flapping publisher is never locked out
				// of the listen port; HLS keeps its escalating 404-idle backoff.
				if (promptRespawnOnIdle()) {
					backoffSeconds = INITIAL_BACKOFF_SECONDS;
					attempt = 0;
				}
				onIdle(backoffSeconds);
			} else {
				connectionState = "waiting";
				SavePointRadioMod.LOGGER.warn("[jukebox] feed reconnect station={} attempt={} reason={} lastStderr={}",
						stationKey, attempt, reason, lastStderrLine());
			}

			try {
				Thread.sleep(Math.max(0L, reconnectDelayMillis(backoffSeconds, idle, attempt)));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
			backoffSeconds = Math.min(backoffSeconds * 2, maxBackoffSeconds);
		}
	}

	private Process spawnProcess() throws IOException {
		String[] cmd = buildCommand();
		// Snapshot for the diag arg-shape, so getDiagLine() never has to rebuild (and
		// possibly re-resolve) the command on a caller's thread.
		lastCommand = cmd;
		return new ProcessBuilder(List.of(cmd)).start();
	}

	/**
	 * Reads full 1920-byte frames from ffmpeg stdout into the ring buffer until
	 * EOF, error or stop. Returns {@code [firstFrameNanos, lastFrameNanos]}
	 * ({@code -1} when no frame arrived).
	 */
	private long[] readFrames(Process p) {
		byte[] raw = new byte[FRAME_BYTES];
		long firstFrameNanos = -1L;
		long lastFrameNanos = -1L;
		InputStream in = p.getInputStream();
		try {
			while (running && !Thread.currentThread().isInterrupted()) {
				int n = in.readNBytes(raw, 0, FRAME_BYTES);
				if (n < FRAME_BYTES) {
					break; // EOF (any partial trailing frame is discarded)
				}
				short[] frame = new short[FRAME_SAMPLES];
				ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(frame);
				lastFrameNanos = System.nanoTime();
				// Published for the stall watchdog, which cannot see this method's local.
				lastIngestFrameNanos = lastFrameNanos;
				if (firstFrameNanos < 0) {
					firstFrameNanos = lastFrameNanos;
					connectionState = "streaming";
				}
				framesRead.incrementAndGet();
				offerFrame(frame);
			}
		} catch (IOException e) {
			if (running) {
				recordStderr("stdout read failed: " + e.getMessage());
			}
		}
		return new long[] {firstFrameNanos, lastFrameNanos};
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
		synchronized (bufferLock) {
			while (buffer.size() >= maxBufferFrames) {
				buffer.pollFirst();
				dropped++;
			}
			buffer.addLast(frame);
		}
		recordDrops(dropped, DropCause.OVERFLOW);
	}

	/**
	 * Buffer-preserving reconnect (sow-005 Phase 4, the dev's D-061 win). Runs on
	 * the ingest thread between a process ending and the next spawn.
	 *
	 * <p>With {@link #reconnectPreserveBuffer} on (default) the retained buffer is
	 * left INTACT: playout keeps draining the frames it already holds while the new
	 * ffmpeg process refills them, so a reconnect gap shorter than the buffered depth
	 * (e.g. SavePoint's ~2&nbsp;s RTMP publisher restart) is bridged with no flush and
	 * no re-preroll blip. Only when the buffer still holds at least the pre-roll
	 * cushion is this counted a {@code bufferPreservedReconnects}; below the cushion
	 * (cold start or a long outage) nothing is done here - the delivery loop
	 * re-prerolls naturally on its next underrun, exactly as before.</p>
	 *
	 * <p>The preserve default reproduces the pre-Phase-4 behaviour, where a respawn never
	 * cleared the buffer (the only flush was in {@link #stop()}). With the knob off the
	 * buffer is instead flushed under {@code bufferLock} so playout re-prerolls cleanly
	 * from empty on every respawn - a NEW opt-in flush-and-re-preroll mode, not a
	 * restoration of prior behaviour. Either way this only touches the buffer under its
	 * lock and never blocks - the delivery and server threads are unaffected.</p>
	 */
	private void handleReconnectBuffer() {
		if (reconnectPreserveBuffer) {
			int buffered;
			synchronized (bufferLock) {
				buffered = buffer.size();
			}
			if (buffered >= prerollFrames) {
				bufferPreservedReconnects.incrementAndGet();
			}
			// else: below the cushion - leave the buffer intact and let the delivery
			// loop re-preroll on its next underrun (cold start / long outage).
			return;
		}
		// Opt-in flush mode (knob off): clear so playout re-prerolls from empty on every
		// respawn. This is NEW behaviour - the pre-Phase-4 code never flushed on respawn.
		synchronized (bufferLock) {
			buffer.clear();
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
		Thread thread = new Thread(drainer, "travelers-jukebox-stderr-" + stationKey);
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
				if (isIdleStderr(line)) {
					drain.sawIdle.set(true);
				}
				if (isFatalStderr(line)) {
					drain.sawFatal.set(true);
				}
				recordStderr(line);
			}
		} catch (IOException ignored) {
			// Process destroyed under us - expected on stop/reconnect.
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
		line = sanitize(line);
		synchronized (stderrTail) {
			while (stderrTail.size() >= STDERR_TAIL_LINES) {
				stderrTail.pollFirst();
			}
			stderrTail.addLast(line);
		}
	}

	// ------------------------------------------------------------------
	// Delivery loop (feed executor thread)
	// ------------------------------------------------------------------

	/**
	 * Target-latency jitter buffer playout. Pre-rolls silence until the buffer
	 * holds {@code prerollFrames}, then drains one frame per 20&nbsp;ms; an empty
	 * buffer is an underrun that delivers silence and re-rolls. While playing, a
	 * wide-deadband HYSTERESIS trim (sow-005 Phase 1) leaves the buffer untouched
	 * anywhere below high-water (~2x target) - so steady-state jitter drops zero
	 * frames and produces no micro-glitches - and only once latency genuinely
	 * creeps to high-water drains the excess back to {@code targetFrames} in one
	 * bounded whole-frame catch-up before disengaging until the next creep. The
	 * nanoTime drift/stall resync from sow-001 is preserved for delivery-thread
	 * lateness and still respects {@code targetFrames}.
	 */
	private void runDeliveryLoop() {
		// Shared zero frame; sinks must not mutate delivered frames.
		short[] silence = new short[FRAME_SAMPLES];
		long next = System.nanoTime();
		long lastStats = next;
		long lastOccupancy = next;
		// Wall-clock baseline paired with lastOccupancy for the host/VM freeze detector.
		long lastOccWallMs = System.currentTimeMillis();
		// The "feed drops high" WARN watches ONLY overflow drops (see below), so its
		// 30 s baseline is the overflow counter, not the all-causes drops total.
		long lastStatsOverflowDrops = overflowDrops.get();
		// Per-interval CSV diagnostics baselines (delivery thread only; FeedDiagnostics
		// off => never read). Seeded to the current counters so the first emitted row
		// shows a one-interval delta, not a since-start burst.
		long lastDiag = next;
		long diagFramesRead = framesRead.get();
		long diagDelivered = framesDelivered.get();
		long diagSilence = silenceFrames.get();
		long diagDrops = drops.get();
		long diagOverflow = overflowDrops.get();
		long diagUnderruns = underruns.get();
		long diagReanchors = reanchors.get();
		long diagReconnects = reconnects.get();
		// Playout state machine: starts pre-rolling (not yet playing). The
		// hysteresis trim needs no persistent latch - it engages, drains to target
		// and disengages within a single tick, so the deadband is simply "drop
		// nothing until the buffer climbs back to high-water again".
		boolean playing = false;
		deliveryPlaying = false;
		// Frames of resume gain-ramp still to apply (sow-005 Phase A, item 4). Armed to
		// RESUME_RAMP_FRAMES on every playing false->true transition (cold start, post
		// underrun, post re-anchor); counts down as each ramped frame is delivered, so
		// the ramp fires ONLY on a resume and never in steady state.
		int rampFramesLeft = 0;
		// Consecutive PLAYING ticks the buffer has sat below the re-anchor low water
		// (sow-005 Phase 4). Reset whenever occupancy recovers, on an underrun, and
		// after a re-anchor fires, so only a SUSTAINED drain trips the re-anchor.
		long belowLowTicks = 0L;
		// sow-005 Phase B: pitch-preserving time-stretch (TEMPO_MATCH) delivery-thread
		// state. The stretcher is delivery-thread-private (no locks; mirrors
		// applyResumeRamp) and only exists in TEMPO_MATCH mode; in REBUFFER it stays null
		// and the loop runs the exact Phase-A passthrough. stretchFailed latches a runtime
		// DSP exception into a permanent passthrough fallback so audio never breaks.
		WsolaTimeStretcher stretcher = tempoMatchEnabled ? new WsolaTimeStretcher() : null;
		boolean stretchFailed = false;
		// Reused staging for frames polled under bufferLock and appended to the stretcher
		// off-lock (references only cross the lock; the DSP ring copy runs off-lock).
		short[][] pollStage = new short[16][];
		// Occupancy-servo state. servoIntegral is the slow error-nulling term (seeded near
		// the trunk's ~2.6% deficit); stretchFactor is the clamped output/input factor the
		// stretcher applies. Published to currentStretchFactor for diag.
		double servoIntegral = clampStretch(STRETCH_INITIAL_INTEGRAL);
		double stretchFactor = clampFactor(1.0 + servoIntegral);
		// EMA-smoothed occupancy error feeding the proportional term (finding: keeps a
		// bursty fill/reconnect swing from stepping the factor across its clamp at 1 Hz).
		double occErrEma = 0.0;
		currentStretchFactor = tempoMatchEnabled ? stretchFactor : 1.0;
		while (running && !Thread.currentThread().isInterrupted()) {
			short[] frame = silence;
			long trimmed = 0L;
			// >= 0 only on the tick a controlled re-anchor fired: the buffered depth to
			// log/record for it, outside bufferLock.
			long reanchorBuffered = -1L;

			if (!playing) {
				// PRE-ROLL: hold at silence, draining nothing, until the buffer has
				// filled to prerollFrames; then start playout on the first real frame
				// and reset the pace clock so there is no catch-up burst.
				synchronized (bufferLock) {
					// Normally hold until prerollFrames are buffered. sow-012: but once the input has
					// ENDED (a finite repeat-off source shorter than the preroll cushion), play whatever
					// it managed to buffer rather than waiting for a preroll that will never arrive - else
					// a sub-preroll file/VOD would drain to "ended" having played only silence.
					if (buffer.size() >= prerollFrames || (inputEnded && !buffer.isEmpty())) {
						frame = buffer.pollFirst();
					} else {
						frame = null;
					}
				}
				if (frame != null) {
					playing = true;
					// Arm the click-free resume ramp: this is the single playing
					// false->true transition (cold start / post-underrun / post
					// re-anchor all route through here), so ramping the first
					// RESUME_RAMP_FRAMES from 0 to full gain covers every audible
					// resume and only those.
					rampFramesLeft = RESUME_RAMP_FRAMES;
					next = System.nanoTime();
					framesDelivered.incrementAndGet();
					// sow-005 Phase B: discard any stale samples the stretcher's private ring
					// held from before this preroll (a prior underrun/re-anchor left up to
					// ~45 ms of pre-outage audio behind its cursor). Resetting here makes the
					// stretch stream resume cleanly from the freshly-prerolled frames (its
					// first output re-does the Hann fade-in, reinforcing the click-free
					// resume ramp) instead of splicing stale audio before the new content.
					// Harmless on a cold start (the ring is already empty).
					if (stretcher != null) {
						stretcher.reset();
					}
				} else {
					frame = silence;
					silenceFrames.incrementAndGet();
				}
			} else {
				// PLAYING. sow-005 Phase B: in TEMPO_MATCH the WSOLA stage consumes the
				// jitter buffer and emits ONE pitch-preserving time-stretched frame per
				// tick, holding occupancy constant against the source deficit; a runtime
				// DSP exception latches a permanent passthrough fallback so audio never
				// breaks. In REBUFFER (stretcher == null) the exact Phase-A passthrough
				// below runs unchanged.
				boolean handled = false;
				if (stretcher != null && !stretchFailed) {
					try {
						// Poll enough input under bufferLock (references only cross the
						// lock), apply the SAME high-water trim, capture occupancy - then
						// append + synthesize entirely OFF the lock on delivery-thread
						// locals, mirroring the applyResumeRamp discipline.
						int needFrames = stretcher.framesNeededFor();
						if (needFrames > pollStage.length) {
							needFrames = pollStage.length;
						}
						int polledCount = 0;
						int postPollSize;
						synchronized (bufferLock) {
							int available = buffer.size();
							while (polledCount < needFrames) {
								short[] f = buffer.pollFirst();
								if (f == null) {
									break;
								}
								pollStage[polledCount++] = f;
							}
							// High-water hysteresis trim, unchanged: once the buffer has
							// crept to high-water, drain the excess oldest frames back to
							// target in one bounded catch-up (still caps latency on bursts).
							if (available >= highWaterFrames) {
								int excess = buffer.size() - targetFrames;
								for (int i = 0; i < excess; i++) {
									buffer.pollFirst();
								}
								if (excess > 0) {
									trimmed = excess;
								}
							}
							postPollSize = buffer.size();
						}
						// OFF-lock DSP: feed the private ring, then synthesize one stretched
						// frame - or, if the ring is starved (a TRUE drain despite max
						// stretch: source gone), take the identical underrun/re-anchor path
						// so the Phase-4 backstop still fires.
						for (int i = 0; i < polledCount; i++) {
							stretcher.append(pollStage[i]);
							pollStage[i] = null;
						}
						if (stretcher.canProduce()) {
							frame = stretcher.produceFrame(stretchFactor);
							// Publish the DSP ring depth for the diag (the fixed latency the
							// jitter-buffer latencyMs excludes); volatile, once per tick.
							stretchRingSamples = stretcher.bufferedSamplesAhead();
							framesDelivered.incrementAndGet();
							// SUSTAINED-DRAIN RE-ANCHOR (sow-005 Phase 4) keyed off jitter
							// buffer occupancy exactly as the passthrough path: with the
							// stretch holding content-rate this should almost never fire in
							// steady state, remaining as the hard-reset backstop for a real
							// outage.
							if (reanchorSustainFrames > 0) {
								if (postPollSize < reanchorLowWaterFrames) {
									if (++belowLowTicks >= reanchorSustainFrames) {
										playing = false;
										belowLowTicks = 0L;
										reanchorBuffered = postPollSize;
									}
								} else {
									belowLowTicks = 0L;
								}
							}
						} else {
							frame = silence;
							silenceFrames.incrementAndGet();
							playing = false;
							underruns.incrementAndGet();
							recordGlitchEvent();
							belowLowTicks = 0L;
						}
						handled = true;
					} catch (Throwable t) {
						// Never break audio: latch a permanent passthrough fallback for this
						// feed and let the Phase-A path below handle THIS tick too. Recover
						// cleanly at the fallback edge instead of dropping buffered audio and
						// undercounting the trim:
						//  (1) re-enqueue any polled-but-not-yet-appended frames to the FRONT
						//      of the jitter buffer (reverse index order preserves FIFO) so no
						//      buffered content is lost - frames already fed into the stretcher
						//      ring were nulled at append and cannot be recovered (bounded to
						//      the small ring);
						//  (2) account the high-water excess we already physically removed
						//      under lock this tick as a CATCH_UP drop (recorded here, off
						//      lock) rather than discarding it, then clear the shared
						//      accumulator so the passthrough retry below counts only its OWN
						//      trim (no double attribution).
						stretchFailed = true;
						stretchRuntimeFailed = true;
						long stretchTrim = trimmed;
						trimmed = 0L;
						synchronized (bufferLock) {
							for (int i = pollStage.length - 1; i >= 0; i--) {
								if (pollStage[i] != null) {
									buffer.addFirst(pollStage[i]);
									pollStage[i] = null;
								}
							}
						}
						recordDrops(stretchTrim, DropCause.CATCH_UP);
						SavePointRadioMod.LOGGER.error(
								"[jukebox] time-stretch failed station={} - falling back to passthrough for this feed",
								stationKey, t);
					}
				}
				if (!handled) {
				// PLAYING: drain one frame; when empty, underrun -> silence + re-roll.
				// HYSTERESIS TRIM (sow-005 Phase 1): only once the buffer has genuinely
				// crept to high-water (~2x target) do we drain the excess oldest frames
				// straight back to target in ONE bounded catch-up (whole frames only,
				// never a resample) - a single rare skip instead of the old continuous
				// one-frame-per-500ms nibble that cost ~120 micro-glitches/min. Anywhere
				// below high-water nothing is dropped, so steady-state jitter costs zero
				// glitches, and the catch-up cannot recur until the buffer climbs back to
				// high-water (the wide deadband). The single skip is bounded by the buffer
				// depth (maxBufferFrames - targetFrames) and only fires on a real creep.
				int postPollSize;
				synchronized (bufferLock) {
					int available = buffer.size();
					frame = buffer.pollFirst();
					if (frame != null && available >= highWaterFrames) {
						int excess = buffer.size() - targetFrames;
						for (int i = 0; i < excess; i++) {
							buffer.pollFirst();
						}
						if (excess > 0) {
							trimmed = excess;
						}
					}
					postPollSize = buffer.size();
				}
				if (frame != null) {
					framesDelivered.incrementAndGet();
					// SUSTAINED-DRAIN RE-ANCHOR (sow-005 Phase 4): the trunk produces at
					// ~98% of realtime, so at a fixed 50 fps our buffer slowly drains
					// toward empty - a creep the high-water trim can never counter (it only
					// fires when the buffer is HIGH). Once occupancy has sat below the low
					// watermark for reanchorSustainFrames consecutive ticks (a sustained
					// drain, not a one-off dip), perform ONE controlled re-anchor: drop
					// back to the pre-roll hold so playout pauses a single time and rebuilds
					// the full target cushion, instead of a cluster of ragged micro-underruns
					// as the buffer bounces off empty. One clean bounded discontinuity, not a
					// continuous clock warp (the explicit dev steer). Disabled when
					// reanchorSustainFrames == 0.
					if (reanchorSustainFrames > 0) {
						if (postPollSize < reanchorLowWaterFrames) {
							if (++belowLowTicks >= reanchorSustainFrames) {
								playing = false;
								belowLowTicks = 0L;
								reanchorBuffered = postPollSize;
							}
						} else {
							belowLowTicks = 0L;
						}
					}
				} else {
					frame = silence;
					silenceFrames.incrementAndGet();
					playing = false;
					underruns.incrementAndGet();
					// An underrun (playout ran dry -> silence + re-roll) is an audible
					// discontinuity too; record it as one glitch event.
					recordGlitchEvent();
					// The underrun already forces a re-preroll; clear the drain streak so
					// a true underrun and the proactive re-anchor never fight or double up.
					belowLowTicks = 0L;
				}
				} // end if (!handled) - Phase-A passthrough
			}

			// Count the hysteresis catch-up drops outside bufferLock (recordDrops
			// takes only the window monitors and must never nest under bufferLock).
			// One catch-up is ONE glitch event, however many frames it sheds.
			if (trimmed > 0) {
				recordDrops(trimmed, DropCause.CATCH_UP);
			}
			// Record a controlled re-anchor outside bufferLock (recordGlitchEvent takes
			// only the window monitor): it is ONE deliberate bounded discontinuity - the
			// pre-roll pause that rebuilds the cushion - counted distinctly from an
			// underrun and logged as its own line, no frames dropped (the retained frames
			// stay and playout resumes from them once refilled to prerollFrames).
			if (reanchorBuffered >= 0L) {
				reanchors.incrementAndGet();
				recordGlitchEvent();
				SavePointRadioMod.LOGGER.info(
						"[jukebox] feed reanchor station={} buffered={} lowWaterFrames={} sustainFrames={} - "
								+ "sustained drain; re-prerolling to rebuild target cushion",
						stationKey, reanchorBuffered, reanchorLowWaterFrames, reanchorSustainFrames);
			}

			// CLICK-FREE RESUME RAMP (sow-005 Phase A, item 4): on the first
			// RESUME_RAMP_FRAMES after a resume, deliver a fresh COPY of the frame
			// scaled by a linear 0->full-gain envelope so the resume has no click.
			// Only on a real resume frame (playing, not the shared silence frame),
			// so steady state neither ramps nor allocates. The dequeued frame is
			// never mutated in place - a sink may retain it - so we scale into a new
			// array. Runs on the delivery thread, outside bufferLock.
			if (playing && rampFramesLeft > 0 && frame != silence) {
				frame = applyResumeRamp(frame, rampFramesLeft);
				rampFramesLeft--;
			}
			// Publish the playout phase for playoutState()/the HUD (volatile write,
			// once per tick; cheap). deliveryPlaying==true == emitting real audio.
			deliveryPlaying = playing;

			for (AudioSink sink : sinks) {
				try {
					sink.accept(frame);
				} catch (Throwable t) {
					SavePointRadioMod.LOGGER.error("[jukebox] sink accept failed station={}", stationKey, t);
				}
			}

			long now = System.nanoTime();
			if (now - lastOccupancy >= 1_000_000_000L) {
				// One buffered-occupancy sample per second for the /jukeboxdisc diag
				// min/max/avg note (a small ring; cheap).
				long nanoDeltaMs = (now - lastOccupancy) / 1_000_000L;
				lastOccupancy = now;
				// HOST/VM FREEZE DETECTOR: compare wall-clock elapsed to monotonic
				// (nanoTime) elapsed over this ~1s window. Normally equal. If the OS/
				// hypervisor SUSPENDS the process (e.g. WSL2 host freezing the guest, a
				// paused container, host memory reclaim), the monotonic clock pauses while
				// the wall clock keeps running - so wall >> nano by the frozen duration.
				// This is invisible to our nanoTime-paced delivery (which stays perfect on
				// its own clock) yet drains the client's sound-card buffer by exactly that
				// gap, so it is THE server-side-measurable cause of an otherwise-unexplained
				// hitch. On healthy hardware freezeMs stays ~0; a non-zero count here is an
				// environment problem, not a mod bug.
				long nowWallMs = System.currentTimeMillis();
				long freezeMs = (nowWallMs - lastOccWallMs) - nanoDeltaMs;
				lastOccWallMs = nowWallMs;
				if (freezeMs >= HOST_FREEZE_THRESHOLD_MS) {
					hostFreezeEvents.incrementAndGet();
					hostFreezeMillis.addAndGet(freezeMs);
					SavePointRadioMod.LOGGER.warn(
							"[jukebox] host stall station={} froze~{}ms (monotonic clock paused vs wall clock: the OS/VM "
									+ "suspended the process - NOT the mod). A client playout buffer shallower than "
									+ "~{}ms will hitch here.",
							stationKey, freezeMs, freezeMs);
				}
				int occBuffered;
				synchronized (bufferLock) {
					occBuffered = buffer.size();
				}
				recordOccupancy(occBuffered);
				// OCCUPANCY SERVO (sow-005 Phase B): once per second, nudge the WSOLA
				// stretch factor to hold jitter-buffer occupancy at targetFrames. A slow
				// PI loop (heavy integral, damping proportional term) that adapts over
				// minutes and cannot chase short-term jitter (that is the buffer's job):
				// occupancy below target -> stretch a touch MORE (slower, higher factor)
				// to refill; above target -> ease toward 1.0. The integrator nulls the
				// steady-state error so the factor converges to the source's exact inverse
				// content-rate with no open-loop rate math; the factor is hard-clamped to
				// the +/- budget. Frozen while not playing (no source to measure) and never
				// runs in REBUFFER mode, so that path has zero residual effect.
				if (stretcher != null && !stretchFailed && playing) {
					double errSec = (targetFrames - occBuffered) / FRAMES_PER_SECOND;
					// Integral path UNCHANGED: nulls the steady-state error over minutes on
					// the RAW error, so the factor still converges to the source's exact
					// inverse content-rate.
					servoIntegral = clampStretch(servoIntegral + STRETCH_KI * errSec);
					// Proportional path acts on an EMA-SMOOTHED error so second-to-second
					// occupancy jitter (bursty fill / reconnect catch-up) cannot saturate the
					// clamp at the 1 Hz update rate.
					occErrEma += STRETCH_ERR_EMA_ALPHA * (errSec - occErrEma);
					double desired = clampFactor(1.0 + servoIntegral + STRETCH_KP * occErrEma);
					// Slew-limit the published factor to a small per-update delta so tempo
					// only moves on the minutes timescale (no audible wow/flutter staircase),
					// even during a large occupancy transient.
					double delta = desired - stretchFactor;
					if (delta > STRETCH_MAX_SLEW) {
						delta = STRETCH_MAX_SLEW;
					} else if (delta < -STRETCH_MAX_SLEW) {
						delta = -STRETCH_MAX_SLEW;
					}
					stretchFactor = clampFactor(stretchFactor + delta);
					currentStretchFactor = stretchFactor;
				}
				// PER-INTERVAL CSV DIAGNOSTICS (sow testing aid). Off by default:
				// FeedDiagnostics.get() is null and this is skipped entirely. When on,
				// emit one row of live state + delta counters, reusing occBuffered so no
				// extra bufferLock read; the actual file write is async on the diag IO
				// thread, so nothing here touches disk on the pacing path.
				FeedDiagnostics diag = FeedDiagnostics.get();
				if (diag != null && now - lastDiag >= diag.intervalNanos()) {
					long nowMs = System.currentTimeMillis();
					long fr = framesRead.get();
					long de = framesDelivered.get();
					long si = silenceFrames.get();
					long dr = drops.get();
					long ov = overflowDrops.get();
					long un = underruns.get();
					long ra = reanchors.get();
					long rc = reconnects.get();
					double elapsedSec = (now - lastDiag) / 1_000_000_000.0;
					double expectedFrames = elapsedSec * FRAMES_PER_SECOND;
					double deliverRt = expectedFrames > 0.0 ? (de - diagDelivered) / expectedFrames : 0.0;
					double ingestRt = expectedFrames > 0.0 ? (fr - diagFramesRead) / expectedFrames : 0.0;
					double stretch = currentStretchFactor;
					double sourceRate = stretch > 0.0 ? 1.0 / stretch : 1.0;
					double fillPct = targetFrames > 0 ? 100.0 * occBuffered / targetFrames : 0.0;
					diag.record(String.format(Locale.ROOT,
							"%d,%s,%s,%s,%s,%s,%b,%d,%d,%d,%d,%.1f,%d,%d,%d,%d,%d,%d,%d,%d,%.3f,%.3f,%.4f,%.4f,%d",
							nowMs, FeedDiagnostics.TIME_FORMAT.format(Instant.ofEpochMilli(nowMs)),
							stationKey, getClass().getSimpleName(), playoutState(), connectionState,
							deliveryPlaying, occBuffered, occBuffered * FRAME_MILLIS,
							targetFrames * FRAME_MILLIS, maxBufferFrames * FRAME_MILLIS, fillPct,
							de - diagDelivered, si - diagSilence, un - diagUnderruns, dr - diagDrops,
							ov - diagOverflow, ra - diagReanchors, rc - diagReconnects, fr - diagFramesRead,
							deliverRt, ingestRt, stretch, sourceRate, freezeMs));
					diagFramesRead = fr;
					diagDelivered = de;
					diagSilence = si;
					diagDrops = dr;
					diagOverflow = ov;
					diagUnderruns = un;
					diagReanchors = ra;
					diagReconnects = rc;
					lastDiag = now;
				}
			}
			if (now - lastStats >= STATS_INTERVAL_NANOS) {
				lastStats = now;
				int buffered;
				synchronized (bufferLock) {
					buffered = buffer.size();
				}
				SavePointRadioMod.LOGGER.info(
						"[jukebox] feed stats station={} frames={} delivered={} silence={} drops={} reconnects={} "
								+ "underruns={} buffered={} latencyMs={} targetMs={} hostFreezes={} hostFreezeMs={}",
						stationKey, framesRead.get(), framesDelivered.get(), silenceFrames.get(),
						drops.get(), reconnects.get(), underruns.get(), buffered,
						buffered * FRAME_MILLIS, targetFrames * FRAME_MILLIS,
						hostFreezeEvents.get(), hostFreezeMillis.get());
				// Gate ONLY on hard-cap OVERFLOW drops: those are the frames raising
				// maxBufferSeconds actually bounds. Hysteresis catch-up and stall-resync
				// drops are a bounded latency correction the maxBuffer knob does not
				// address (a single one can legitimately shed maxBufferFrames-targetFrames
				// frames), so folding them in here would misattribute a normal correction
				// to an overflow and push the operator at the wrong knob (sow-005 Phase 1).
				long totalOverflowDrops = overflowDrops.get();
				long overflowDelta = totalOverflowDrops - lastStatsOverflowDrops;
				lastStatsOverflowDrops = totalOverflowDrops;
				if (overflowDelta > DROPS_WARN_PER_STATS_INTERVAL) {
					SavePointRadioMod.LOGGER.warn(
							"[jukebox] feed drops high station={} overflowLast30s={} maxBufferFrames={} - upstream "
									+ "bursts exceed the jitter buffer's hard cap; raise 'maxBufferSeconds' in "
									+ "travelers_jukebox.json",
							stationKey, overflowDelta, maxBufferFrames);
				}
				checkIngestStall();
			}

			next += FRAME_NANOS;
			long lead = next - now;
			if (lead > 0) {
				LockSupport.parkNanos(lead);
			} else if (-lead > MAX_CATCHUP_LAG_NANOS) {
				// Real delivery-thread scheduling stall (not upstream jitter): resync
				// the pace clock to now, and shed the backlog the stall accumulated -
				// but never below targetFrames, so this bulk drop cannot cut into the
				// intended playout depth or fight the hysteresis trim. Any excess above
				// target that remains is left for the trim's next high-water catch-up.
				long skipped = (now - next) / FRAME_NANOS;
				next = now;
				if (skipped > 0) {
					long dropped = 0L;
					synchronized (bufferLock) {
						for (long i = 0; i < skipped && buffer.size() > targetFrames; i++) {
							buffer.pollFirst();
							dropped++;
						}
					}
					recordDrops(dropped, DropCause.CATCH_UP);
				}
			}
		}
	}

	/**
	 * A fresh, gain-ramped copy of {@code src} for the resume envelope (sow-005
	 * Phase A, item 4). Gain rises linearly from ~0 to 1.0 across the whole
	 * {@code RESUME_RAMP_FRAMES}-frame span: the frame currently being delivered is
	 * index {@code RESUME_RAMP_FRAMES - framesLeft} (0-based), and within it each of
	 * the {@link #FRAME_SAMPLES} s16 samples is scaled by
	 * {@code (globalSampleIndex + 1) / (RESUME_RAMP_FRAMES * FRAME_SAMPLES)}, so the
	 * very last ramp sample reaches full gain. Never mutates {@code src} (a sink may
	 * retain the delivered array); allocates one frame, only on ramp ticks.
	 */
	/** Clamps the servo integrator to the +/- stretch budget (anti-windup). */
	private double clampStretch(double v) {
		return Math.max(-maxStretchFraction, Math.min(maxStretchFraction, v));
	}

	/** Hard-clamps the output stretch factor to {@code 1 +/- maxStretchFraction}. */
	private double clampFactor(double v) {
		return Math.max(1.0 - maxStretchFraction, Math.min(1.0 + maxStretchFraction, v));
	}

	private static short[] applyResumeRamp(short[] src, int framesLeft) {
		int frameIndex = RESUME_RAMP_FRAMES - framesLeft;
		int totalSamples = RESUME_RAMP_FRAMES * FRAME_SAMPLES;
		int baseIndex = frameIndex * FRAME_SAMPLES;
		short[] out = new short[FRAME_SAMPLES];
		for (int i = 0; i < FRAME_SAMPLES; i++) {
			double gain = (double) (baseIndex + i + 1) / (double) totalSamples;
			out[i] = (short) Math.round(src[i] * gain);
		}
		return out;
	}

	// ------------------------------------------------------------------
	// Process teardown
	// ------------------------------------------------------------------

	/**
	 * destroy, 2&nbsp;s grace, then destroyForcibly. Safe on an already-dead
	 * process. Only ever called from the ingest loop's {@code finally} block (a
	 * feed executor thread) - never from {@link #stop()}, whose caller is
	 * frequently the server thread and must not wait on the grace period.
	 */
	/**
	 * Backstop for a STALLED-BUT-ALIVE ffmpeg. {@code readFrames} blocks on the child's
	 * stdout with no deadline, so a child that stops producing without exiting parks the
	 * ingest thread: the connection never "ends", the reconnect ladder never runs, and the
	 * feed is silent while diag still reports {@code state=streaming}. The per-feed
	 * {@code -rw_timeout} handles the common socket case; this catches everything else by
	 * killing the child, which EOFs the read and hands over to the existing ladder.
	 *
	 * <p>Only fires for a connection that HAS streamed. A connection with no frames yet is
	 * the normal state of an RTMP listener waiting for a publisher, and killing that would
	 * break the listener the operator is waiting on.</p>
	 *
	 * <p>Runs on the delivery thread, from the 30 s stats tick.</p>
	 */
	private void checkIngestStall() {
		if (!running) {
			return;
		}
		Process p = process;
		long last = lastIngestFrameNanos;
		if (p == null || last == 0L || !p.isAlive()) {
			return;
		}
		long stalledNanos = System.nanoTime() - last;
		if (stalledNanos < INGEST_STALL_NANOS) {
			return;
		}
		SavePointRadioMod.LOGGER.warn(
				"[jukebox] feed ingest stalled station={} - no audio from ffmpeg for {}s while the process is still "
						+ "alive; restarting the connection",
				stationKey, TimeUnit.NANOSECONDS.toSeconds(stalledNanos));
		lastIngestFrameNanos = 0L;
		// Only the NON-blocking SIGTERM here: this runs on the real-time delivery thread, which
		// must never park on destroyProcess()'s 2 s grace wait (that would starve every listener
		// mid-playout). p.destroy() closes ffmpeg's stdout, which unblocks the ingest thread's
		// readFrames; that thread's own finally block then runs the full destroyProcess() with
		// the grace period, where waiting IS allowed, and rides the reconnect ladder.
		p.destroy();
	}

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
