package fm.gbti.jockey.session;

import fm.gbti.jockey.JockeyRadioMod;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
	static final int FRAME_BYTES = FRAME_SAMPLES * 2;
	static final long FRAME_NANOS = 20_000_000L;
	static final long FRAME_MILLIS = 20L;

	static final long INITIAL_BACKOFF_SECONDS = 2L;

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

	final String stationKey;
	protected final String ffmpegBinary;
	/** Hard cap on buffered frames (drop-oldest overflow bound). */
	final int maxBufferFrames;
	/** Steady-state playout depth the live-edge trim walks the buffer back toward. */
	final int targetFrames;
	/** Fill required before (re)starting playout after a start or an underrun. */
	final int prerollFrames;
	/** Hysteresis engage point: {@code round(HYSTERESIS_HIGH_MULTIPLIER * targetFrames)}. */
	final int highWaterFrames;
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
	final boolean reconnectPreserveBuffer;
	/**
	 * Buffered depth (frames) below which the sustained-drain re-anchor arms
	 * (sow-005 Phase 4); forced strictly below BOTH {@code targetFrames} (so ordinary
	 * jitter around target never arms it) and {@code prerollFrames} (so the re-anchor,
	 * which rebuilds the cushion to {@code prerollFrames}, always lands above its own
	 * arming threshold and fires once per drain episode, never once per sustain window).
	 */
	final int reanchorLowWaterFrames;
	/**
	 * Consecutive below-low-water delivery ticks (20&nbsp;ms each) required before
	 * ONE controlled re-anchor fires, or {@code 0} to disable the re-anchor entirely
	 * (sow-005 Phase 4).
	 */
	final int reanchorSustainFrames;
	final long maxBackoffSeconds;

	// --- sow-005 Phase B: pitch-preserving time-stretch (TEMPO_MATCH) ---
	/** Normalized smoothing mode ({@code "tempo_match"} or {@code "rebuffer"}). */
	private final String smoothingMode;
	/** Hard clamp on the stretch factor's magnitude ({@code factor in 1 +/- this}). */
	final double maxStretchFraction;
	/**
	 * Whether the WSOLA time-stretch stage is active: {@code "tempo_match"} mode with
	 * a positive stretch budget. When false the delivery loop runs the exact Phase-A
	 * passthrough (safe rollback, zero residual effect).
	 */
	final boolean tempoMatchEnabled;
	/**
	 * The live stretch factor ({@code output/input}) the occupancy servo last set,
	 * published for {@code /jockey diag}. {@code 1.0} means no stretch. Written once
	 * per servo update by the delivery thread; read on any thread.
	 */
	volatile double currentStretchFactor = 1.0;
	/**
	 * Set true if the time-stretch stage ever throws at runtime: the delivery loop then
	 * permanently falls back to the Phase-A passthrough so audio never breaks (logged
	 * once). Diagnostics only; the authoritative flag is a delivery-thread local.
	 */
	volatile boolean stretchRuntimeFailed;
	/**
	 * Samples the WSOLA stage currently holds ahead of its analysis cursor - the DSP
	 * ring depth that sits BEHIND the jitter buffer and adds a small fixed latency the
	 * jitter-buffer {@code latencyMs} does not include. Published each PLAYING tick in
	 * TEMPO_MATCH for the {@code /jockey diag} ({@code dspRingMs}); stays 0 in
	 * REBUFFER. Delivery-thread write, any-thread read (diagnostic only).
	 */
	volatile int stretchRingSamples;

	final List<AudioSink> sinks = new CopyOnWriteArrayList<>();

	/** Ring buffer of decoded frames, guarded by {@code bufferLock}. */
	final ArrayDeque<short[]> buffer = new ArrayDeque<>();
	final Object bufferLock = new Object();

	/** Bounded tail of recent (sanitized) ffmpeg stderr lines, guarded by itself. */
	final ArrayDeque<String> stderrTail = new ArrayDeque<>();

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
	volatile String[] lastCommand;

	/**
	 * {@code System.nanoTime()} of the most recent frame read from ffmpeg, or {@code 0}
	 * when this connection has not produced one yet. Read by {@link FeedDeliveryLoop#checkIngestStall()}
	 * on the delivery thread; written by the ingest thread.
	 */
	volatile long lastIngestFrameNanos;

	/** Counters, rolling windows and the occupancy ring (see {@link FeedStats}). */
	final FeedStats stats = new FeedStats();

	/** nanoTime at {@link #start()}; the denominator for the effective PCM bitrate. */
	private volatile long startNanos;

	private final AtomicBoolean started = new AtomicBoolean();
	private final AtomicBoolean stopped = new AtomicBoolean();
	volatile boolean running;

	/** Whether the feed is still live (not stopped). Lets a subclass abort an in-flight resolve. */
	protected final boolean isRunning() {
		return running;
	}
	volatile Process process;
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
	volatile boolean deliveryPlaying;
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
	volatile boolean lastClassifiedIdle;
	/**
	 * sow-012: set once when a FINITE source (repeat off) has streamed to its end and the ingest
	 * loop stopped instead of respawning. The delivery loop drains the retained buffer then delivers
	 * silence, and {@link #playoutState()} reports {@code "ended"} once playout stops - the signal the
	 * jukebox mixin uses to let the disc finish (particles stop) like a vanilla disc.
	 */
	volatile boolean inputEnded;

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
		/** Set by {@link #isRejectStderr}: this connection's audio must never be offered. */
		final AtomicBoolean sawRejected = new AtomicBoolean();
		final CountDownLatch done = new CountDownLatch(1);
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
	 * URL carried a token printed it verbatim in {@code /jockey diag} (gated only at
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
	 * True when this stderr line means the connection must be refused: the drainer kills
	 * the child at once and no frame from the connection is offered. Used for checks
	 * ffmpeg only warns about, such as an RTMP publisher using the wrong stream key.
	 * Default never rejects.
	 */
	protected boolean isRejectStderr(String line) {
		return false;
	}

	/**
	 * How long a connection's first frame is held before any audio from it is offered,
	 * so a rejection logged before decoding starts is always seen first. ffmpeg writes
	 * such a line during the handshake, before it produces PCM, so the hold only has to
	 * cover the drainer thread being scheduled. Default 0 (no hold).
	 */
	protected long admissionDelayMillis() {
		return 0L;
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
		JockeyRadioMod.LOGGER.info("[jockey] feed idle station={} retry={}s", stationKey, backoffSeconds);
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
			JockeyRadioMod.LOGGER.warn(
					"[jockey] feed start refused station={} (feed executor unavailable, server stopping?)", stationKey);
			return;
		}
		running = true;
		startNanos = System.nanoTime();
		JockeyRadioMod.LOGGER.info("[jockey] feed start station={} ingest=ffmpeg {}", stationKey, startLogDetail());
		try {
			ingestTask = executor.submit(new FeedIngestLoop(this)::runIngestLoop);
			deliveryTask = executor.submit(new FeedDeliveryLoop(this)::runDeliveryLoop);
		} catch (RejectedExecutionException e) {
			running = false;
			JockeyRadioMod.LOGGER.warn("[jockey] feed start rejected station={} (executor shut down)", stationKey);
		}
	}

	@Override
	public final void stop() {
		if (!stopped.compareAndSet(false, true)) {
			return;
		}
		running = false;
		deliveryPlaying = false;
		// SIGTERM, then SIGKILL after a grace, without waiting here: the caller is often the
		// server thread. The ingest thread cannot escalate on its own while it is parked on a
		// read that only the child's death can end.
		ProcessReaper.terminate(process);
		// A subclass may own a HELPER child that is not the ffmpeg process (LinkResolvedFeed's
		// yt-dlp resolve). Interrupting the ingest thread cannot free it - a read on a process
		// pipe is not interruptible - so the subclass gets an explicit chance to kill it here.
		try {
			onStop();
		} catch (Throwable t) {
			JockeyRadioMod.LOGGER.debug("[jockey] feed onStop hook failed station={}: {}", stationKey, t.toString());
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
		JockeyRadioMod.LOGGER.info("[jockey] feed stop station={} ingest=ffmpeg frames={} delivered={} "
						+ "silence={} drops={} reconnects={} underruns={}",
				stationKey, stats.framesRead.get(), stats.framesDelivered.get(), stats.silenceFrames.get(),
				stats.drops.get(), stats.reconnects.get(), stats.underruns.get());
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
		return stats.framesRead.get();
	}

	@Override
	public final String getStatsLine() {
		int buffered;
		synchronized (bufferLock) {
			buffered = buffer.size();
		}
		return "station=" + stationKey
				+ " ingest=ffmpeg state=" + connectionState
				+ " frames=" + stats.framesRead.get()
				+ " delivered=" + stats.framesDelivered.get()
				+ " silence=" + stats.silenceFrames.get()
				+ " drops=" + stats.drops.get()
				+ " reconnects=" + stats.reconnects.get()
				+ " underruns=" + stats.underruns.get()
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
	 * A richer one-line snapshot for {@code /jockey diag} (sow-005 Phase 0):
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
				+ " dropsLast" + FeedStats.DROP_WINDOW_SECONDS + "s=" + stats.dropWindow.sum()
				+ " glitchEvents=" + stats.glitchEvents.get()
				+ " glitchEventsLast" + FeedStats.DROP_WINDOW_SECONDS + "s=" + stats.glitchEventWindow.sum()
				+ " pcmKbps=" + effectiveKbps()
				// sow-005 Phase 4: effective target depth (also targetMs above), the
				// buffer-preserving-reconnect count and the sustained-drain re-anchor
				// count + its low-water threshold, so an operator can see both smoothing
				// paths firing. All plain counter/field reads - allocation-light.
				+ " targetFrames=" + targetFrames
				+ " bufferPreservedReconnects=" + stats.bufferPreservedReconnects.get()
				+ " reanchors=" + stats.reanchors.get()
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
				+ " " + stats.occupancySummary()
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
		double bits = stats.framesDelivered.get() * (double) FRAME_BYTES * 8.0;
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
}
