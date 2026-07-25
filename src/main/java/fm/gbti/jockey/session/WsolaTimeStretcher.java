package fm.gbti.jockey.session;

import java.util.Arrays;

/**
 * Pitch-preserving time-stretch (WSOLA / windowed overlap-add) for the delivery
 * loop's smoothing mix (sow-005 Phase B, TEMPO_MATCH). Consumes 960-sample
 * (20&nbsp;ms) s16 mono frames dequeued from the jitter buffer and produces
 * EXACTLY one 960-sample output frame per {@link #produceFrame(double)} call,
 * time-stretched by a caller-supplied factor {@code s = output/input}.
 *
 * <p>Why this exists: the SavePoint trunk delivers CONTENT at ~0.974x realtime,
 * but SVC needs real-time (50&nbsp;fps) packets. Without correction the jitter
 * buffer slowly drains and eventually rebuffers. Stretching the content by the
 * matching factor (~1.026) makes content consumed per wall-second equal content
 * produced per wall-second, so the buffer occupancy is held CONSTANT with no
 * rebuffer pause and at fixed latency. Because we overlap-add real waveform
 * segments (never resample) the PITCH is preserved - a &lt;=3% stretch is
 * inaudible for music.</p>
 *
 * <h2>Algorithm (streaming WSOLA)</h2>
 * <ul>
 *   <li>Fixed synthesis hop {@code HS} = half the window {@code L} (50% overlap).
 *       A single Hann window at 50% overlap sums to unity, so overlap-add
 *       preserves amplitude with no synthesis-side normalization.</li>
 *   <li>Analysis hop {@code Ha = HS / s}: the input read cursor advances by
 *       {@code Ha} per synthesis step, so input-consumed / output-produced =
 *       {@code Ha/HS = 1/s}. For {@code s>1} we consume slightly LESS input than
 *       we emit - exactly the deficit correction.</li>
 *   <li>Per step a short cross-correlation search (&plusmn;{@code SEARCH} samples)
 *       picks the input segment near the analysis cursor that best continues the
 *       previously placed segment (the WSOLA "natural continuation" template),
 *       so the overlap-add cross-fade is phase-aligned - no clicks, no metallic
 *       warble.</li>
 *   <li>Two synthesis steps ({@code 2 * HS = 960}) yield exactly one output
 *       frame. The overlap accumulator carries state across steps AND across
 *       calls, so the stretched stream is continuous frame to frame.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * This class is NOT thread-safe and holds NO locks. It is owned exclusively by
 * the single delivery-thread and every method runs on delivery-thread locals
 * only (mirroring {@code applyResumeRamp}), so it never touches {@code bufferLock}
 * and never runs on the server thread. Frames are handed to {@link #append} after
 * they have been dequeued under {@code bufferLock}; the DSP math here runs
 * entirely off that lock. All buffers are reused; only the returned output frame
 * is freshly allocated each call (a sink may retain it, so it must be private).
 */
final class WsolaTimeStretcher {

	/** Samples per delivered frame (20 ms @ 48 kHz mono). Matches {@code FRAME_SAMPLES}. */
	private static final int FRAME = 960;
	/** Overlap-add window length (20 ms). */
	private static final int L = 960;
	/** Synthesis hop = 50% overlap; a single Hann at this hop sums to unity. */
	private static final int HS = L / 2;
	/**
	 * Cross-correlation search half-range (~10 ms, one 100&nbsp;Hz period) for the
	 * phase-aligned continuation. Wide enough that the search can lock a full low-bass
	 * period (a ~4 ms range covers well under one cycle of bass, giving a broad,
	 * weakly-peaked surface and residual low-frequency phasiness at the joins). The
	 * normalized (shape-based) score below keeps a wide range from snapping onto a
	 * distant loud region.
	 */
	private static final int SEARCH = 480;
	/** Cross-correlation template length (~4 ms); kept modest to bound per-tick cost. */
	private static final int CORR = 200;
	/**
	 * Below this |s-1| the unity fast path runs: same overlap-add machinery but with
	 * NO similarity search and an exact integer hop, so no search artifact is incurred
	 * when the servo sits at ~1.0 (e.g. a real-time RTMP push). The 50%-overlap Hann
	 * pair sums to unity, so the fast path reconstructs the input to within &lt;1 LSB
	 * (a near-bit-exact copy) while staying seamless across a factor transition.
	 */
	private static final double UNITY_EPS = 1e-3;
	/**
	 * Input samples that must sit ahead of the analysis cursor before a full
	 * output frame can be synthesized without reading past the buffered input.
	 * Covers two analysis hops + the search range + one window, with margin.
	 */
	private static final int REQUIRED_AHEAD = 2 * L + SEARCH + 32;
	/** Compact the input buffer once the analysis cursor passes this index. */
	private static final int COMPACT_THRESHOLD = 2048;
	/** Cap on frames pulled per tick to bound per-tick work (cold-start fill). */
	private static final int MAX_POLL_FRAMES = 8;

	/** Precomputed Hann window (length {@code L}). */
	private final float[] hann = new float[L];
	/** Overlap-add accumulator carrying partial output across steps/calls. */
	private final float[] ola = new float[L];
	/** Linear input sample buffer; valid samples are {@code in[0..inLen)}. */
	private float[] in = new float[16384];
	private int inLen;

	/** Fractional analysis read cursor (samples). */
	private double analysisPos;
	/** Start of the "natural continuation" template = previous chosen + HS. */
	private int naturalPos;
	/** False until the first segment is placed (no search on the very first). */
	private boolean started;

	WsolaTimeStretcher() {
		for (int i = 0; i < L; i++) {
			hann[i] = (float) (0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / L)));
		}
	}

	/** Appends one 960-sample input frame to the DSP ring (off-lock; delivery thread only). */
	void append(short[] frame) {
		ensureCapacity(inLen + frame.length);
		for (int i = 0; i < frame.length; i++) {
			in[inLen + i] = frame[i];
		}
		inLen += frame.length;
	}

	/**
	 * Whether enough input is buffered ahead of the analysis cursor to synthesize
	 * one full output frame. When false the caller treats it as an underrun
	 * exactly as the passthrough path treats an empty jitter buffer.
	 */
	boolean canProduce() {
		return inLen - (int) Math.floor(analysisPos) >= REQUIRED_AHEAD;
	}

	/**
	 * How many 960-sample frames to pull from the jitter buffer this tick to keep
	 * the DSP input fed to {@link #REQUIRED_AHEAD}. Returns 0 when already fed
	 * (steady state alternates 0/1 since we consume slightly under one frame per
	 * output frame), capped at {@link #MAX_POLL_FRAMES}.
	 */
	int framesNeededFor() {
		int ahead = inLen - (int) Math.floor(analysisPos);
		int need = REQUIRED_AHEAD - ahead;
		if (need <= 0) {
			return 0;
		}
		int frames = (need + FRAME - 1) / FRAME;
		return Math.min(MAX_POLL_FRAMES, frames);
	}

	/**
	 * Samples currently buffered ahead of the analysis cursor: the DSP ring depth that
	 * sits BEHIND the jitter buffer and adds a small fixed latency the jitter-buffer
	 * {@code latencyMs} does not include. Exposed for the TEMPO_MATCH diag. Never
	 * negative. Delivery-thread only, like every other method here.
	 */
	int bufferedSamplesAhead() {
		int ahead = inLen - (int) Math.floor(analysisPos);
		return ahead > 0 ? ahead : 0;
	}

	/**
	 * Produces exactly one 960-sample output frame time-stretched by {@code s}
	 * ({@code output/input}). Must only be called when {@link #canProduce()} is
	 * true. Every input read is bounds-clamped so a slightly-off cursor can never
	 * throw; the returned array is freshly allocated (safe for a sink to retain).
	 */
	short[] produceFrame(double s) {
		if (s < 0.5) {
			s = 0.5;
		} else if (s > 2.0) {
			s = 2.0;
		}
		// UNITY FAST PATH: when the servo sits at ~1.0 there is nothing to stretch, so
		// skip the similarity search entirely and take an exact integer hop. Because a
		// 50%-overlap Hann pair sums to unity, the overlap-add then reconstructs the
		// input to within <1 LSB (near-bit-exact) with zero search artifact. We keep the
		// overlap-add machinery (rather than a raw verbatim copy) precisely so entering
		// AND leaving unity stays click-free: the pending ola tail is a windowed
		// fade-out that a verbatim full-amplitude copy would discontinuously butt
		// against - the cross-fade absorbs it.
		boolean unity = Math.abs(s - 1.0) < UNITY_EPS;
		double ha = unity ? HS : HS / s;
		short[] out = new short[FRAME];
		int outPos = 0;
		for (int step = 0; step < 2; step++) {
			int chosen;
			if (!started || unity) {
				chosen = (int) Math.floor(analysisPos);
				started = true;
			} else {
				chosen = bestOffset((int) Math.floor(analysisPos));
			}
			chosen = clampSeg(chosen);
			// Overlap-add the Hann-windowed input segment.
			for (int i = 0; i < L; i++) {
				ola[i] += in[chosen + i] * hann[i];
			}
			// Emit the now-complete first HS samples.
			for (int i = 0; i < HS; i++) {
				out[outPos++] = clip(ola[i]);
			}
			// Shift the accumulator down by HS and zero the freed tail.
			System.arraycopy(ola, HS, ola, 0, L - HS);
			Arrays.fill(ola, L - HS, L, 0f);
			// Advance: the natural continuation is this segment + HS; the analysis
			// cursor advances by the (fractional) analysis hop.
			naturalPos = chosen + HS;
			analysisPos += ha;
		}
		compactIfNeeded();
		return out;
	}

	/** Discards state and buffered input (used on a clean disable/reset). */
	void reset() {
		inLen = 0;
		analysisPos = 0.0;
		naturalPos = 0;
		started = false;
		Arrays.fill(ola, 0f);
	}

	/**
	 * The input segment start near {@code center} whose leading {@code CORR}
	 * samples best cross-correlate with the natural-continuation template at
	 * {@code naturalPos}. Ties (and a non-positive best, e.g. silence) prefer the
	 * offset closest to {@code center} so a quiet passage stays at nominal hop.
	 */
	private int bestOffset(int center) {
		int best = center;
		double bestScore = -Double.MAX_VALUE;
		for (int cand = center - SEARCH; cand <= center + SEARCH; cand++) {
			if (cand < 0 || cand + L > inLen) {
				continue;
			}
			double dot = 0.0;
			double energy = 0.0;
			for (int k = 0; k < CORR; k++) {
				int a = cand + k;
				int b = naturalPos + k;
				if (a >= inLen || b >= inLen || b < 0) {
					break;
				}
				double va = in[a];
				dot += va * (double) in[b];
				energy += va * va;
			}
			// NORMALIZED cross-correlation (cosine similarity): divide the raw dot by the
			// candidate-segment energy so the search maximizes waveform SHAPE, not
			// amplitude. A RAW correlation is biased toward the loudest positively-
			// correlated candidate, so a transient (kick/snare) inside the search window
			// snaps `best` onto the loud onset -> that attack is duplicated into two hops
			// (an audible flam). The template energy at naturalPos is constant across
			// candidates, so it does not affect the argmax and is omitted.
			double score = energy > 0.0 ? dot / Math.sqrt(energy) : 0.0;
			if (score > bestScore
					|| (score == bestScore && Math.abs(cand - center) < Math.abs(best - center))) {
				bestScore = score;
				best = cand;
			}
		}
		if (bestScore <= 0.0) {
			return center;
		}
		return best;
	}

	/** Clamps a segment start so {@code [chosen, chosen+L)} stays inside the input. */
	private int clampSeg(int x) {
		int max = inLen - L;
		if (max < 0) {
			max = 0;
		}
		if (x < 0) {
			return 0;
		}
		if (x > max) {
			return max;
		}
		return x;
	}

	/** Slides consumed samples out of the front so the buffer stays bounded. */
	private void compactIfNeeded() {
		int base = (int) Math.floor(analysisPos);
		if (base <= COMPACT_THRESHOLD) {
			return;
		}
		int keep = base - SEARCH - 8;
		if (keep <= 0) {
			return;
		}
		if (keep > inLen) {
			keep = inLen;
		}
		System.arraycopy(in, keep, in, 0, inLen - keep);
		inLen -= keep;
		analysisPos -= keep;
		naturalPos -= keep;
		if (naturalPos < 0) {
			naturalPos = 0;
		}
	}

	private void ensureCapacity(int n) {
		if (n <= in.length) {
			return;
		}
		int cap = in.length;
		while (cap < n) {
			cap <<= 1;
		}
		in = Arrays.copyOf(in, cap);
	}

	private static short clip(float v) {
		int i = Math.round(v);
		if (i > Short.MAX_VALUE) {
			return Short.MAX_VALUE;
		}
		if (i < Short.MIN_VALUE) {
			return Short.MIN_VALUE;
		}
		return (short) i;
	}
}
