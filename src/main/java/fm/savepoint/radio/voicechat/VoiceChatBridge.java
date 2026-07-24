package fm.savepoint.radio.voicechat;

import fm.savepoint.radio.session.AudioSink;
import fm.savepoint.radio.session.MonitorChannel;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * The ONLY Simple Voice Chat surface the rest of the mod may touch. This class
 * deliberately imports no {@code de.maxhenkel.voicechat.api} types: every
 * method first checks {@code isModLoaded("voicechat")} and only then delegates
 * to {@link VoiceChatBackend}, whose bytecode does reference the SVC API.
 * Because the JVM resolves {@code VoiceChatBackend} lazily - at the first
 * delegated call, which the guard prevents when SVC is absent - no SVC class is
 * ever loaded on a server without the voicechat mod.
 *
 * <p>Classloading isolation rule for the whole codebase: outside the
 * {@code fm.savepoint.radio.voicechat} package, only this bridge may be
 * referenced; inside it, only this class is safe to load unconditionally.</p>
 */
public final class VoiceChatBridge {

	/** True when the Simple Voice Chat mod is installed (API may still be pending). */
	public static boolean isModPresent() {
		return FabricLoader.getInstance().isModLoaded("voicechat");
	}

	/** True when SVC is installed AND its server API has been captured. */
	public static boolean isAvailable() {
		return isModPresent() && VoiceChatBackend.isReady();
	}

	/**
	 * Creates a locational voicechat sink for a jukebox, or empty when SVC is
	 * absent, its server API is not ready yet, or channel creation fails.
	 * Called on the server thread; all involved API calls are non-blocking.
	 */
	public static Optional<AudioSink> createSink(ServerLevel level, GlobalPos pos, int radiusBlocks, String stationKey) {
		if (!isModPresent()) {
			return Optional.empty();
		}
		return Optional.ofNullable(VoiceChatBackend.createSink(level, pos, radiusBlocks, stationKey));
	}

	/**
	 * Creates a private audible monitor sink playing to the admin's own voice chat
	 * connection, or empty when SVC is absent, its server API is not ready, or the
	 * admin has no connected voice chat client (so a monitor would be inaudible).
	 * The returned {@link MonitorChannel} exposes a level meter but no SVC type, so
	 * {@code MonitorService} can drive it without loading any voicechat class.
	 * Called on the server thread; all involved API calls are non-blocking.
	 */
	public static Optional<MonitorChannel> createMonitorSink(ServerLevel level, ServerPlayer admin, String discLabel) {
		if (!isModPresent()) {
			return Optional.empty();
		}
		return Optional.ofNullable(VoiceChatBackend.createMonitorSink(level, admin, discLabel));
	}

	private VoiceChatBridge() {
	}
}
