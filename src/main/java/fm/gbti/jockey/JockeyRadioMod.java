package fm.gbti.jockey;

import fm.gbti.jockey.command.RadioCommands;
import fm.gbti.jockey.config.HostCapabilityProbe;
import fm.gbti.jockey.config.RadioConfig;
import fm.gbti.jockey.disc.DiscRegistry;
import fm.gbti.jockey.disc.LocalAudioServer;
import fm.gbti.jockey.session.FeedDiagnostics;
import fm.gbti.jockey.session.MonitorService;
import fm.gbti.jockey.session.MusicSuppressor;
import fm.gbti.jockey.session.NowPlayingHud;
import fm.gbti.jockey.session.RadioManager;
import fm.gbti.jockey.session.RtmpKeepAlive;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jockey Radio - server-side streaming radio jukebox discs.
 *
 * <p>Phase 1: config, bundled data-driven jukebox song, disc factory and the
 * {@code /jockey} command. Phase 2: jukebox mixins plus the disc-presence
 * session core ({@link RadioManager}) - no audio yet. Later phases add the
 * station feeds and the Simple Voice Chat audio bridge.</p>
 */
public final class JockeyRadioMod implements ModInitializer {

	public static final String MOD_ID = "jockey_radio";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		RadioConfig config = RadioConfig.load();
		FeedDiagnostics.init(config);
		LocalAudioServer.get().start(config.localAudioServerPort);
		DiscRegistry.get().load();
		// Reconcile everything that depends on the disc set after any mutation
		// (create/edit/delete/reload): live sessions first, then the sow-008 RTMP
		// keep-alive anchors. RadioManager owns the ordering because releasing the
		// sessions' feed references has to happen before the anchors re-acquire, or an
		// edited disc gets its OLD feed handed straight back. A no-op before the server
		// is running, so this startup load() does not touch feeds early.
		DiscRegistry.get().setChangeListener(() -> RadioManager.get().onDiscsChanged());
		RadioCommands.register();
		registerSessionLifecycle();
		LOGGER.info("Jockey Radio initialized ({} config station(s), default station '{}')",
				config.stations.size(), config.defaultStation == null ? "none" : config.defaultStation);
		// sow-013: state the platform gate every boot. An existing config file is never rewritten
		// when new keys are added, so an UPGRADING operator would otherwise have no way to discover
		// that enablePlatformLinks exists - and a gate nobody can find defeats the point of asking
		// them to opt in deliberately.
		if (config.enablePlatformLinks) {
			LOGGER.warn("[jockey] platform links are ENABLED; you are responsible for holding the rights "
					+ "to rebroadcast any third-party content you link on this server.");
		} else {
			LOGGER.info("[jockey] platform links are disabled (default). Set enablePlatformLinks=true in "
					+ "jockey_radio.json to enable platform-link discs.");
		}
		// Report what this host actually permits (process spawning / executing a binary we place on
		// disk / whether ffmpeg is present). On a managed host with no shell this log is the only way
		// an operator - or we - can tell "ffmpeg isn't installed" apart from "this container forbids
		// running it", which decides whether shipping bundled binaries can work here at all.
		HostCapabilityProbe.logReport(FabricLoader.getInstance().getConfigDir());
	}

	/**
	 * Wires {@link RadioManager} into the server lifecycle. Every callback
	 * here runs on the server thread; jukebox load/unload drives session
	 * resume/suspend, and server stop suspends everything and tears down the
	 * feed executor.
	 */
	private static void registerSessionLifecycle() {
		ServerLifecycleEvents.SERVER_STARTING.register(server ->
				RadioManager.get().onServerStarting(server));

		ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((blockEntity, level) -> {
			if (blockEntity instanceof JukeboxBlockEntity) {
				RadioManager.get().onJukeboxLoaded(level, blockEntity.getBlockPos());
			}
		});
		ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((blockEntity, level) -> {
			if (blockEntity instanceof JukeboxBlockEntity) {
				RadioManager.get().onJukeboxUnloaded(level, blockEntity.getBlockPos());
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(server ->
				RadioManager.get().onEndServerTick(server));

		// Audible-monitor lifecycle: tick the level meters, tear a monitor down when
		// its admin leaves, and stop every monitor on server stop. The monitor's
		// SERVER_STOPPING handler is registered BEFORE RadioManager.shutdownAll so a
		// monitor releases its feed lease while the feed registry is still live
		// (after shutdown the release would just log an ignored "unknown station").
		ServerTickEvents.END_SERVER_TICK.register(server ->
				MonitorService.get().onEndServerTick(server));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				MonitorService.get().onPlayerDisconnect(handler.player));
		ServerLifecycleEvents.SERVER_STOPPING.register(server ->
				MonitorService.get().stopAll());

		// Now-playing boss bar: reconcile per-radius bar membership each tick, drop a
		// leaving player from every bar, and clear all bars on server stop. Holds no
		// feed leases (only vanilla boss bars), so ordering vs shutdownAll is moot; it
		// coexists with MonitorService's monitor bar (a different bar per purpose).
		ServerTickEvents.END_SERVER_TICK.register(server ->
				NowPlayingHud.get().onEndServerTick(server));
		// DISCONNECT can fire on the Netty event-loop thread for a network-level drop
		// (client quit / timeout / TCP reset), so marshal the boss-bar teardown onto
		// the server thread: NowPlayingHud's bar map and every ServerBossEvent player
		// set are plain (non-thread-safe) and are otherwise only touched by the
		// server-thread sweep. Capture the player reference before scheduling.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			var player = handler.player;
			server.execute(() -> NowPlayingHud.get().onPlayerDisconnect(player));
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server ->
				NowPlayingHud.get().stopAll());

		// Server-side game-music suppression: each tick, hard-mute the vanilla
		// background music of every player standing within a playing disc's radius so
		// Minecraft's own music never fights the streamed radio (a periodic
		// ClientboundStopSoundPacket for the MUSIC category - no client mod). Holds no
		// per-player state and nothing to tear down: once a player leaves the radius or
		// the server stops sending, the client's music resumes on its own, so there is
		// no disconnect/stop handler to register (unlike NowPlayingHud's bars).
		ServerTickEvents.END_SERVER_TICK.register(server ->
				MusicSuppressor.get().onEndServerTick(server));

		// sow-008: release RTMP keep-alive anchors BEFORE RadioManager.shutdownAll so
		// their ref-counts unwind while the feed registry is still live (mirrors the
		// MonitorService lease-release ordering above).
		ServerLifecycleEvents.SERVER_STOPPING.register(server ->
				RtmpKeepAlive.get().shutdownAll());

		ServerLifecycleEvents.SERVER_STOPPING.register(server ->
				RadioManager.get().shutdownAll());

		// Flush + close the diagnostics dump AFTER shutdownAll so the delivery loops
		// have stopped emitting rows first (handlers run in registration order).
		ServerLifecycleEvents.SERVER_STOPPING.register(server ->
				FeedDiagnostics.shutdown());
		// sow-009: stop the internal loopback audio server on shutdown.
		ServerLifecycleEvents.SERVER_STOPPING.register(server ->
				LocalAudioServer.get().stop());
	}
}
