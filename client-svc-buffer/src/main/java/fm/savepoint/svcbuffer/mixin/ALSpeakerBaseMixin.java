package fm.savepoint.svcbuffer.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.fabricmc.loader.api.FabricLoader;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Deepens Simple Voice Chat's client playout buffer for continuous music.
 *
 * <p><b>How SVC's playout actually works</b> (verified against 2.6.21+26.2
 * bytecode). {@code ALSpeakerBase} owns a FIXED pool {@code buffers = new
 * int[32]} of OpenAL buffers (32 &times; 20ms = 640ms ceiling). Two things are
 * driven by {@code getBufferSize()} (= config {@code output_buffer_size}, which
 * SVC hard-clamps to 16):
 * <ul>
 *   <li><b>Pre-buffer / underrun cushion</b> ({@code lambda$play$0}): whenever
 *       the source is stopped (initial start OR after an underrun), it queues
 *       {@code getBufferSize()} silent frames, then real audio, then
 *       {@code alSourcePlay}. So {@code getBufferSize()} IS the depth of the
 *       cushion rebuilt on every underrun - the dominant smoothness knob.</li>
 *   <li><b>Overflow drain</b> ({@code writeSync}): when {@code queued >=
 *       buffers.length} it fast-forwards the sample offset by
 *       {@code (queued - getBufferSize())} frames.</li>
 * </ul>
 *
 * <p><b>Why a naive override breaks audio.</b> If {@code getBufferSize() > 32}
 * (the pool size): (a) the pre-buffer loop writes &gt;32 frames and overflows
 * the pool mid-fill, and (b) the overflow drain computes a NEGATIVE skip and
 * seeks the AL sample offset backward - OpenAL chokes and the channel goes
 * silent. That is exactly why SVC clamps its own value to 16, and why simply
 * returning 50 produced "on air, no sound".
 *
 * <p><b>This mixin therefore does two coordinated things:</b>
 * <ol>
 *   <li>{@link #savepoint_svc_buffer$enlargePool} enlarges the pool constant
 *       {@code 32} to {@code pool_packets} (default 110 = 2.2s), so there is
 *       room for a deep cushion.</li>
 *   <li>{@link #savepoint_svc_buffer$deepen} raises {@code getBufferSize()} to
 *       {@code buffer_packets} (default 50 = 1.0s) but ALWAYS clamps it to
 *       {@code buffers.length - MARGIN}, read live via a {@code @Shadow}. This
 *       is self-correcting: even if the pool enlarge ever fails to apply, the
 *       cushion stays below the real pool and the negative-skip silence bug
 *       cannot recur - it just degrades to a smaller (still &gt; stock) buffer.</li>
 * </ol>
 *
 * <p>Targeted by fully-qualified STRING name because {@code ALSpeakerBase} is a
 * client-side SVC class not in the voicechat-api jar. {@code remap=false}
 * throughout because these are third-party (non-Mojang) names. Pinned to this
 * SVC build via the {@code buffers} shadow; a future SVC that renames the field
 * or the class makes the mixin refuse to apply (loud) rather than misbehave.
 */
@Mixin(targets = "de.maxhenkel.voicechat.voice.client.speaker.ALSpeakerBase", remap = false)
public class ALSpeakerBaseMixin {

	/** SVC's fixed OpenAL buffer pool. Read (never written) for the safety clamp. */
	@Shadow
	@Final
	protected int[] buffers;

	/** Config keys. Each "packet" is one 20ms audio frame. */
	private static final String POOL_KEY = "pool_packets";
	private static final String CUSHION_KEY = "buffer_packets";

	/** Defaults: pool 110 = 2.2s ceiling; cushion 50 = 1.0s (> the ~700ms send stall). */
	private static final int DEFAULT_POOL = 110;
	private static final int DEFAULT_CUSHION = 50;

	/** Pool clamp: never below SVC stock (32); a sane upper bound to cap OpenAL buffer count. */
	private static final int MIN_POOL = 32;
	private static final int MAX_POOL = 512;

	/** Cushion clamp floor; the ceiling is enforced at runtime as pool - MARGIN. */
	private static final int MIN_CUSHION = 5;

	/**
	 * Headroom kept between the cushion and the pool ceiling. The cushion must be
	 * strictly below {@code buffers.length} so the pre-buffer loop never trips the
	 * overflow branch and the overflow skip {@code (queued - cushion)} stays
	 * non-negative. 8 frames = 160ms of slack.
	 */
	private static final int MARGIN = 8;

	/** Cached config; -1 means "not yet loaded". Read off the hot path. */
	private static volatile int savepoint_svc_buffer$poolPackets = -1;
	private static volatile int savepoint_svc_buffer$cushionPackets = -1;

	// require = 0 on both injectors: SVC's internals are non-API and refactored
	// across versions. Because fabric.mod.json hard-depends on voicechat, any SVC
	// build applies this mixin; require=0 makes a moved/renamed target a tolerated
	// no-op instead of an InjectionError crash. Safety does not depend on either
	// injector resolving - the cushion clamp reads the live pool length regardless.

	/**
	 * Enlarges the fixed OpenAL buffer pool {@code new int[32]} so a deeper
	 * cushion has room. Only ever grows it (never shrinks below SVC's 32).
	 */
	@ModifyConstant(method = "<init>", constant = @Constant(intValue = 32), remap = false, require = 0)
	private int savepoint_svc_buffer$enlargePool(int original) {
		int pool = savepoint_svc_buffer$poolPackets;
		if (pool < 0) {
			savepoint_svc_buffer$loadConfig();
			pool = savepoint_svc_buffer$poolPackets;
		}
		return Math.max(original, pool);
	}

	/**
	 * Raises the pre-buffer / overflow-reset depth, clamped to strictly below the
	 * live pool length so the negative-skip silence bug can never occur.
	 */
	@ModifyReturnValue(method = "getBufferSize()I", at = @At("RETURN"), remap = false, require = 0)
	private int savepoint_svc_buffer$deepen(int original) {
		int cushion = savepoint_svc_buffer$cushionPackets;
		if (cushion < 0) {
			savepoint_svc_buffer$loadConfig();
			cushion = savepoint_svc_buffer$cushionPackets;
		}
		// Hard safety cap: always leave MARGIN below the ACTUAL pool size, whatever
		// it ended up being (enlarged or stock 32). buffers is set in <init> before
		// any audio flows, so it is non-null here, but guard anyway.
		int poolLen = (buffers != null) ? buffers.length : MIN_POOL;
		int cap = Math.max(MIN_CUSHION, poolLen - MARGIN);
		int target = Math.min(cushion, cap);
		// Only ever INCREASE over SVC's own value; never exceed the safety cap.
		return Math.min(cap, Math.max(original, target));
	}

	/**
	 * Loads both knobs once from {@code config/savepoint-svc-buffer.properties}.
	 * Never throws: any problem falls back to the defaults. Writes a documented
	 * default file if one is missing.
	 */
	private static synchronized void savepoint_svc_buffer$loadConfig() {
		if (savepoint_svc_buffer$poolPackets >= 0 && savepoint_svc_buffer$cushionPackets >= 0) {
			return; // another thread already loaded it
		}
		int pool = DEFAULT_POOL;
		int cushion = DEFAULT_CUSHION;
		try {
			Path configFile = FabricLoader.getInstance().getConfigDir()
					.resolve("savepoint-svc-buffer.properties");

			if (!Files.exists(configFile)) {
				savepoint_svc_buffer$writeDefault(configFile);
			} else {
				Properties props = new Properties();
				try (InputStream in = Files.newInputStream(configFile)) {
					props.load(in);
				}
				pool = savepoint_svc_buffer$parse(props, POOL_KEY, DEFAULT_POOL);
				cushion = savepoint_svc_buffer$parse(props, CUSHION_KEY, DEFAULT_CUSHION);
			}
		} catch (Throwable t) {
			// Malformed value, IO error, missing loader, anything: stay on defaults.
			pool = DEFAULT_POOL;
			cushion = DEFAULT_CUSHION;
		}
		pool = Math.max(MIN_POOL, Math.min(MAX_POOL, pool));
		// Keep the cached cushion sane; the live per-call clamp still enforces the
		// true pool-MARGIN ceiling.
		cushion = Math.max(MIN_CUSHION, cushion);
		savepoint_svc_buffer$poolPackets = pool;
		savepoint_svc_buffer$cushionPackets = cushion;
	}

	private static int savepoint_svc_buffer$parse(Properties props, String key, int fallback) {
		String raw = props.getProperty(key);
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		try {
			return Integer.parseInt(raw.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/** Best-effort write of a documented default config. Silent on failure. */
	private static void savepoint_svc_buffer$writeDefault(Path configFile) {
		try {
			Files.createDirectories(configFile.getParent());
			Properties props = new Properties();
			props.setProperty(POOL_KEY, Integer.toString(DEFAULT_POOL));
			props.setProperty(CUSHION_KEY, Integer.toString(DEFAULT_CUSHION));
			String header = "SavePoint SVC Buffer - deepens Simple Voice Chat's client playout buffer.\n"
					+ "Each packet = one 20ms audio frame.\n"
					+ POOL_KEY + " = max OpenAL buffer pool depth (SVC stock = 32 = 640ms)."
					+ " Range " + MIN_POOL + ".." + MAX_POOL + ". Default " + DEFAULT_POOL + " = 2.2s.\n"
					+ CUSHION_KEY + " = pre-buffer/underrun cushion depth restored on every start"
					+ " (SVC clamps its own to 16 = 320ms). Auto-capped to " + POOL_KEY + " minus "
					+ MARGIN + ". Default " + DEFAULT_CUSHION + " = 1.0s.";
			try (OutputStream out = Files.newOutputStream(configFile)) {
				props.store(out, header);
			}
		} catch (IOException | RuntimeException ignored) {
			// Non-fatal: the defaults are still used in memory.
		}
	}
}
