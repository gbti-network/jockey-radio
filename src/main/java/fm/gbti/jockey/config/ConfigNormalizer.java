package fm.gbti.jockey.config;

import fm.gbti.jockey.JockeyRadioMod;

import java.util.Locale;

import static fm.gbti.jockey.config.ConfigLimits.*;

/**
 * The load-time clamps for {@link RadioConfig}: every out-of-range value is corrected (with one
 * warning) and the jitter-buffer frame counts are resolved, once per load.
 */
final class ConfigNormalizer {

	private ConfigNormalizer() {
	}

	/**
	 * Clamps the Phase 3 ingest settings to usable values. Files that predate
	 * these keys arrive here with the field defaults (Gson leaves absent
	 * fields untouched), so only explicit bad values are corrected.
	 */
	static void normalizeIngestSettings(RadioConfig loaded) {
		if (loaded.ingest == null || loaded.ingest.isBlank()) {
			loaded.ingest = "ffmpeg";
		} else {
			loaded.ingest = loaded.ingest.trim().toLowerCase(Locale.ROOT);
			if (!loaded.ingest.equals("ffmpeg") && !loaded.ingest.equals("stub")) {
				JockeyRadioMod.LOGGER.warn(
						"Unknown ingest '{}' in {} (expected \"ffmpeg\" or \"stub\"); using \"ffmpeg\"",
						loaded.ingest, FILE_NAME);
				loaded.ingest = "ffmpeg";
			}
		}
		if (loaded.ffmpegBinary == null || loaded.ffmpegBinary.isBlank()) {
			loaded.ffmpegBinary = "ffmpeg";
		}
		if (loaded.bufferFrames < 5 || loaded.bufferFrames > 3000) {
			JockeyRadioMod.LOGGER.warn(
					"bufferFrames {} in {} is outside 5..3000; using default 600",
					loaded.bufferFrames, FILE_NAME);
			loaded.bufferFrames = 600;
		}
		if (loaded.reconnectBackoffMaxSeconds < 2 || loaded.reconnectBackoffMaxSeconds > 3600) {
			JockeyRadioMod.LOGGER.warn(
					"reconnectBackoffMaxSeconds {} in {} is outside 2..3600; using default 60",
					loaded.reconnectBackoffMaxSeconds, FILE_NAME);
			loaded.reconnectBackoffMaxSeconds = 60;
		}
		// HLS start-behind depth: 0 (default live-edge) .. the retained window. Clamp
		// with a warning like the other knobs so a too-deep start never seeks past the
		// trunk's deletion horizon (sow-005 Phase A).
		if (loaded.hlsStartBehindSegments < 0
				|| loaded.hlsStartBehindSegments > MAX_HLS_START_BEHIND_SEGMENTS) {
			int clamped = Math.max(0, Math.min(MAX_HLS_START_BEHIND_SEGMENTS, loaded.hlsStartBehindSegments));
			JockeyRadioMod.LOGGER.warn(
					"hlsStartBehindSegments {} in {} is outside 0..{}; using {}",
					loaded.hlsStartBehindSegments, FILE_NAME, MAX_HLS_START_BEHIND_SEGMENTS, clamped);
			loaded.hlsStartBehindSegments = clamped;
		}
		if (loaded.adminPermissionLevel < 0 || loaded.adminPermissionLevel > 4) {
			JockeyRadioMod.LOGGER.warn(
					"adminPermissionLevel {} in {} is outside 0..4; using default 3",
					loaded.adminPermissionLevel, FILE_NAME);
			loaded.adminPermissionLevel = 3;
		}
		// 0 or negative = disabled. A POSITIVE out-of-range value is clamped into 5..3600
		// like every neighbouring knob, rather than silently turning the feature off: an
		// operator who set 3 hoping for faster track updates got no poller at all, no
		// "now-playing poller enabled" line, and no track title for the whole run.
		if (loaded.nowPlayingPollSeconds < 0) {
			loaded.nowPlayingPollSeconds = 0;
		} else if (loaded.nowPlayingPollSeconds != 0
				&& (loaded.nowPlayingPollSeconds < 5 || loaded.nowPlayingPollSeconds > 3600)) {
			int clamped = Math.max(5, Math.min(3600, loaded.nowPlayingPollSeconds));
			JockeyRadioMod.LOGGER.warn(
					"nowPlayingPollSeconds {} in {} is outside 5..3600 (0 disables); using {}",
					loaded.nowPlayingPollSeconds, FILE_NAME, clamped);
			loaded.nowPlayingPollSeconds = clamped;
		}
		if (loaded.monitorSeconds < 10 || loaded.monitorSeconds > 600) {
			JockeyRadioMod.LOGGER.warn(
					"monitorSeconds {} in {} is outside 10..600; using default 90",
					loaded.monitorSeconds, FILE_NAME);
			loaded.monitorSeconds = 90;
		}
		// Now-playing bar gate mode: "beacon" (default) or "always". Warn once on an
		// unknown/blank value and fall back to "beacon" so the gate stays predictable.
		if (loaded.nowPlayingHudMode == null || loaded.nowPlayingHudMode.isBlank()) {
			loaded.nowPlayingHudMode = "beacon";
		} else {
			loaded.nowPlayingHudMode = loaded.nowPlayingHudMode.trim().toLowerCase(Locale.ROOT);
			if (!loaded.nowPlayingHudMode.equals("beacon") && !loaded.nowPlayingHudMode.equals("always")) {
				JockeyRadioMod.LOGGER.warn(
						"Unknown nowPlayingHudMode '{}' in {} (expected \"beacon\" or \"always\"); using \"beacon\"",
						loaded.nowPlayingHudMode, FILE_NAME);
				loaded.nowPlayingHudMode = "beacon";
			}
		}
		// Beacon-gate scan radius: 1..8 per axis (default 4 = 9x9x9). Clamp with a
		// warning like the other knobs so the gate cost stays bounded.
		if (loaded.nowPlayingHudTriggerRadius < 1 || loaded.nowPlayingHudTriggerRadius > 8) {
			int clamped = Math.max(1, Math.min(8, loaded.nowPlayingHudTriggerRadius));
			JockeyRadioMod.LOGGER.warn(
					"nowPlayingHudTriggerRadius {} in {} is outside 1..8; using {}",
					loaded.nowPlayingHudTriggerRadius, FILE_NAME, clamped);
			loaded.nowPlayingHudTriggerRadius = clamped;
		}
		// rtmpPublicHost is a bare host[:port] authority the mod prefixes with
		// rtmp://; a scheme or path would produce a malformed ingest URL, so reject
		// (null) such a value with one warning. Blank collapses to null (disabled).
		if (loaded.rtmpPublicHost != null) {
			String host = loaded.rtmpPublicHost.trim();
			if (host.isEmpty()) {
				loaded.rtmpPublicHost = null;
			} else if (host.contains("://") || host.contains("/")) {
				JockeyRadioMod.LOGGER.warn(
						"rtmpPublicHost '{}' in {} must be a bare host[:port] (no scheme, no path); ignoring",
						host, FILE_NAME);
				loaded.rtmpPublicHost = null;
			} else {
				loaded.rtmpPublicHost = host;
			}
		}
		// High-quality Opus bitrate: clamp to what the encoder will honour, with a warning.
		// The ceiling is Concentus's own 300 kbit/s mono cap, not the MTU - above it the
		// library silently reduced the value, so the knob read as effective when it was not.
		// Only consulted when opusHighQuality is on, but clamp regardless so a later toggle
		// stays safe. Pre-key files arrive with the field default.
		if (loaded.opusBitrate < MIN_OPUS_BITRATE || loaded.opusBitrate > MAX_OPUS_BITRATE) {
			int clamped = Math.max(MIN_OPUS_BITRATE, Math.min(MAX_OPUS_BITRATE, loaded.opusBitrate));
			JockeyRadioMod.LOGGER.warn(
					"opusBitrate {} in {} is outside {}..{} (the encoder caps mono at {}); using {}",
					loaded.opusBitrate, FILE_NAME, MIN_OPUS_BITRATE, MAX_OPUS_BITRATE,
					MAX_OPUS_BITRATE, clamped);
			loaded.opusBitrate = clamped;
		}
		// Opus encode complexity: the encode-latency/quality lever. Clamp 0..10 with
		// a warning, matching the opusBitrate pattern. Only consulted when
		// opusHighQuality is on, but clamp regardless so a later toggle stays safe.
		if (loaded.opusComplexity < MIN_OPUS_COMPLEXITY || loaded.opusComplexity > MAX_OPUS_COMPLEXITY) {
			int clamped = Math.max(MIN_OPUS_COMPLEXITY, Math.min(MAX_OPUS_COMPLEXITY, loaded.opusComplexity));
			JockeyRadioMod.LOGGER.warn(
					"opusComplexity {} in {} is outside {}..{}; using {}",
					loaded.opusComplexity, FILE_NAME, MIN_OPUS_COMPLEXITY, MAX_OPUS_COMPLEXITY, clamped);
			loaded.opusComplexity = clamped;
		}
		// Opus FEC expected packet-loss percent: clamp 0..100 with a warning. 0 = FEC off.
		if (loaded.opusFecPacketLossPercent < MIN_OPUS_FEC_PACKET_LOSS
				|| loaded.opusFecPacketLossPercent > MAX_OPUS_FEC_PACKET_LOSS) {
			int clamped = Math.max(MIN_OPUS_FEC_PACKET_LOSS,
					Math.min(MAX_OPUS_FEC_PACKET_LOSS, loaded.opusFecPacketLossPercent));
			JockeyRadioMod.LOGGER.warn(
					"opusFecPacketLossPercent {} in {} is outside {}..{}; using {}",
					loaded.opusFecPacketLossPercent, FILE_NAME, MIN_OPUS_FEC_PACKET_LOSS,
					MAX_OPUS_FEC_PACKET_LOSS, clamped);
			loaded.opusFecPacketLossPercent = clamped;
		}
		// sow-005 Phase B: smoothing mode - "tempo_match" (default, WSOLA stretch) or
		// "rebuffer" (Phase-A passthrough, safe rollback). Warn once on an unknown/blank
		// value and fall back to the default so the audio path stays predictable.
		if (loaded.smoothingMode == null || loaded.smoothingMode.isBlank()) {
			loaded.smoothingMode = DEFAULT_SMOOTHING_MODE;
		} else {
			loaded.smoothingMode = loaded.smoothingMode.trim().toLowerCase(Locale.ROOT);
			if (!loaded.smoothingMode.equals("tempo_match") && !loaded.smoothingMode.equals("rebuffer")) {
				JockeyRadioMod.LOGGER.warn(
						"Unknown smoothingMode '{}' in {} (expected \"tempo_match\" or \"rebuffer\"); using \"{}\"",
						loaded.smoothingMode, FILE_NAME, DEFAULT_SMOOTHING_MODE);
				loaded.smoothingMode = DEFAULT_SMOOTHING_MODE;
			}
		}
		// sow-005 Phase B: max time-stretch magnitude (percent), clamped 0..6. The feed
		// hard-clamps the servo factor to 1 +/- this/100. Absent uses the default.
		double stretchPct = loaded.maxStretchPercent != null
				? loaded.maxStretchPercent : DEFAULT_MAX_STRETCH_PERCENT;
		double clampedStretchPct = clamp(stretchPct, 0.0, MAX_MAX_STRETCH_PERCENT);
		if (loaded.maxStretchPercent != null && clampedStretchPct != stretchPct) {
			JockeyRadioMod.LOGGER.warn(
					"maxStretchPercent {} in {} is outside 0..{}; using {}",
					stretchPct, FILE_NAME, MAX_MAX_STRETCH_PERCENT, clampedStretchPct);
		}
		loaded.resolvedMaxStretchPercent = clampedStretchPct;
		resolveBuffering(loaded);
	}

	/**
	 * Resolves the jitter-buffer frame counts from the {@code *BufferSeconds}
	 * keys, clamping (and warning once) on out-of-range explicit values and
	 * migrating a legacy explicit {@code bufferFrames} into the max cap when the
	 * new keys are absent. Runs exactly once per {@link RadioConfig#load()} so any warning
	 * or migration line is logged once, not per feed. Idempotent and pure aside
	 * from logging; safe to call on a defaults instance (all keys present).
	 *
	 * <p>Order matters: the target is clamped first ({@value ConfigLimits#MIN_TARGET_BUFFER_SECONDS}..{@value
	 * ConfigLimits#MAX_TARGET_BUFFER_SECONDS} s), then the max cap (clamped {@code target}..{@value
	 * ConfigLimits#MAX_MAX_BUFFER_SECONDS} s so it can never sit below the target), then
	 * the preroll (clamped 0.2 s..max). Rounding to frames is re-ordered after so
	 * {@code preroll,target <= max} and every count is at least one frame.</p>
	 */
	static void resolveBuffering(RadioConfig c) {
		// --- target: steady-state playout latency ---
		double targetSec = c.targetBufferSeconds != null ? c.targetBufferSeconds : DEFAULT_TARGET_BUFFER_SECONDS;
		double clampedTarget = clamp(targetSec, MIN_TARGET_BUFFER_SECONDS, MAX_TARGET_BUFFER_SECONDS);
		if (c.targetBufferSeconds != null && clampedTarget != targetSec) {
			JockeyRadioMod.LOGGER.warn("targetBufferSeconds {} in {} is outside {}..{}; using {}",
					targetSec, FILE_NAME, MIN_TARGET_BUFFER_SECONDS, MAX_TARGET_BUFFER_SECONDS, clampedTarget);
		}
		targetSec = clampedTarget;

		// --- max cap: explicit key wins, else a migrated legacy bufferFrames,
		// else the default. Migration only when the new key is truly absent. ---
		double maxSec;
		boolean migrated = false;
		if (c.maxBufferSeconds != null) {
			maxSec = c.maxBufferSeconds;
		} else if (c.bufferFrames != LEGACY_DEFAULT_BUFFER_FRAMES) {
			maxSec = c.bufferFrames / (double) FRAMES_PER_SECOND;
			migrated = true;
		} else {
			maxSec = DEFAULT_MAX_BUFFER_SECONDS;
		}
		double clampedMax = clamp(maxSec, targetSec, MAX_MAX_BUFFER_SECONDS);
		if (c.maxBufferSeconds != null && clampedMax != maxSec) {
			JockeyRadioMod.LOGGER.warn("maxBufferSeconds {} in {} is outside {}..{}; using {}",
					maxSec, FILE_NAME, targetSec, MAX_MAX_BUFFER_SECONDS, clampedMax);
		}
		maxSec = clampedMax;

		// sow-005 Phase 4 (finding): the high-water trim IS the sliding-window lag cap,
		// but its deadband only exists when the hard cap sits well above the target.
		// Raising targetBufferSeconds toward maxBufferSeconds (the deeper-target guidance)
		// pins high-water to maxBufferFrames and collapses the trim excess to ~0, silently
		// defeating the lag cap - latency then sits at the full max with no bounded
		// catch-up. Warn once (does not fire at the shipped 3s/12s defaults) so the
		// operator raises the cap alongside the target instead of losing the lag bound.
		if (maxSec < LAG_CAP_HEADROOM_WARN_MULTIPLIER * targetSec) {
			JockeyRadioMod.LOGGER.warn(
					"maxBufferSeconds {}s in {} gives little headroom over targetBufferSeconds {}s; the "
							+ "high-water lag cap needs the cap above the target to bound latency; raise "
							+ "maxBufferSeconds to >= ~{}s (~{}x target) so a burst-inflated buffer still "
							+ "trims back to target",
					maxSec, FILE_NAME, targetSec,
					LAG_CAP_HEADROOM_RECOMMENDED_MULTIPLIER * targetSec, LAG_CAP_HEADROOM_RECOMMENDED_MULTIPLIER);
		}

		// --- preroll: explicit key wins, else the target. The preroll is the
		// steady-state resting depth (we never resample, so the buffer cannot grow
		// above it on its own; the trim only walks a burst back DOWN toward target),
		// so defaulting it below target would leave cold-start/post-underrun depth
		// under target and underrun on the first upstream gap. Default preroll ==
		// target keeps the configured target latency actually in the buffer. ---
		double prerollDefault = targetSec;
		double prerollSec = c.prerollSeconds != null ? c.prerollSeconds : prerollDefault;
		double clampedPreroll = clamp(prerollSec, MIN_PREROLL_SECONDS, maxSec);
		if (c.prerollSeconds != null && clampedPreroll != prerollSec) {
			JockeyRadioMod.LOGGER.warn("prerollSeconds {} in {} is outside {}..{}; using {}",
					prerollSec, FILE_NAME, MIN_PREROLL_SECONDS, maxSec, clampedPreroll);
		}
		prerollSec = clampedPreroll;

		int max = (int) Math.round(maxSec * FRAMES_PER_SECOND);
		int target = (int) Math.round(targetSec * FRAMES_PER_SECOND);
		int preroll = (int) Math.round(prerollSec * FRAMES_PER_SECOND);
		// Re-establish invariants after rounding: >=1 frame, target/preroll <= max.
		c.maxBufferFrames = Math.max(1, max);
		c.targetFrames = Math.max(1, Math.min(target, c.maxBufferFrames));
		c.prerollFrames = Math.max(1, Math.min(preroll, c.maxBufferFrames));

		// --- sow-005 Phase 4: sustained-drain re-anchor thresholds ---
		// Low watermark clamps into [0.2 s .. target] and is then forced strictly
		// below the target IN FRAMES, so the re-anchor arms only on a genuine drain
		// past the intended playout depth and never on jitter sitting at target.
		double lowSec = c.reanchorLowWatermarkSeconds != null
				? c.reanchorLowWatermarkSeconds : DEFAULT_REANCHOR_LOW_WATERMARK_SECONDS;
		double clampedLow = clamp(lowSec, MIN_REANCHOR_LOW_WATERMARK_SECONDS, targetSec);
		if (c.reanchorLowWatermarkSeconds != null && clampedLow != lowSec) {
			JockeyRadioMod.LOGGER.warn("reanchorLowWatermarkSeconds {} in {} is outside {}..{}; using {}",
					lowSec, FILE_NAME, MIN_REANCHOR_LOW_WATERMARK_SECONDS, targetSec, clampedLow);
		}
		lowSec = clampedLow;

		// Sustain <= 0 DISABLES the re-anchor (sustainFrames = 0); a positive value
		// clamps into [1 s .. 120 s].
		double sustainSec = c.reanchorSustainSeconds != null
				? c.reanchorSustainSeconds : DEFAULT_REANCHOR_SUSTAIN_SECONDS;
		int sustainFrames;
		if (sustainSec <= 0.0) {
			sustainFrames = 0;
		} else {
			double clampedSustain = clamp(sustainSec, MIN_REANCHOR_SUSTAIN_SECONDS, MAX_REANCHOR_SUSTAIN_SECONDS);
			if (c.reanchorSustainSeconds != null && clampedSustain != sustainSec) {
				JockeyRadioMod.LOGGER.warn("reanchorSustainSeconds {} in {} is outside {}..{}; using {}",
						sustainSec, FILE_NAME, MIN_REANCHOR_SUSTAIN_SECONDS, MAX_REANCHOR_SUSTAIN_SECONDS, clampedSustain);
			}
			sustainFrames = Math.max(1, (int) Math.round(clampedSustain * FRAMES_PER_SECOND));
		}
		int low = (int) Math.round(lowSec * FRAMES_PER_SECOND);
		// Floor the low water strictly below BOTH the target AND the preroll depth. A
		// re-anchor always rebuilds the cushion to prerollFrames (the delivery loop drops
		// back to pre-roll on fire), so if the low water sat at/above prerollFrames the
		// rebuild would land at/below its own arming threshold and the re-anchor could
		// re-fire once per sustain window instead of once per genuine drain episode.
		// prerollSeconds is an independent operator knob (clamped only to [0.2s..max]), so
		// a low-latency preroll below reanchorLowWatermarkSeconds is reachable; forcing
		// low < preroll here keeps the "fires ONCE per drain episode" guarantee for any
		// config. Warn once when the configured low water would have sat at/above the
		// preroll depth so the operator sees the interaction (sow-005 Phase 4, finding).
		if (sustainFrames > 0 && low >= c.prerollFrames) {
			JockeyRadioMod.LOGGER.warn(
					"reanchorLowWatermarkSeconds {}s in {} ({} frames) is at/above prerollSeconds ({} frames); "
							+ "a re-anchor rebuilds only to the preroll depth, so the low water is forced below it "
							+ "to keep the re-anchor firing once per drain episode; lower reanchorLowWatermarkSeconds "
							+ "or raise prerollSeconds",
					lowSec, FILE_NAME, low, c.prerollFrames);
		}
		c.reanchorLowWaterFrames = Math.max(1,
				Math.min(low, Math.max(1, Math.min(c.targetFrames - 1, c.prerollFrames - 1))));
		c.reanchorSustainFrames = sustainFrames;

		if (migrated) {
			JockeyRadioMod.LOGGER.info(
					"Migrated legacy bufferFrames={} in {} to maxBufferSeconds={}s ({} frames); set "
							+ "targetBufferSeconds/maxBufferSeconds/prerollSeconds to tune the jitter buffer",
					c.bufferFrames, FILE_NAME, maxSec, c.maxBufferFrames);
		}
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
