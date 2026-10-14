package fm.gbti.jockey.config;

/**
 * Defaults and bounds for {@link RadioConfig}'s keys, shared by the field initializers there and
 * the load-time clamps in {@link ConfigNormalizer}.
 */
final class ConfigLimits {

	private ConfigLimits() {
	}

	static final String FILE_NAME = "jockey_radio.json";

	/** Frames per second at 48 kHz mono in 960-sample (20 ms) frames. */
	static final int FRAMES_PER_SECOND = 50;
	static final double DEFAULT_TARGET_BUFFER_SECONDS = 3.0;
	static final double DEFAULT_MAX_BUFFER_SECONDS = 12.0;
	static final double MIN_TARGET_BUFFER_SECONDS = 0.5;
	/**
	 * sow-005 Phase A: the target ceiling is raised to 90&nbsp;s so an operator can run
	 * the DEEP-BUFFER profile (start behind the live edge + a deep target so the
	 * ~2.6% source deficit is absorbed gaplessly). The shipped default stays 3&nbsp;s;
	 * this only widens the allowed range. Dev guidance: no benefit past ~90&nbsp;s depth.
	 */
	static final double MAX_TARGET_BUFFER_SECONDS = 90.0;
	/** Raised to 120&nbsp;s (sow-005 Phase A) so the max cap can keep ~2x headroom over a deep 90 s target. */
	static final double MAX_MAX_BUFFER_SECONDS = 120.0;
	static final double MIN_PREROLL_SECONDS = 0.2;
	/**
	 * sow-005 Phase 4: the hysteresis high-water trim (engage point ~2x target in
	 * {@code AbstractFfmpegFeed}) doubles as the sliding-window LAG CAP - it drains a
	 * burst-inflated buffer back to the target once occupancy creeps to high-water. That
	 * deadband only exists when the hard cap sits well above the target. Raising
	 * {@code targetBufferSeconds} toward {@code maxBufferSeconds} (the SOW's deeper-target
	 * guidance) collapses it: high-water pins to {@code maxBufferFrames} and the trim
	 * excess shrinks to ~0, so latency can sit at the full max with no bounded catch-up.
	 * We warn once when the cap gives less than this multiple of target of headroom so the
	 * operator raises {@code maxBufferSeconds} alongside the target (the recommendation is
	 * ~2x, matching the trim's engage point).
	 */
	static final double LAG_CAP_HEADROOM_WARN_MULTIPLIER = 1.5;
	static final double LAG_CAP_HEADROOM_RECOMMENDED_MULTIPLIER = 2.0;
	/** The pre-jitter default ring size; an explicit non-600 value migrates. */
	static final int LEGACY_DEFAULT_BUFFER_FRAMES = 600;

	// --- sow-005 Phase A: HLS start-behind (deep-buffer fast fill) ---
	/**
	 * Default HLS start-behind depth in segments. {@code 15} = ~30&nbsp;s at 2&nbsp;s
	 * segments - a MODERATE default (WSOLA in a later phase will remove the drain and
	 * let latency drop). {@code 0} keeps ffmpeg's default live-edge start.
	 */
	static final int DEFAULT_HLS_START_BEHIND_SEGMENTS = 15;
	/**
	 * Max HLS start-behind depth. The SavePoint trunk retains 120&nbsp;s (60x2&nbsp;s)
	 * with segment deletion on. Parking {@code -live_start_index -N} puts ffmpeg's start
	 * segment {@code 2N} seconds behind the live edge, so it falls out of the retained
	 * window in {@code 120 - 2N} seconds; requiring the SOW's &gt;=30&nbsp;s deletion
	 * margin gives {@code 120 - 2N >= 30}, i.e. {@code N <= 45}. A higher ceiling (the
	 * former 55, ~10&nbsp;s margin) let a small connect delay push the requested start
	 * segment out of the window -&gt; HTTP 404 at connect, the exact failure this clamp
	 * exists to prevent. Default {@value #DEFAULT_HLS_START_BEHIND_SEGMENTS} is well
	 * inside this.
	 */
	static final int MAX_HLS_START_BEHIND_SEGMENTS = 45;

	// --- sow-005 Phase 4: buffer-preserving reconnect + sustained-drain re-anchor ---
	/** Reuse the jitter buffer across ffmpeg respawns by default (the D-061 win). */
	static final boolean DEFAULT_RECONNECT_PRESERVE_BUFFER = true;
	/**
	 * Default re-anchor low watermark (seconds of buffered audio). Sits below the
	 * default target so it only trips on a genuine sustained drain, not jitter.
	 */
	static final double DEFAULT_REANCHOR_LOW_WATERMARK_SECONDS = 1.0;
	static final double MIN_REANCHOR_LOW_WATERMARK_SECONDS = 0.2;
	/** Default sustained-drain window before ONE controlled re-anchor fires. */
	static final double DEFAULT_REANCHOR_SUSTAIN_SECONDS = 8.0;
	static final double MIN_REANCHOR_SUSTAIN_SECONDS = 1.0;
	static final double MAX_REANCHOR_SUSTAIN_SECONDS = 120.0;

	// --- sow-005 Phase B: pitch-preserving time-stretch (TEMPO_MATCH) ---
	/**
	 * Default smoothing mode. {@code "tempo_match"} runs the WSOLA time-stretch
	 * stage so a deep buffer against the permanent ~2.6% source deficit never drains
	 * (zero rebuffer pauses at constant latency); {@code "rebuffer"} disables the
	 * stretch entirely and keeps the Phase-A passthrough (the safe rollback).
	 */
	static final String DEFAULT_SMOOTHING_MODE = "tempo_match";
	/** Default max time-stretch magnitude (percent); a &lt;=3% stretch is inaudible for music. */
	static final double DEFAULT_MAX_STRETCH_PERCENT = 3.0;
	/** Hard ceiling on the configurable stretch magnitude (percent). */
	static final double MAX_MAX_STRETCH_PERCENT = 6.0;

	/** Default radio Opus bitrate (bits/s): music-grade 128 kbit/s mono. */
	static final int DEFAULT_OPUS_BITRATE = 128_000;
	/** Lower bound for a sane music bitrate. */
	static final int MIN_OPUS_BITRATE = 24_000;
	/**
	 * Upper bound, set by what the ENCODER will actually honour. Concentus hard-caps
	 * {@code setBitrate} at 300 kbit/s mono, so a higher value was accepted here, passed
	 * on, and silently reduced - the knob did nothing above 300 kbit/s and no log line
	 * said so. Clamping at the real ceiling makes the accepted range truthful. (The
	 * MTU-derived ceiling is higher, ~510 kbit/s, but it is never the binding one.)
	 */
	static final int MAX_OPUS_BITRATE = 300_000;

	/**
	 * Default Opus encode complexity: mid-scale, so the pure-Java encoder stays
	 * well under the 20 ms per-frame budget on a slow host while VBR preserves
	 * music quality. 0 = fastest/lowest, 10 = slowest/highest.
	 */
	static final int DEFAULT_OPUS_COMPLEXITY = 5;
	/** Lowest Opus complexity (fastest encode). */
	static final int MIN_OPUS_COMPLEXITY = 0;
	/** Highest Opus complexity (slowest encode). */
	static final int MAX_OPUS_COMPLEXITY = 10;

	/** Default Opus FEC expected packet-loss percent. 0 = FEC OFF (pristine CELT-only music). */
	static final int DEFAULT_OPUS_FEC_PACKET_LOSS = 0;
	static final int MIN_OPUS_FEC_PACKET_LOSS = 0;
	static final int MAX_OPUS_FEC_PACKET_LOSS = 100;

	/** Default feed-diagnostics sample interval (seconds). */
	static final double DEFAULT_FEED_DIAGNOSTICS_INTERVAL_SECONDS = 1.0;
}
