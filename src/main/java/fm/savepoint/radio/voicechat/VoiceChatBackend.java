package fm.savepoint.radio.voicechat;

import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;
import fm.savepoint.radio.SavePointRadioMod;
import fm.savepoint.radio.config.RadioConfig;
import fm.savepoint.radio.session.AudioSink;
import fm.savepoint.radio.session.MonitorChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holder for the captured {@link VoicechatServerApi} plus the sink factory
 * built on it. Imports SVC API types, so it must only ever be classloaded via
 * {@link VoiceChatBridge}'s {@code isModLoaded} guard (or by
 * {@link SavePointVoicechatPlugin}, which SVC itself instantiates).
 *
 * <p>The api reference is written by SVC's server started/stopped events
 * (whatever thread SVC fires them on) and read on the server thread and, via
 * {@link VoiceChatBridge#isAvailable()}, potentially anywhere - hence
 * volatile.</p>
 */
final class VoiceChatBackend {

	/** Volume category id channels are tagged with (client-side volume slider). */
	static final String CATEGORY_ID = "savepoint_radio";

	private static volatile VoicechatServerApi serverApi;
	private static volatile boolean categoryRegistered;

	/**
	 * Set once the high-quality encoder has failed to initialise and we logged the
	 * fallback, so the warning is emitted a single time (not per sink/frame).
	 */
	private static final AtomicBoolean hqFallbackLogged = new AtomicBoolean(false);

	/**
	 * Captures the server api once SVC has started. Called from the plugin only.
	 *
	 * <p>Writes {@code categoryRegistered} BEFORE the volatile {@code serverApi}
	 * on purpose: SVC may fire its started event off the server thread, so a
	 * session activating concurrently on the server thread could read these two
	 * volatiles. Publishing the flag first means the happens-before edge created
	 * when a reader sees a non-null {@code serverApi} also makes the final
	 * {@code categoryRegistered} value visible. Readers ({@link #createSink})
	 * null-check {@code serverApi} first, so they can never observe
	 * {@code serverApi != null} while {@code categoryRegistered} is still stale -
	 * which would otherwise drop the channel's volume-category tag.</p>
	 */
	static void setServerApi(VoicechatServerApi api, boolean categoryOk) {
		categoryRegistered = categoryOk;
		serverApi = api;
	}

	/** Drops the api on SVC server stop; new sinks fall back to null sinks. */
	static void clear() {
		serverApi = null;
		categoryRegistered = false;
	}

	static boolean isReady() {
		return serverApi != null;
	}

	/**
	 * Creates a {@link VoiceChatSink} (locational channel centered in the
	 * jukebox block plus a per-sink Opus encoder), or null when the api is not
	 * ready or channel creation fails. Server thread; non-blocking.
	 */
	static AudioSink createSink(ServerLevel level, GlobalPos pos, int radiusBlocks, String stationKey) {
		VoicechatServerApi api = serverApi;
		if (api == null) {
			return null;
		}
		BlockPos p = pos.pos();
		RadioOpusEncoder encoder = null;
		try {
			Position center = api.createPosition(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
			LocationalAudioChannel channel = api.createLocationalAudioChannel(
					UUID.randomUUID(), api.fromServerLevel(level), center);
			if (channel == null) {
				SavePointRadioMod.LOGGER.warn(
						"[radio] voicechat channel creation returned null {} station={}",
						describePos(pos), stationKey);
				return null;
			}
			channel.setDistance(radiusBlocks);
			if (categoryRegistered) {
				channel.setCategory(CATEGORY_ID);
			}
			encoder = createRadioEncoder(api);
			return new VoiceChatSink(channel, encoder, stationKey, describePos(pos));
		} catch (Throwable t) {
			if (encoder != null) {
				try {
					encoder.close();
				} catch (Throwable ignored) {
				}
			}
			SavePointRadioMod.LOGGER.error("[radio] voicechat sink creation failed {} station={}",
					describePos(pos), stationKey, t);
			return null;
		}
	}

	/**
	 * Creates a {@link MonitorSink} on a NON-POSITIONAL {@link StaticAudioChannel}
	 * bound to the admin's own voice chat connection, plus a per-sink Opus
	 * encoder, or null when the api is not ready, the admin has no connected voice
	 * chat client, or channel creation fails. The static 3-arg channel plays to
	 * exactly that one connection - a private monitor, audible regardless of the
	 * admin's position and with no jukebox involved. Server thread; non-blocking.
	 *
	 * <p>The 3-arg {@code createStaticAudioChannel(UUID, ServerLevel,
	 * VoicechatConnection)} factory is marked {@code @Deprecated} in voicechat-api
	 * 2.6.20 (the newer style creates a 1-arg channel and adds targets manually),
	 * but it is still the exact one-connection binding a monitor wants and works on
	 * this API version; the suppression is scoped to this method.</p>
	 */
	@SuppressWarnings("deprecation")
	static MonitorChannel createMonitorSink(ServerLevel level, ServerPlayer admin, String discLabel) {
		VoicechatServerApi api = serverApi;
		if (api == null) {
			return null;
		}
		// getConnectionOf(UUID) avoids wrapping the MC player; null means the admin
		// has no SVC client, isConnected() gates a client that is not on the voice
		// server yet - either way a monitor would be inaudible, so refuse cleanly.
		VoicechatConnection connection = api.getConnectionOf(admin.getUUID());
		if (connection == null || !connection.isConnected()) {
			return null;
		}
		RadioOpusEncoder encoder = null;
		try {
			StaticAudioChannel channel = api.createStaticAudioChannel(
					UUID.randomUUID(), api.fromServerLevel(level), connection);
			if (channel == null) {
				SavePointRadioMod.LOGGER.warn(
						"[radio] voicechat monitor channel creation returned null station={}", discLabel);
				return null;
			}
			if (categoryRegistered) {
				channel.setCategory(CATEGORY_ID);
			}
			encoder = createRadioEncoder(api);
			return new MonitorSink(channel, encoder, discLabel);
		} catch (Throwable t) {
			if (encoder != null) {
				try {
					encoder.close();
				} catch (Throwable ignored) {
				}
			}
			SavePointRadioMod.LOGGER.error("[radio] voicechat monitor sink creation failed station={}", discLabel, t);
			return null;
		}
	}

	/**
	 * Builds the per-sink Opus encoder for one of OUR channels. When
	 * {@code opusHighQuality} is on (default) this is our bundled music-grade
	 * {@link HqOpusEncoder} at the configured bitrate; if it fails to initialise we
	 * log once and fall back to SVC's own {@code api.createEncoder(AUDIO)} so audio
	 * still works. When {@code opusHighQuality} is off we use the SVC encoder
	 * directly - byte-for-byte the pre-Phase-2b behaviour. Either way the encoder is
	 * wrapped in a {@link RadioOpusEncoder} so the sinks stay encoder-agnostic, and
	 * NO other SVC channel or the player voice codec is affected (only sinks WE
	 * create ever reach this method).
	 */
	private static RadioOpusEncoder createRadioEncoder(VoicechatServerApi api) {
		RadioConfig config = RadioConfig.get();
		if (config.opusHighQuality) {
			try {
				return HqOpusEncoder.create(config.opusBitrate, config.opusComplexity,
						config.opusFecPacketLossPercent);
			} catch (Throwable t) {
				if (hqFallbackLogged.compareAndSet(false, true)) {
					SavePointRadioMod.LOGGER.warn(
							"[radio] high-quality Opus encoder init failed (opusBitrate={}); falling back to "
									+ "the SVC voice encoder for radio channels", config.opusBitrate, t);
				}
			}
		}
		return new SvcOpusEncoder(api.createEncoder(OpusEncoderMode.AUDIO));
	}

	/** Same {@code pos=<x,y,z> dim=<id>} shape the session log contract uses. */
	static String describePos(GlobalPos pos) {
		BlockPos p = pos.pos();
		return "pos=" + p.getX() + "," + p.getY() + "," + p.getZ()
				+ " dim=" + pos.dimension().identifier();
	}

	private VoiceChatBackend() {
	}
}
