package fm.gbti.jockey.session;

import fm.gbti.jockey.JockeyRadioMod;
import fm.gbti.jockey.session.FeedStats.DropCause;
import fm.gbti.jockey.config.RadioConfig;

import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static fm.gbti.jockey.session.AbstractFfmpegFeed.*;

/**
 * The playout side of an {@link AbstractFfmpegFeed}: the target-latency delivery loop that
 * drains the jitter buffer to every sink once per 20&nbsp;ms, with the hysteresis trim,
 * re-anchor, time-stretch servo, resume ramp and ingest-stall watchdog. Runs on one feed
 * executor thread; every piece of shared state stays on the feed.
 */
final class FeedDeliveryLoop {

	/** Behind by more than this after a stall: resync to now instead of bursting. */
	private static final long MAX_CATCHUP_LAG_NANOS = 200_000_000L;

	private static final long STATS_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);
	/**
	 * How long a live-but-silent ffmpeg is tolerated before {@link #checkIngestStall()}
	 * restarts the connection. Comfortably above the per-feed {@code -rw_timeout} so the
	 * socket-level guard fires first and this stays a genuine backstop.
	 */
	private static final long INGEST_STALL_NANOS = TimeUnit.SECONDS.toNanos(60);
	/** Wall-clock-minus-monotonic gap (ms) at/above which a delivery-loop tick counts as a host/VM freeze. */
	private static final long HOST_FREEZE_THRESHOLD_MS = 100L;

	/**
	 * Click-free resume ramp length in frames (sow-005 Phase A, item 4). Every
	 * playout resume from a preroll / re-anchor / reconnect (the single
	 * {@code playing} false-&gt;true transition) linearly ramps the s16 samples of the
	 * first {@code RESUME_RAMP_FRAMES} delivered frames from 0 to full gain so the
	 * resume carries no click. 2 frames = ~40&nbsp;ms at {@link AbstractFfmpegFeed#FRAME_NANOS}, inside
	 * the 20-50&nbsp;ms window. Applied ONLY on the resume transition, never in steady
	 * state; the ramp allocates a fresh frame (so a shared/dequeued frame is never
	 * mutated) only on those 1-2 frames per resume.
	 *
	 * <p>The pause edge (playing true-&gt;false) is smoothed too, because a step from the
	 * last sample to silence is the same click. A re-anchor knows it is pausing on the tick
	 * it delivers its last frame, so that frame is faded out ({@link #fadeOut}). An underrun
	 * has no lookahead (the last real frame went out a tick earlier), so the first silent
	 * frame instead decays from the last delivered sample to zero over
	 * {@link #DECAY_TAIL_SAMPLES} ({@link #decayTail}).</p>
	 */
	private static final int RESUME_RAMP_FRAMES = 2;
	/** Length of the underrun decay tail: 5&nbsp;ms at 48&nbsp;kHz. */
	static final int DECAY_TAIL_SAMPLES = 240;

	private static final long DROPS_WARN_PER_STATS_INTERVAL = 250L;

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

	private final AbstractFfmpegFeed feed;
	private final FeedStats stats;

	FeedDeliveryLoop(AbstractFfmpegFeed feed) {
		this.feed = feed;
		this.stats = feed.stats;
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
	void runDeliveryLoop() {
		// Shared zero frame; sinks must not mutate delivered frames.
		short[] silence = new short[FRAME_SAMPLES];
		long next = System.nanoTime();
		long lastStats = next;
		long lastOccupancy = next;
		// Wall-clock baseline paired with lastOccupancy for the host/VM freeze detector.
		long lastOccWallMs = System.currentTimeMillis();
		// The "feed drops high" WARN watches ONLY overflow drops (see below), so its
		// 30 s baseline is the overflow counter, not the all-causes drops total.
		long lastStatsOverflowDrops = stats.overflowDrops.get();
		// Per-interval CSV diagnostics baselines (delivery thread only; FeedDiagnostics
		// off => never read). Seeded to the current counters so the first emitted row
		// shows a one-interval delta, not a since-start burst.
		long lastDiag = next;
		long diagFramesRead = stats.framesRead.get();
		long diagDelivered = stats.framesDelivered.get();
		long diagSilence = stats.silenceFrames.get();
		long diagDrops = stats.drops.get();
		long diagOverflow = stats.overflowDrops.get();
		long diagUnderruns = stats.underruns.get();
		long diagReanchors = stats.reanchors.get();
		long diagReconnects = stats.reconnects.get();
		// Playout state machine: starts pre-rolling (not yet playing). The
		// hysteresis trim needs no persistent latch - it engages, drains to target
		// and disengages within a single tick, so the deadband is simply "drop
		// nothing until the buffer climbs back to high-water again".
		boolean playing = false;
		feed.deliveryPlaying = false;
		// Frames of resume gain-ramp still to apply (sow-005 Phase A, item 4). Armed to
		// RESUME_RAMP_FRAMES on every playing false->true transition (cold start, post
		// underrun, post re-anchor); counts down as each ramped frame is delivered, so
		// the ramp fires ONLY on a resume and never in steady state.
		int rampFramesLeft = 0;
		// Last sample delivered, so an underrun can decay from it instead of stepping to zero.
		short lastSample = 0;
		// Consecutive PLAYING ticks the buffer has sat below the re-anchor low water
		// (sow-005 Phase 4). Reset whenever occupancy recovers, on an underrun, and
		// after a re-anchor fires, so only a SUSTAINED drain trips the re-anchor.
		long belowLowTicks = 0L;
		// sow-005 Phase B: pitch-preserving time-stretch (TEMPO_MATCH) delivery-thread
		// state. The stretcher is delivery-thread-private (no locks; mirrors
		// applyResumeRamp) and only exists in TEMPO_MATCH mode; in REBUFFER it stays null
		// and the loop runs the exact Phase-A passthrough. stretchFailed latches a runtime
		// DSP exception into a permanent passthrough fallback so audio never breaks.
		WsolaTimeStretcher stretcher = feed.tempoMatchEnabled ? new WsolaTimeStretcher() : null;
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
		feed.currentStretchFactor = feed.tempoMatchEnabled ? stretchFactor : 1.0;
		while (feed.running && !Thread.currentThread().isInterrupted()) {
			short[] frame = silence;
			long trimmed = 0L;
			// >= 0 only on the tick a controlled re-anchor fired: the buffered depth to
			// log/record for it, outside bufferLock.
			long reanchorBuffered = -1L;
			// True on the tick playout runs dry (an underrun), for the decay tail.
			boolean ranDry = false;

			if (!playing) {
				// PRE-ROLL: hold at silence, draining nothing, until the buffer has
				// filled to prerollFrames; then start playout on the first real frame
				// and reset the pace clock so there is no catch-up burst.
				synchronized (feed.bufferLock) {
					// Normally hold until prerollFrames are buffered. sow-012: but once the input has
					// ENDED (a finite repeat-off source shorter than the preroll cushion), play whatever
					// it managed to buffer rather than waiting for a preroll that will never arrive - else
					// a sub-preroll file/VOD would drain to "ended" having played only silence.
					if (feed.buffer.size() >= feed.prerollFrames || (feed.inputEnded && !feed.buffer.isEmpty())) {
						frame = feed.buffer.pollFirst();
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
					// sow-005 Phase B: discard any stale samples the stretcher's private ring
					// held from before this preroll (a prior underrun/re-anchor left up to
					// ~45 ms of pre-outage audio behind its cursor), so the stretch stream
					// resumes from the freshly-prerolled frames instead of splicing stale audio
					// before the new content. Harmless on a cold start (the ring is empty).
					if (stretcher != null) {
						stretcher.reset();
					}
					if (stretcher != null && !stretchFailed) {
						// The stretcher's first output after a reset fades in from zero (its Hann
						// window). Delivering this frame raw first meant audio, then a drop back
						// toward silence: a click on every resume. Hand it back so the stretched
						// stream starts with it, and hold silence for this one tick.
						synchronized (feed.bufferLock) {
							feed.buffer.addFirst(frame);
						}
						frame = silence;
						stats.silenceFrames.incrementAndGet();
					} else {
						stats.framesDelivered.incrementAndGet();
					}
				} else {
					frame = silence;
					stats.silenceFrames.incrementAndGet();
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
						synchronized (feed.bufferLock) {
							int available = feed.buffer.size();
							while (polledCount < needFrames) {
								short[] f = feed.buffer.pollFirst();
								if (f == null) {
									break;
								}
								pollStage[polledCount++] = f;
							}
							// High-water hysteresis trim, unchanged: once the buffer has
							// crept to high-water, drain the excess oldest frames back to
							// target in one bounded catch-up (still caps latency on bursts).
							if (available >= feed.highWaterFrames) {
								int excess = feed.buffer.size() - feed.targetFrames;
								for (int i = 0; i < excess; i++) {
									feed.buffer.pollFirst();
								}
								if (excess > 0) {
									trimmed = excess;
								}
							}
							postPollSize = feed.buffer.size();
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
							feed.stretchRingSamples = stretcher.bufferedSamplesAhead();
							stats.framesDelivered.incrementAndGet();
							// SUSTAINED-DRAIN RE-ANCHOR (sow-005 Phase 4) keyed off jitter
							// buffer occupancy exactly as the passthrough path: with the
							// stretch holding content-rate this should almost never fire in
							// steady state, remaining as the hard-reset backstop for a real
							// outage.
							if (feed.reanchorSustainFrames > 0) {
								if (postPollSize < feed.reanchorLowWaterFrames) {
									if (++belowLowTicks >= feed.reanchorSustainFrames) {
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
							stats.silenceFrames.incrementAndGet();
							playing = false;
							ranDry = true;
							stats.underruns.incrementAndGet();
							stats.recordGlitchEvent();
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
						feed.stretchRuntimeFailed = true;
						long stretchTrim = trimmed;
						trimmed = 0L;
						synchronized (feed.bufferLock) {
							for (int i = pollStage.length - 1; i >= 0; i--) {
								if (pollStage[i] != null) {
									feed.buffer.addFirst(pollStage[i]);
									pollStage[i] = null;
								}
							}
						}
						stats.recordDrops(stretchTrim, DropCause.CATCH_UP);
						JockeyRadioMod.LOGGER.error(
								"[jockey] time-stretch failed station={}; falling back to passthrough for this feed",
								feed.stationKey, t);
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
				synchronized (feed.bufferLock) {
					int available = feed.buffer.size();
					frame = feed.buffer.pollFirst();
					if (frame != null && available >= feed.highWaterFrames) {
						int excess = feed.buffer.size() - feed.targetFrames;
						for (int i = 0; i < excess; i++) {
							feed.buffer.pollFirst();
						}
						if (excess > 0) {
							trimmed = excess;
						}
					}
					postPollSize = feed.buffer.size();
				}
				if (frame != null) {
					stats.framesDelivered.incrementAndGet();
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
					if (feed.reanchorSustainFrames > 0) {
						if (postPollSize < feed.reanchorLowWaterFrames) {
							if (++belowLowTicks >= feed.reanchorSustainFrames) {
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
					stats.silenceFrames.incrementAndGet();
					playing = false;
					ranDry = true;
					stats.underruns.incrementAndGet();
					// An underrun (playout ran dry -> silence + re-roll) is an audible
					// discontinuity too; record it as one glitch event.
					stats.recordGlitchEvent();
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
				stats.recordDrops(trimmed, DropCause.CATCH_UP);
			}
			// Record a controlled re-anchor outside bufferLock (recordGlitchEvent takes
			// only the window monitor): it is ONE deliberate bounded discontinuity - the
			// pre-roll pause that rebuilds the cushion - counted distinctly from an
			// underrun and logged as its own line, no frames dropped (the retained frames
			// stay and playout resumes from them once refilled to prerollFrames).
			if (reanchorBuffered >= 0L) {
				stats.reanchors.incrementAndGet();
				stats.recordGlitchEvent();
				JockeyRadioMod.LOGGER.info(
						"[jockey] feed reanchor station={} buffered={} lowWaterFrames={} sustainFrames={}; "
								+ "sustained drain; re-prerolling to rebuild target cushion",
						feed.stationKey, reanchorBuffered, feed.reanchorLowWaterFrames, feed.reanchorSustainFrames);
			}

			// CLICK-FREE PAUSE EDGE: decay an underrun from the last sample, or fade out the
			// frame a re-anchor pauses after (see RESUME_RAMP_FRAMES).
			if (ranDry && lastSample != 0) {
				frame = decayTail(lastSample);
			} else if (reanchorBuffered >= 0L) {
				frame = fadeOut(frame);
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
			feed.deliveryPlaying = playing;

			for (AudioSink sink : feed.sinks) {
				try {
					sink.accept(frame);
				} catch (Throwable t) {
					JockeyRadioMod.LOGGER.error("[jockey] sink accept failed station={}", feed.stationKey, t);
				}
			}
			lastSample = frame[frame.length - 1];

			long now = System.nanoTime();
			if (now - lastOccupancy >= 1_000_000_000L) {
				// One buffered-occupancy sample per second for the /jockey diag
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
					stats.hostFreezeEvents.incrementAndGet();
					stats.hostFreezeMillis.addAndGet(freezeMs);
					JockeyRadioMod.LOGGER.warn(
							"[jockey] host stall station={} froze~{}ms (monotonic clock paused vs wall clock: the OS/VM "
									+ "suspended the process, NOT the mod). A client playout buffer shallower than "
									+ "~{}ms will hitch here.",
							feed.stationKey, freezeMs, freezeMs);
				}
				int occBuffered;
				synchronized (feed.bufferLock) {
					occBuffered = feed.buffer.size();
				}
				stats.recordOccupancy(occBuffered);
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
					double errSec = (feed.targetFrames - occBuffered) / FRAMES_PER_SECOND;
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
					feed.currentStretchFactor = stretchFactor;
				}
				// PER-INTERVAL CSV DIAGNOSTICS (sow testing aid). Off by default:
				// FeedDiagnostics.get() is null and this is skipped entirely. When on,
				// emit one row of live state + delta counters, reusing occBuffered so no
				// extra bufferLock read; the actual file write is async on the diag IO
				// thread, so nothing here touches disk on the pacing path.
				FeedDiagnostics diag = FeedDiagnostics.get();
				if (diag != null && now - lastDiag >= diag.intervalNanos()) {
					long nowMs = System.currentTimeMillis();
					long fr = stats.framesRead.get();
					long de = stats.framesDelivered.get();
					long si = stats.silenceFrames.get();
					long dr = stats.drops.get();
					long ov = stats.overflowDrops.get();
					long un = stats.underruns.get();
					long ra = stats.reanchors.get();
					long rc = stats.reconnects.get();
					double elapsedSec = (now - lastDiag) / 1_000_000_000.0;
					double expectedFrames = elapsedSec * FRAMES_PER_SECOND;
					double deliverRt = expectedFrames > 0.0 ? (de - diagDelivered) / expectedFrames : 0.0;
					double ingestRt = expectedFrames > 0.0 ? (fr - diagFramesRead) / expectedFrames : 0.0;
					double stretch = feed.currentStretchFactor;
					double sourceRate = stretch > 0.0 ? 1.0 / stretch : 1.0;
					double fillPct = feed.targetFrames > 0 ? 100.0 * occBuffered / feed.targetFrames : 0.0;
					diag.record(String.format(Locale.ROOT,
							"%d,%s,%s,%s,%s,%s,%b,%d,%d,%d,%d,%.1f,%d,%d,%d,%d,%d,%d,%d,%d,%.3f,%.3f,%.4f,%.4f,%d",
							nowMs, FeedDiagnostics.TIME_FORMAT.format(Instant.ofEpochMilli(nowMs)),
							feed.stationKey, feed.getClass().getSimpleName(), feed.playoutState(), feed.connectionState,
							feed.deliveryPlaying, occBuffered, occBuffered * FRAME_MILLIS,
							feed.targetFrames * FRAME_MILLIS, feed.maxBufferFrames * FRAME_MILLIS, fillPct,
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
				synchronized (feed.bufferLock) {
					buffered = feed.buffer.size();
				}
				if (RadioConfig.get().feedStatsLogging) {
					JockeyRadioMod.LOGGER.info(
							"[jockey] feed stats station={} frames={} delivered={} silence={} drops={} reconnects={} "
									+ "underruns={} buffered={} latencyMs={} targetMs={} hostFreezes={} hostFreezeMs={}",
							feed.stationKey, stats.framesRead.get(), stats.framesDelivered.get(), stats.silenceFrames.get(),
							stats.drops.get(), stats.reconnects.get(), stats.underruns.get(), buffered,
							buffered * FRAME_MILLIS, feed.targetFrames * FRAME_MILLIS,
							stats.hostFreezeEvents.get(), stats.hostFreezeMillis.get());
				}
				// Gate ONLY on hard-cap OVERFLOW drops: those are the frames raising
				// maxBufferSeconds actually bounds. Hysteresis catch-up and stall-resync
				// drops are a bounded latency correction the maxBuffer knob does not
				// address (a single one can legitimately shed maxBufferFrames-targetFrames
				// frames), so folding them in here would misattribute a normal correction
				// to an overflow and push the operator at the wrong knob (sow-005 Phase 1).
				long totalOverflowDrops = stats.overflowDrops.get();
				long overflowDelta = totalOverflowDrops - lastStatsOverflowDrops;
				lastStatsOverflowDrops = totalOverflowDrops;
				if (overflowDelta > DROPS_WARN_PER_STATS_INTERVAL) {
					JockeyRadioMod.LOGGER.warn(
							"[jockey] feed drops high station={} overflowLast30s={} maxBufferFrames={}; upstream "
									+ "bursts exceed the jitter buffer's hard cap; raise 'maxBufferSeconds' in "
									+ "jockey_radio.json",
							feed.stationKey, overflowDelta, feed.maxBufferFrames);
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
					synchronized (feed.bufferLock) {
						for (long i = 0; i < skipped && feed.buffer.size() > feed.targetFrames; i++) {
							feed.buffer.pollFirst();
							dropped++;
						}
					}
					stats.recordDrops(dropped, DropCause.CATCH_UP);
				}
			}
		}
	}

	/** Clamps the servo integrator to the +/- stretch budget (anti-windup). */
	private double clampStretch(double v) {
		return Math.max(-feed.maxStretchFraction, Math.min(feed.maxStretchFraction, v));
	}

	/** Hard-clamps the output stretch factor to {@code 1 +/- maxStretchFraction}. */
	private double clampFactor(double v) {
		return Math.max(1.0 - feed.maxStretchFraction, Math.min(1.0 + feed.maxStretchFraction, v));
	}

	/**
	 * A fresh, gain-ramped copy of {@code src} for the resume envelope (sow-005
	 * Phase A, item 4). Gain rises linearly from ~0 to 1.0 across the whole
	 * {@code RESUME_RAMP_FRAMES}-frame span: the frame currently being delivered is
	 * index {@code RESUME_RAMP_FRAMES - framesLeft} (0-based), and within it each of
	 * the {@link AbstractFfmpegFeed#FRAME_SAMPLES} s16 samples is scaled by
	 * {@code (globalSampleIndex + 1) / (RESUME_RAMP_FRAMES * FRAME_SAMPLES)}, so the
	 * very last ramp sample reaches full gain. Never mutates {@code src} (a sink may
	 * retain the delivered array); allocates one frame, only on ramp ticks.
	 */
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

	/**
	 * A copy of {@code src} faded linearly from full gain to zero across the frame, for the last
	 * frame before a re-anchor's pause. Never mutates {@code src}: a sink may retain it.
	 */
	static short[] fadeOut(short[] src) {
		short[] out = new short[src.length];
		for (int i = 0; i < src.length; i++) {
			double gain = (double) (src.length - 1 - i) / (double) (src.length - 1);
			out[i] = (short) Math.round(src[i] * gain);
		}
		return out;
	}

	/**
	 * The first silent frame after an underrun: {@code last} (the final sample delivered)
	 * decaying linearly to zero over {@link #DECAY_TAIL_SAMPLES}, then silence. Removes the
	 * step from a non-zero sample straight to zero.
	 */
	static short[] decayTail(short last) {
		short[] out = new short[FRAME_SAMPLES];
		for (int i = 0; i < DECAY_TAIL_SAMPLES; i++) {
			double gain = (double) (DECAY_TAIL_SAMPLES - 1 - i) / (double) DECAY_TAIL_SAMPLES;
			out[i] = (short) Math.round(last * gain);
		}
		return out;
	}

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
	void checkIngestStall() {
		if (!feed.running) {
			return;
		}
		Process p = feed.process;
		long last = feed.lastIngestFrameNanos;
		if (p == null || last == 0L || !p.isAlive()) {
			return;
		}
		long stalledNanos = System.nanoTime() - last;
		if (stalledNanos < INGEST_STALL_NANOS) {
			return;
		}
		JockeyRadioMod.LOGGER.warn(
				"[jockey] feed ingest stalled station={}; no audio from ffmpeg for {}s while the process is still "
						+ "alive; restarting the connection",
				feed.stationKey, TimeUnit.NANOSECONDS.toSeconds(stalledNanos));
		// Non-blocking: this runs on the real-time delivery thread, which must never park on a
		// grace wait (that would starve every listener mid-playout). The reaper sends SIGTERM and
		// escalates to SIGKILL on its own timer; the child's death closes its stdout, which ends
		// the ingest thread's read and rides the reconnect ladder. lastIngestFrameNanos is left
		// as it is, so if the child were somehow still alive at the next tick the watchdog would
		// fire again; the next connection resets it.
		ProcessReaper.terminate(p);
	}
}
