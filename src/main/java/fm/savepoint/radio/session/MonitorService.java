package fm.savepoint.radio.session;

import fm.savepoint.radio.SavePointRadioMod;
import fm.savepoint.radio.config.RadioConfig;
import fm.savepoint.radio.disc.DiscDefinition;
import fm.savepoint.radio.disc.DiscRegistry;
import fm.savepoint.radio.voicechat.VoiceChatBridge;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side coordinator for the on-demand AUDIBLE MONITOR behind the
 * {@code /savepoint} "Test" button: it lets an admin hear a disc's feed
 * privately - no jukebox - with a boss-bar level meter, then auto-stops.
 *
 * <p>One monitor per admin. {@link #start(ServerPlayer, String)} acquires the
 * disc's shared feed through the SAME {@link FeedSource}
 * {@link RadioManager#discFeedSource} builds (so a monitor reuses a live feed
 * when the disc is already playing, or starts a ref-counted transient one),
 * attaches a private {@link MonitorChannel} that plays into the admin's own
 * voice chat connection, and shows a {@link ServerBossEvent} whose progress
 * tracks the smoothed RMS level. It auto-stops after {@code monitorSeconds}, on
 * admin disconnect, and on server stop.</p>
 *
 * <p>Threading: every method here runs on the server thread. {@link #start} and
 * {@link #stop} are driven from {@link fm.savepoint.radio.dialog.DialogRouter}
 * (past {@code ensureRunningOnSameThread}); {@link #onEndServerTick},
 * {@link #onPlayerDisconnect} and {@link #stopAll} from Fabric lifecycle events
 * that fire on the server thread. So the boss bar and every SVC-touching call
 * (via {@link VoiceChatBridge}) stay on the server thread per the guardrail,
 * while the RMS level is produced off-thread in the sink and read here through a
 * volatile. The monitor map is concurrent only as defensive hygiene; all access
 * is in fact serialized on the server thread.</p>
 */
public final class MonitorService {

	private static final MonitorService INSTANCE = new MonitorService();

	/** Server ticks per second (the auto-stop deadline is counted in ticks). */
	private static final int TICKS_PER_SECOND = 20;

	/** Keyed by admin UUID: one monitor per admin. Server-thread access only. */
	private final Map<UUID, Monitor> monitors = new ConcurrentHashMap<>();

	public static MonitorService get() {
		return INSTANCE;
	}

	/** Outcome of {@link #start}, mapped to a dialog notice by the router. */
	public enum StartResult {
		/** Monitor is running; the admin should now hear audio. */
		STARTED,
		/** No such disc in the registry (a race: deleted after the router looked it up). */
		NO_DISC,
		/** Simple Voice Chat is not installed / its server api is not ready. */
		NO_VOICECHAT,
		/** The admin has no connected voice chat client, so a monitor would be silent. */
		NO_CONNECTION,
		/** An unexpected failure while wiring the monitor up (logged server-side). */
		ERROR
	}

	/**
	 * Starts (or restarts) the audible monitor for {@code admin} on {@code discId}.
	 * Any monitor already running for this admin is stopped first. Server thread.
	 */
	public StartResult start(ServerPlayer admin, String discId) {
		// One monitor per admin: tear down any existing one first (idempotent).
		stop(admin);

		DiscDefinition def = DiscRegistry.get().get(discId).orElse(null);
		if (def == null) {
			return StartResult.NO_DISC;
		}
		if (!VoiceChatBridge.isModPresent()) {
			return StartResult.NO_VOICECHAT;
		}

		ServerLevel level = admin.level();
		// The sink is created FIRST: if the admin isn't connected to voice chat the
		// bridge returns empty and we bail before acquiring a feed or a boss bar, so
		// nothing needs cleanup. Null also covers "api not ready".
		MonitorChannel sink = VoiceChatBridge.createMonitorSink(level, admin, discId).orElse(null);
		if (sink == null) {
			return VoiceChatBridge.isAvailable() ? StartResult.NO_CONNECTION : StartResult.NO_VOICECHAT;
		}

		FeedSource source = RadioManager.discFeedSource(discId, def);
		StationFeed feed = null;
		ServerBossEvent bossBar = null;
		try {
			feed = FeedRegistry.get().acquire(source);
			sink.open();
			feed.addSink(sink);

			String discName = def.name != null && !def.name.isBlank() ? def.name : discId;
			bossBar = new ServerBossEvent(UUID.randomUUID(),
					buildName(discName, false, null, null),
					BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
			bossBar.setProgress(0f);
			bossBar.addPlayer(admin);

			int seconds = RadioConfig.get().monitorSeconds;
			Monitor monitor = new Monitor(admin.getUUID(), discId, discName, source.key(),
					feed, sink, bossBar, Math.max(1, seconds) * TICKS_PER_SECOND);
			monitors.put(admin.getUUID(), monitor);
			SavePointRadioMod.LOGGER.info("[radio] monitor start admin={} station={} seconds={}",
					admin.getName().getString(), discId, seconds);
			return StartResult.STARTED;
		} catch (Throwable t) {
			// Never leak the sink/feed lease/boss bar if wiring failed partway.
			if (feed != null) {
				try {
					feed.removeSink(sink);
				} catch (Throwable ignored) {
				}
				try {
					FeedRegistry.get().release(source.key());
				} catch (Throwable ignored) {
				}
			}
			try {
				sink.close();
			} catch (Throwable ignored) {
			}
			if (bossBar != null) {
				try {
					bossBar.removeAllPlayers();
				} catch (Throwable ignored) {
				}
			}
			SavePointRadioMod.LOGGER.error("[radio] monitor start failed admin={} station={}",
					admin.getName().getString(), discId, t);
			return StartResult.ERROR;
		}
	}

	/** Stops this admin's monitor if one is running. Idempotent. Server thread. */
	public void stop(ServerPlayer admin) {
		stopByUuid(admin.getUUID(), "stopped");
	}

	/** Tears down a leaving player's monitor. Server thread (DISCONNECT event). */
	public void onPlayerDisconnect(ServerPlayer admin) {
		stopByUuid(admin.getUUID(), "disconnect");
	}

	/**
	 * Stops any monitor of {@code discId}. Called before a disc edit/delete reconciles feeds:
	 * a monitor holds an independent ref on the same {@code disc:<id>} feed key, so without
	 * this the ref-count could not reach zero and the keep-alive would re-acquire the STALE
	 * feed (e.g. keeping a rotated RTMP stream key's old listener alive). Server thread.
	 */
	public void stopForDisc(String discId) {
		if (discId == null || monitors.isEmpty()) {
			return;
		}
		for (Map.Entry<UUID, Monitor> entry : new ArrayList<>(monitors.entrySet())) {
			if (discId.equals(entry.getValue().discId)) {
				stopByUuid(entry.getKey(), "disc changed");
			}
		}
	}

	/** Stops every monitor - called on SERVER_STOPPING before the feed registry shuts down. */
	public void stopAll() {
		if (monitors.isEmpty()) {
			return;
		}
		for (UUID uuid : new ArrayList<>(monitors.keySet())) {
			stopByUuid(uuid, "server-stop");
		}
	}

	/**
	 * Per-tick boss-bar refresh and auto-stop. Updates each monitor's progress
	 * (from the sink's smoothed RMS level) and name (feed state + now-playing),
	 * and stops any monitor whose deadline elapsed or whose admin has left. Server
	 * thread (END_SERVER_TICK).
	 */
	public void onEndServerTick(MinecraftServer server) {
		if (monitors.isEmpty()) {
			return;
		}
		List<UUID> timedOut = null;
		List<UUID> gone = null;
		for (Monitor monitor : monitors.values()) {
			// Safety net: DISCONNECT normally tears a monitor down first, but if the
			// admin is somehow gone, drop it here too.
			if (server.getPlayerList().getPlayer(monitor.adminUuid) == null) {
				(gone = lazy(gone)).add(monitor.adminUuid);
				continue;
			}
			if (--monitor.remainingTicks <= 0) {
				(timedOut = lazy(timedOut)).add(monitor.adminUuid);
				continue;
			}
			// One monitor's boss-bar/session-read failure must neither abort the other
			// monitors' refresh nor skip the queued gone/timedOut teardowns below (which
			// would leak feeds/channels), nor propagate into MinecraftServer.tickServer.
			// Mirror the delivery loop's guard around sink.accept and stopByUuid's per-step
			// guards: log and move on.
			try {
				refresh(monitor);
			} catch (Throwable t) {
				SavePointRadioMod.LOGGER.warn("[radio] monitor refresh failed station={}", monitor.discId, t);
			}
		}
		if (gone != null) {
			for (UUID uuid : gone) {
				stopByUuid(uuid, "disconnect");
			}
		}
		if (timedOut != null) {
			for (UUID uuid : timedOut) {
				stopByUuid(uuid, "timeout");
			}
		}
	}

	// ------------------------------------------------------------------
	// Internals (server thread only)
	// ------------------------------------------------------------------

	private void refresh(Monitor monitor) {
		// Progress from the live level meter. Quantized to hundredths so a slowly
		// varying level does not emit a boss-bar packet every single tick
		// (setProgress no-ops when the value is unchanged).
		float level = monitor.sink.level();
		if (level < 0f) {
			level = 0f;
		} else if (level > 1f) {
			level = 1f;
		}
		monitor.bossBar.setProgress(Math.round(level * 100f) / 100f);

		// Name reflects whether real upstream audio is flowing (the feed's decoded
		// frame count, not the sink's - the delivery loop feeds the sink silence
		// while pre-rolling / waiting, so only feedFramesRead distinguishes a bound
		// RTMP listener with no publisher from one actually receiving) plus the
		// now-playing track when a jukebox session for this disc has one.
		FeedRegistry registry = FeedRegistry.get();
		boolean receiving = registry.feedIsStreaming(monitor.feedKey) && registry.feedFramesRead(monitor.feedKey) > 0;
		RadioSession.NowPlaying np = RadioManager.get().sessionForDisc(monitor.discId)
				.map(RadioSession::nowPlaying)
				.orElse(null);
		String title = np != null && np.live() ? np.title() : null;
		String artist = np != null && np.live() ? np.artist() : null;
		// NUL-joined so a title containing our separator can never forge a false match.
		String nameKey = (receiving ? "r" : "w") + '\0'
				+ (title == null ? "" : title) + '\0' + (artist == null ? "" : artist);
		if (!nameKey.equals(monitor.lastNameKey)) {
			monitor.lastNameKey = nameKey;
			monitor.bossBar.setName(buildName(monitor.discName, receiving, title, artist));
		}
	}

	private void stopByUuid(UUID uuid, String reason) {
		Monitor monitor = monitors.remove(uuid);
		if (monitor == null) {
			return;
		}
		// Detach the sink first so no more frames arrive, then release the feed
		// lease, then close the sink (flush + encoder close; the AudioSink contract
		// tolerates the one in-flight frame that may still land), then drop the boss
		// bar. Mirrors RadioSession.releaseResources so nothing leaks.
		try {
			monitor.feed.removeSink(monitor.sink);
		} catch (Throwable t) {
			SavePointRadioMod.LOGGER.warn("[radio] monitor removeSink failed station={}", monitor.discId, t);
		}
		try {
			FeedRegistry.get().release(monitor.feedKey);
		} catch (Throwable t) {
			SavePointRadioMod.LOGGER.warn("[radio] monitor feed release failed station={}", monitor.discId, t);
		}
		try {
			monitor.sink.close();
		} catch (Throwable t) {
			SavePointRadioMod.LOGGER.warn("[radio] monitor sink close failed station={}", monitor.discId, t);
		}
		try {
			monitor.bossBar.removeAllPlayers();
			monitor.bossBar.setVisible(false);
		} catch (Throwable t) {
			SavePointRadioMod.LOGGER.warn("[radio] monitor boss bar teardown failed station={}", monitor.discId, t);
		}
		SavePointRadioMod.LOGGER.info("[radio] monitor stop station={} reason={}", monitor.discId, reason);
	}

	/**
	 * The boss-bar title. Untrusted now-playing {@code title}/{@code artist} are
	 * appended with literal concatenation (never a {@code %s} format), matching the
	 * dialog code, so a value containing {@code %} can never break formatting.
	 */
	private static Component buildName(String discName, boolean receiving, String title, String artist) {
		MutableComponent name = Component.literal("♪ Monitoring ").append(Component.literal(discName));
		if (!receiving) {
			return name.append(Component.literal(" - waiting for stream"));
		}
		if (title != null && !title.isBlank()) {
			StringBuilder track = new StringBuilder(" - ").append(title);
			if (artist != null && !artist.isBlank()) {
				track.append(" - ").append(artist);
			}
			return name.append(Component.literal(track.toString()));
		}
		return name.append(Component.literal(" - receiving"));
	}

	private static List<UUID> lazy(List<UUID> list) {
		return list != null ? list : new ArrayList<>();
	}

	/** One running monitor. All fields are written on the server thread; {@code sink.level()} is volatile. */
	private static final class Monitor {
		final UUID adminUuid;
		final String discId;
		final String discName;
		/** Namespaced feed-registry key ({@code disc:<id>}) used to release the lease. */
		final String feedKey;
		final StationFeed feed;
		final MonitorChannel sink;
		final ServerBossEvent bossBar;
		/** Ticks left before auto-stop; decremented each server tick. */
		int remainingTicks;
		/** Last boss-bar name descriptor, so the name packet is only resent when it changes. */
		String lastNameKey = "";

		Monitor(UUID adminUuid, String discId, String discName, String feedKey,
				StationFeed feed, MonitorChannel sink, ServerBossEvent bossBar, int remainingTicks) {
			this.adminUuid = adminUuid;
			this.discId = discId;
			this.discName = discName;
			this.feedKey = feedKey;
			this.feed = feed;
			this.sink = sink;
			this.bossBar = bossBar;
			this.remainingTicks = remainingTicks;
		}
	}

	private MonitorService() {
	}
}
