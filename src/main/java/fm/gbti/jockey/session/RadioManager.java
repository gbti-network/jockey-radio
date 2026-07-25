package fm.gbti.jockey.session;

import fm.gbti.jockey.JockeyRadioMod;
import fm.gbti.jockey.config.RadioConfig;
import fm.gbti.jockey.disc.DiscDefinition;
import fm.gbti.jockey.disc.DiscRegistry;
import fm.gbti.jockey.disc.RadioDiscFactory;
import fm.gbti.jockey.voicechat.VoiceChatBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.JukeboxSongPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Owns every {@link RadioSession}, keyed by jukebox position. Sessions track
 * DISC PRESENCE, not vanilla play state: while a radio disc sits in a jukebox
 * the session is live, even after vanilla stops the song at
 * {@code length_in_seconds}.
 *
 * <p>All state transitions run on the server thread (the mixin hooks and
 * Fabric lifecycle events that call in already do; {@link #checkServerThread}
 * logs loudly if that ever stops being true). The map itself is concurrent so
 * read-only helpers such as {@link #getSessionCount()} are safe anywhere.</p>
 */
public final class RadioManager {

	private static final RadioManager INSTANCE = new RadioManager();
	private static final long EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 5;
	/** Throttle for the self-healing sink-upgrade sweep (~1s at 20 TPS). */
	private static final int SINK_UPGRADE_INTERVAL_TICKS = 20;

	private final ConcurrentHashMap<GlobalPos, RadioSession> sessions = new ConcurrentHashMap<>();

	/**
	 * Jukeboxes seen by {@code BLOCK_ENTITY_LOAD} this tick. The event fires
	 * before the block entity's NBT is necessarily loaded, so the disc check
	 * is deferred to {@code END_SERVER_TICK} (still the server thread).
	 */
	private final ArrayDeque<GlobalPos> pendingLoadChecks = new ArrayDeque<>();

	/**
	 * Volatile because the voicechat plugin reads it from whatever thread SVC
	 * fires its server-started event on (to {@code server.execute} the sink
	 * upgrade back onto the server thread). All writes stay on the server thread.
	 */
	private volatile MinecraftServer server;
	private boolean shuttingDown;
	/** Server-thread-only tick counter throttling {@link #sweepSinkUpgrades}. */
	private int sinkUpgradeTicks;

	public static RadioManager get() {
		return INSTANCE;
	}

	/**
	 * The running server, or null outside a server run. Safe on any thread;
	 * used by the voicechat plugin to schedule work onto the server thread.
	 */
	public MinecraftServer currentServer() {
		return server;
	}

	// ------------------------------------------------------------------
	// Lifecycle (wired up in JockeyRadioMod)
	// ------------------------------------------------------------------

	public void onServerStarting(MinecraftServer server) {
		this.server = server;
		this.shuttingDown = false;
		// The integrated server can start several times in one JVM; re-arm the
		// registry's shutdown latch so later worlds get a feed executor again.
		FeedRegistry.get().reset();
		// Pick up any disc registry edits made since the last run (or since mod
		// init) so a fresh server run reflects the current discs.json.
		DiscRegistry.get().load();
		// Decide ffmpeg vs stub feeds for this run (logs its verdict; falls
		// back to stubs if the binary is unusable so the server still runs).
		FeedRegistry.get().runStartupDoctor();
		// sow-008: now that real-vs-stub is decided, arm RTMP idle keep-alive and
		// bind the always-on listeners for every RTMP-receive disc (no-op if the
		// disc set has none or rtmpKeepAlive is off). Must follow the doctor so the
		// anchors are real ffmpeg feeds, not stubs.
		RtmpKeepAlive.get().arm();
		// Opt-in LIVE indicator plumbing; a silent no-op unless
		// nowPlayingPollSeconds > 0 in config (default 0 = disabled).
		NowPlayingPoller.get().start();
	}

	/**
	 * A jukebox block entity was loaded (chunk load or placement). Its item
	 * may not be deserialized yet, so just queue a check for end of tick.
	 */
	public void onJukeboxLoaded(ServerLevel level, BlockPos pos) {
		checkServerThread("onJukeboxLoaded");
		if (shuttingDown) {
			return;
		}
		pendingLoadChecks.add(GlobalPos.of(level.dimension(), pos.immutable()));
	}

	/**
	 * A jukebox block entity is about to unload. Usually a chunk unload, but
	 * block removal with flag 256 ({@code UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS},
	 * e.g. strict {@code /setblock} or structure placement) skips
	 * {@code preRemoveSideEffects} yet still fires {@code BLOCK_ENTITY_UNLOAD}
	 * from {@code removeBlockEntity} - in that case the block is already gone,
	 * so the session must stop as {@code broken}, not suspend. Genuine chunk
	 * unloads keep the jukebox in the (still-loaded) chunk, or the chunk is
	 * already unreachable, and take the suspend path.
	 */
	public void onJukeboxUnloaded(ServerLevel level, BlockPos pos) {
		checkServerThread("onJukeboxUnloaded");
		RadioSession session = sessions.get(GlobalPos.of(level.dimension(), pos));
		if (session == null || session.state() != RadioSession.State.ACTIVE) {
			return;
		}
		LevelChunk chunk = chunkNow(level, pos);
		if (chunk != null && chunk.getBlockState(pos).getBlock() != Blocks.JUKEBOX) {
			stop(session, "broken");
			return;
		}
		suspend(session, "unload");
	}

	/** Drains this tick's deferred load checks and runs the sink-upgrade sweep. */
	public void onEndServerTick(MinecraftServer server) {
		if (!pendingLoadChecks.isEmpty()) {
			checkServerThread("onEndServerTick");
			GlobalPos globalPos;
			while ((globalPos = pendingLoadChecks.poll()) != null) {
				checkLoadedJukebox(server, globalPos);
			}
		}
		sweepSinkUpgrades();
		sweepFinishedSessions();
	}

	/**
	 * sow-012: latch any ACTIVE session whose feed has reached END-OF-INPUT (a repeat-off finite source
	 * - FILE or non-live VOD - that streamed once to its end and drained to silence) as finished,
	 * releasing it. A finished session then stays parked+silent, is never re-fed on resume, and the
	 * jukebox mixin finishes the vanilla song (particles stop). Server thread (END_SERVER_TICK); a cheap
	 * per-session volatile read, and the transition fires at most once per disc.
	 */
	private void sweepFinishedSessions() {
		if (sessions.isEmpty()) {
			return;
		}
		for (RadioSession session : sessions.values()) {
			if (session.state() == RadioSession.State.ACTIVE && !session.isFinished()
					&& "ended".equals(FeedRegistry.get().feedPlayoutState(session.identity()))) {
				session.markFinished();
				JockeyRadioMod.LOGGER.info("[jockey] disc played to end (repeat off) {} station={}",
						session.describePos(), session.stationKey());
			}
		}
	}

	/**
	 * Single entry point for "the disc registry changed" (create / edit / delete / reload).
	 * Rebuilds the live sessions the change actually affects, then reconciles the RTMP
	 * keep-alive anchors.
	 *
	 * <p><b>Why sessions must be stopped before the anchors reconcile.</b> A feed is shared
	 * and ref-counted, and {@code release} only stops it at zero refs. When an RTMP disc was
	 * edited while playing, the keep-alive released its anchor (2&nbsp;&rarr;&nbsp;1) and
	 * immediately re-acquired - getting back the SAME feed, still listening on the old port
	 * with the old stream key - and then recorded the new definition as anchored, so no later
	 * reconcile ever retried. The rotated key silently did not take effect until a restart.
	 * Dropping every session's reference FIRST lets the count reach zero, so the anchor pass
	 * builds a genuinely fresh feed; the sessions then re-acquire and join it.</p>
	 *
	 * <p>Deleting a disc had the mirror problem: nothing reconciled sessions at all, so a
	 * deleted disc kept streaming, kept its RTMP port bound, and kept its token in use until
	 * the disc was ejected or the server restarted. It now becomes an inert session - still
	 * tracked, so ejecting works, but silent.</p>
	 *
	 * <p>Presentation-only edits (colour, base disc, name style) leave the fingerprint
	 * unchanged and never interrupt playback.</p>
	 */
	public void onDiscsChanged() {
		List<RestartRequest> restarts = List.of();
		if (server != null && !shuttingDown) {
			checkServerThread("onDiscsChanged");
			restarts = stopSessionsNeedingRebuild();
		}
		// Always runs: it self-guards on `armed`, so the startup load is a no-op.
		RtmpKeepAlive.get().onDiscsChanged();
		// Only ACTIVE sessions are restarted. A SUSPENDED session (its chunk unloaded) is left
		// stopped so it rebuilds lazily and correctly on the next chunk load - reactivating it
		// here would spawn a live feed and an SVC channel for a jukebox nobody can hear, which
		// nothing would ever suspend again (the unload event already fired).
		for (RestartRequest request : restarts) {
			start(request.pos(), discResolution(request.discId()));
		}
	}

	/** An ACTIVE session stopped by {@link #onDiscsChanged()} that must be re-activated after it. */
	private record RestartRequest(GlobalPos pos, String discId) {
	}

	/** Outcome of a {@link #healPlacedDiscs()} pass, for the admin command to report. */
	public record HealResult(int healed, int discSessions, int unreachable) {
	}

	/**
	 * Re-stamps every ACTIVE registry-disc session's placed disc item, in place, from its current
	 * registry definition. This repairs discs whose item NBT was damaged by an upgrade - notably the
	 * "SavePoint Radio" -> "Jockey Radio" rename, which left already-placed discs unable to
	 * decode; the world then saved them with their custom jukebox-song override STRIPPED, so the base
	 * vanilla disc track leaks over the radio. Rebuilding the item from the registry restores the
	 * silent {@link RadioDiscFactory#RADIO_SONG_KEY} song (no leak) and refreshes stale name / lore /
	 * custom_data at the same time.
	 *
	 * <p>A disc whose placed item already matches the freshly built one is left untouched, so healthy
	 * discs take no audio blip. Damaged/stale ones are replaced eject-then-insert
	 * ({@code setTheItem(EMPTY)} then {@code setTheItem(fresh)}): that clears the leaked vanilla song
	 * and drives the normal {@code setTheItem} hook to stop and restart the radio session cleanly.
	 * Only jukeboxes in LOADED chunks are reachable (a suspended session's block entity is gone), so
	 * the admin runs this near the discs; {@link HealResult#unreachable()} reports how many were out
	 * of range. Server thread.</p>
	 */
	public HealResult healPlacedDiscs() {
		checkServerThread("healPlacedDiscs");
		if (server == null || shuttingDown) {
			return new HealResult(0, 0, 0);
		}
		int healed = 0;
		int discSessions = 0;
		int unreachable = 0;
		for (RadioSession session : List.copyOf(sessions.values())) {
			String identity = session.identity();
			if (!identity.startsWith("disc:")) {
				continue; // inert (unknown disc) or a config station: nothing to rebuild from the registry
			}
			String discId = identity.substring("disc:".length());
			DiscDefinition def = DiscRegistry.get().get(discId).orElse(null);
			if (def == null) {
				continue;
			}
			discSessions++;
			GlobalPos pos = session.pos();
			ServerLevel level = server.getLevel(pos.dimension());
			if (level == null || !(level.getBlockEntity(pos.pos()) instanceof JukeboxBlockEntity jukebox)) {
				unreachable++; // chunk unloaded - not reachable from here, will rebuild fresh on reload
				continue;
			}
			Optional<ItemStack> fresh = RadioDiscFactory.createFromDefinition(level.registryAccess(), def);
			if (fresh.isEmpty() || ItemStack.matches(jukebox.getTheItem(), fresh.get())) {
				continue; // song missing, or the placed item is already correct: no blip
			}
			jukebox.setTheItem(ItemStack.EMPTY);
			jukebox.setTheItem(fresh.get());
			healed++;
			JockeyRadioMod.LOGGER.info("[jockey] healed placed disc {} {}", discId, session.describePos());
		}
		return new HealResult(healed, discSessions, unreachable);
	}

	/**
	 * Stops every registry-disc session whose definition changed, appeared or disappeared,
	 * and returns the ACTIVE ones to re-activate. Sessions for config stations and unaffected
	 * discs are left alone.
	 *
	 * <p>State-aware, because a disc edit reaches sessions in every state:
	 * <ul>
	 *   <li>ACTIVE (playing): stop and queue a restart, so the edit takes effect live.</li>
	 *   <li>SUSPENDED (chunk unloaded): stop and DROP it - reactivating would stream to an
	 *       unloaded chunk forever; the normal chunk-load path rebuilds it fresh.</li>
	 *   <li>FINISHED (repeat-off, already played once): leave it entirely - it holds no feed,
	 *       and restarting would replay a disc that has legitimately stopped.</li>
	 * </ul></p>
	 */
	private List<RestartRequest> stopSessionsNeedingRebuild() {
		if (sessions.isEmpty()) {
			return List.of();
		}
		List<RestartRequest> restarts = new ArrayList<>();
		for (RadioSession session : List.copyOf(sessions.values())) {
			String identity = session.identity();
			String discId;
			if (identity.startsWith("disc:")) {
				discId = identity.substring("disc:".length());
			} else if (identity.startsWith("inert:")) {
				// A disc id that did not exist when the session started. If it exists now
				// (the admin just created it), the session should start playing it.
				discId = identity.substring("inert:".length());
			} else {
				continue; // config station - the disc registry cannot affect it
			}
			DiscDefinition def = DiscRegistry.get().get(discId).orElse(null);
			String nowIdentity = def == null ? "inert:" + discId : "disc:" + discId;
			String nowFingerprint = def == null ? "" : def.ingestFingerprint();
			if (identity.equals(nowIdentity) && session.fingerprint().equals(nowFingerprint)) {
				continue;
			}
			// A play-once disc that already finished holds no feed and must not replay on an edit.
			if (session.isFinished()) {
				continue;
			}
			// Tear down any audible monitor of this disc first, so its independent feed ref does
			// not keep a stale feed alive past the keep-alive reconcile.
			MonitorService.get().stopForDisc(discId);
			boolean wasActive = session.state() == RadioSession.State.ACTIVE;
			stop(session, def == null ? "disc deleted" : "disc edited");
			if (wasActive) {
				restarts.add(new RestartRequest(session.pos(), discId));
			}
		}
		return restarts;
	}

	/**
	 * Upgrades every ACTIVE session that is still on a placeholder (null) sink to
	 * a real voicechat channel. Called (via {@code server.execute}, so on the
	 * server thread) by the voicechat plugin when the SVC server api becomes
	 * ready: sessions resumed during world load are on null sinks by then - SVC
	 * starts after world load. Sessions that already hold a voicechat sink are
	 * left alone, so restarting SVC does not needlessly churn working channels.
	 */
	public void reopenActiveSinks(String reason) {
		checkServerThread("reopenActiveSinks");
		if (shuttingDown) {
			return;
		}
		upgradePlaceholderSinks(reason);
	}

	/**
	 * Self-healing backstop for the edge-triggered {@link #reopenActiveSinks}: a
	 * throttled server-thread sweep that upgrades any ACTIVE session still on a
	 * placeholder sink once SVC is available. Covers the cases the one-shot event
	 * cannot recover from on its own - SVC's started event firing while
	 * {@link #currentServer()} was momentarily null (so the reopen was skipped),
	 * or a session's first upgrade attempt transiently failing - without which
	 * such a session would stay silent for the rest of the world session.
	 */
	private void sweepSinkUpgrades() {
		if (shuttingDown || sessions.isEmpty()) {
			return;
		}
		if (++sinkUpgradeTicks < SINK_UPGRADE_INTERVAL_TICKS) {
			return;
		}
		sinkUpgradeTicks = 0;
		if (!VoiceChatBridge.isAvailable()) {
			return;
		}
		upgradePlaceholderSinks("voicechat-ready-sweep");
	}

	/**
	 * Reopens the sink of every ACTIVE session currently on a placeholder (null)
	 * sink, upgrading it to a real voicechat channel. Sessions already on a
	 * voicechat sink are skipped - reopening one would tear down and recreate a
	 * working channel/encoder (a native teardown plus a one-frame gap) for no
	 * gain. Server thread only.
	 */
	private void upgradePlaceholderSinks(String reason) {
		for (RadioSession session : sessions.values()) {
			if (session.isOnPlaceholderSink()) {
				JockeyRadioMod.LOGGER.info("[jockey] sink reopen {} station={} reason={}",
						session.describePos(), session.stationKey(), reason);
				session.reopenSink(server);
			}
		}
	}

	/** Suspends every active session and stops the shared feed machinery. */
	public void shutdownAll() {
		checkServerThread("shutdownAll");
		shuttingDown = true;
		pendingLoadChecks.clear();
		for (RadioSession session : sessions.values()) {
			if (session.state() == RadioSession.State.ACTIVE) {
				suspend(session, "shutdown");
			}
		}
		sessions.clear();
		NowPlayingPoller.get().stop();
		FeedRegistry.get().shutdown(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		JockeyRadioMod.LOGGER.info("[jockey] manager shut down");
	}

	// ------------------------------------------------------------------
	// Disc-presence transitions (called from the jukebox mixins)
	// ------------------------------------------------------------------

	/**
	 * The jukebox's item changed ({@code setTheItem} or
	 * {@code setSongItemWithoutPlaying}). Reconciles the session at that
	 * position with whatever disc is now present.
	 */
	public void onJukeboxItemChanged(ServerLevel level, BlockPos pos, ItemStack item) {
		checkServerThread("onJukeboxItemChanged");
		if (shuttingDown) {
			return;
		}
		GlobalPos globalPos = GlobalPos.of(level.dimension(), pos.immutable());
		Resolution resolution = resolveDisc(item);
		RadioSession existing = sessions.get(globalPos);

		if (resolution == null) {
			// No radio disc (empty slot, vanilla disc, anything else).
			if (existing != null) {
				stop(existing, item.isEmpty() ? "ejected" : "replaced");
			}
			return;
		}

		// A radio disc is present. On the world-load/restart resume path the
		// persisted song may have already elapsed before the save (song == null),
		// so vanilla's JukeboxSongPlayer.tick emits no note particles until the
		// disc is re-inserted. Silently re-arm the animation here (radio discs
		// only) so the PLAYING look resumes on its own.
		ensureRadioAnimationPlaying(level, pos, item);

		if (existing != null) {
			if (existing.identity().equals(resolution.identity())) {
				// Same disc; make sure the session is live. The world-load /
				// restart resume path reaches here via BLOCK_ENTITY_LOAD plus
				// the deferred end-of-tick disc check (checkLoadedJukebox) -
				// NOT via the setSongItemWithoutPlaying hook, which vanilla
				// 26.2 never calls (loadAdditional writes the item field
				// directly).
				if (existing.state() != RadioSession.State.ACTIVE) {
					resume(existing);
				}
				return;
			}
			stop(existing, "replaced");
		}
		start(globalPos, resolution);
	}

	/**
	 * Resolves the disc in a jukebox to a session {@link Resolution}, or null
	 * when the item is not a radio disc at all. A {@code disc_id} in
	 * {@code custom_data} routes to the admin {@link DiscRegistry} (an unknown
	 * id yields an INERT resolution - a tracked but silent session, never a
	 * crash); otherwise sow-001's config {@code station} key path is used.
	 */
	private Resolution resolveDisc(ItemStack item) {
		String discId = RadioDiscFactory.readDiscId(item).orElse(null);
		if (discId != null) {
			return discResolution(discId);
		}
		String stationKey = RadioDiscFactory.readStationKey(item).orElse(null);
		if (stationKey != null) {
			return new Resolution("station:" + stationKey, stationKey, configStationSource(stationKey), "");
		}
		return null;
	}

	/**
	 * The resolution for a registry disc id. Split out of {@link #resolveDisc} so
	 * {@link #onDiscsChanged()} can rebuild a session without the jukebox's {@link ItemStack}.
	 */
	private Resolution discResolution(String discId) {
		DiscDefinition def = DiscRegistry.get().get(discId).orElse(null);
		if (def == null) {
			return new Resolution("inert:" + discId, discId, null, "");
		}
		return new Resolution("disc:" + discId, discId, discFeedSource(discId, def),
				def.ingestFingerprint());
	}

	/**
	 * Feed source for an admin registry disc (HLS pull or RTMP receive). Public
	 * and static so {@link MonitorService} builds the SAME source (same
	 * namespaced {@code disc:} key) the session path uses: a monitor that acquires
	 * it shares a live feed when the disc is already playing, or starts a
	 * ref-counted transient one otherwise.
	 */
	public static FeedSource discFeedSource(String discId, DiscDefinition def) {
		int radius = def.radiusBlocks;
		// Namespaced registry key ("disc:") keeps admin discs disjoint from config
		// stations in the shared-feed map; the feed itself still logs the bare discId.
		// The key intentionally MATCHES RadioSession.identity() ("disc:" + id): several call
		// sites (sweepFinishedSessions, MusicSuppressor, NowPlayingHud) look a feed's state up
		// by the session identity, so the two must stay equal.
		//
		// Known minor limitation: two jukeboxes holding the SAME play-once (repeat=off) disc
		// share this one feed, so a second jukebox that receives it mid-track hears the tail and
		// both stop together at end-of-input. Re-inserting replays from the top. Giving each
		// jukebox its own feed would need the identity/key coupling above to be broken first.
		String key = "disc:" + discId;
		return new FeedSource(key, discId, radius, () -> {
			RadioConfig config = RadioConfig.get();
			return switch (def.sourceType) {
				case HLS_PULL -> new HlsPullFeed(discId, StationEndpoints.forDiscResolved(def, config),
						config.ffmpegBinary, config.maxBufferFrames(), config.targetFrames(),
						config.prerollFrames(), config.reconnectBackoffMaxSeconds,
						config.reconnectPreserveBuffer, config.reanchorLowWaterFrames(),
						config.reanchorSustainFrames(), config.smoothingMode(), config.maxStretchPercent(),
						config.hlsStartBehindSegments);
				case FILE -> new FileLoopFeed(discId, StationEndpoints.forDiscResolved(def, config),
						config.ffmpegBinary, config.maxBufferFrames(), config.targetFrames(),
						config.prerollFrames(), config.reconnectBackoffMaxSeconds,
						config.reconnectPreserveBuffer, config.reanchorLowWaterFrames(),
						config.reanchorSustainFrames(), config.smoothingMode(), config.maxStretchPercent(), def.repeat);
				// sow-013: PLATFORM is gated OFF by default. This is the load-bearing check - the UI
				// also hides the source type, but a disc hand-written into discs.json (or created
				// while the gate was open, then the gate closed) must not resolve or stream either.
				// Fails SOFT: a stub feed keeps the session alive and silent instead of throwing on
				// the feed thread, matching how a missing ffmpeg degrades.
				case PLATFORM -> {
					if (!config.enablePlatformLinks) {
						JockeyRadioMod.LOGGER.warn(
								"[jockey] platform disc '{}' not started: platform links are disabled. "
										+ "Set enablePlatformLinks=true in jockey_radio.json to enable them - "
										+ "doing so is an explicit acceptance that you have the rights to "
										+ "rebroadcast the linked content on your server.", discId);
						yield new StubStationFeed(discId);
					}
					yield new LinkResolvedFeed(discId, StationEndpoints.forDiscResolved(def, config),
							config.ytdlpBinary, config.ytdlpExtraArgs, config.ffmpegBinary, config.maxBufferFrames(),
							config.targetFrames(), config.prerollFrames(), config.reconnectBackoffMaxSeconds,
							config.reconnectPreserveBuffer, config.reanchorLowWaterFrames(),
							config.reanchorSustainFrames(), config.smoothingMode(), config.maxStretchPercent(),
							def.repeat);
				}
				case RTMP_RECEIVE -> new RtmpReceiveFeed(discId, def.rtmpBind, def.rtmpPort, def.streamKey,
						config.ffmpegBinary, config.maxBufferFrames(), config.targetFrames(),
						config.prerollFrames(), config.reconnectBackoffMaxSeconds,
						config.reconnectPreserveBuffer, config.reanchorLowWaterFrames(),
						config.reanchorSustainFrames(), config.smoothingMode(), config.maxStretchPercent());
			};
		});
	}

	/** Feed source for a sow-001 static config station (unchanged behavior). */
	private static FeedSource configStationSource(String stationKey) {
		RadioConfig.Station snapshot = RadioConfig.get().stations.get(stationKey);
		int radius = snapshot != null ? snapshot.radiusBlocks : 32;
		// Namespaced registry key ("station:") is disjoint from the admin "disc:"
		// namespace; the feed still logs the bare station key (sow-001 unchanged).
		return new FeedSource("station:" + stationKey, stationKey, radius, () -> {
			RadioConfig config = RadioConfig.get();
			RadioConfig.Station station = config.stations.get(stationKey);
			if (station == null || station.url == null || station.url.isBlank()) {
				JockeyRadioMod.LOGGER.warn(
						"[jockey] no configured url for station={} - using stub feed", stationKey);
				return null;
			}
			return new HlsPullFeed(stationKey, StationEndpoints.forUrl(station.url, station.token),
					config.ffmpegBinary, config.maxBufferFrames(), config.targetFrames(),
					config.prerollFrames(), config.reconnectBackoffMaxSeconds,
					config.reconnectPreserveBuffer, config.reanchorLowWaterFrames(),
					config.reanchorSustainFrames(), config.smoothingMode(), config.maxStretchPercent(),
					config.hlsStartBehindSegments);
		});
	}

	/** Resolved disc: namespaced identity, {@code station=} label, feed source (null = inert). */
	private record Resolution(String identity, String label, FeedSource source, String fingerprint) {
	}

	/** The jukebox block is being removed from the world. */
	public void onJukeboxRemoved(ServerLevel level, BlockPos pos) {
		checkServerThread("onJukeboxRemoved");
		RadioSession session = sessions.get(GlobalPos.of(level.dimension(), pos));
		if (session != null) {
			stop(session, "broken");
		}
	}

	/**
	 * {@code BlockEntity.setRemoved} fired. Chunk unloads also call it
	 * ({@code LevelChunk.clearAllBlockEntities}), so only treat it as a break
	 * when the block at the position is no longer a jukebox - the unload case
	 * is handled by {@code BLOCK_ENTITY_UNLOAD} as a suspend. Must only look
	 * at an already-loaded chunk: going through {@code level.getBlockState}
	 * here would file a chunk ticket from inside the unload path and make the
	 * chunk oscillate between loading and unloading forever.
	 */
	public void onJukeboxSetRemoved(ServerLevel level, BlockPos pos) {
		checkServerThread("onJukeboxSetRemoved");
		RadioSession session = sessions.get(GlobalPos.of(level.dimension(), pos));
		if (session == null) {
			return;
		}
		LevelChunk chunk = chunkNow(level, pos);
		if (chunk != null && chunk.getBlockState(pos).getBlock() != Blocks.JUKEBOX) {
			stop(session, "broken");
		}
	}

	/**
	 * Vanilla stopped the jukebox song ({@code JukeboxSongPlayer.stop}). If
	 * the disc is still in the jukebox this is the song timing out at
	 * {@code length_in_seconds} - the session must survive; log the keepalive
	 * the gate greps for. Eject/break paths clear the item first, so they
	 * never reach the keepalive branch.
	 */
	public void onVanillaSongStopped(ServerLevel level, BlockPos pos) {
		checkServerThread("onVanillaSongStopped");
		if (shuttingDown) {
			return;
		}
		LevelChunk chunk = chunkNow(level, pos);
		if (chunk == null || !(chunk.getBlockEntity(pos) instanceof JukeboxBlockEntity jukebox)) {
			return;
		}
		if (!RadioDiscFactory.isRadioDisc(jukebox.getTheItem())) {
			return;
		}
		RadioSession session = sessions.get(GlobalPos.of(level.dimension(), pos));
		if (session != null && session.state() == RadioSession.State.ACTIVE) {
			JockeyRadioMod.LOGGER.info("[jockey] song-elapsed keepalive {} station={}",
					session.describePos(), session.stationKey());
		}
	}

	/**
	 * sow-012: whether the disc at this jukebox has FINISHED (a repeat-off finite source that played
	 * once - latched on the session by {@link #sweepFinishedSessions}). {@code JukeboxSongPlayerMixin}
	 * calls this every tick to drive the vanilla song to finish when the audio ends (so the note
	 * particles / comparator stop then, not at the hour-long song length) while the now-silent session
	 * stays parked in the jukebox. Reads the session's own latched flag, so it survives suspend/resume
	 * (a chunk unload/reload never replays a finished disc). Server thread; one cheap volatile read.
	 */
	public boolean sessionFinishedAt(ServerLevel level, BlockPos pos) {
		RadioSession session = sessions.get(GlobalPos.of(level.dimension(), pos));
		return session != null && session.isFinished();
	}

	// ------------------------------------------------------------------
	// Introspection (dev command / tests)
	// ------------------------------------------------------------------

	/** Number of tracked sessions (active + suspended). Safe on any thread. */
	public int getSessionCount() {
		return sessions.size();
	}

	/**
	 * Read-only view of the tracked sessions. Safe on any thread: the map is
	 * concurrent and the {@link RadioSession} accessors expose only final
	 * fields plus the volatile state.
	 */
	public Collection<RadioSession> getSessions() {
		return Collections.unmodifiableCollection(sessions.values());
	}

	/**
	 * A tracked session playing the given admin registry disc, or empty. Matches on
	 * the namespaced {@code disc:<discId>} identity, so config-station
	 * ({@code station:}) and inert ({@code inert:}) sessions never match. When the
	 * same disc sits in more than one jukebox (several matching sessions in
	 * unspecified map-iteration order), prefer one that actually carries a
	 * now-playing snapshot: the poller only polls ACTIVE sessions, so a SUSPENDED
	 * duplicate would return {@code nowPlaying() == null} and drop the rich detail
	 * line even though a live sibling exists. Falls back to the first match when none
	 * has a snapshot. Used at dialog-build time on the server thread to read a
	 * session's now-playing snapshot, but touches only the concurrent map and
	 * final/volatile session fields, so it is safe on any thread.
	 */
	public Optional<RadioSession> sessionForDisc(String discId) {
		if (discId == null) {
			return Optional.empty();
		}
		String identity = "disc:" + discId;
		RadioSession firstMatch = null;
		for (RadioSession session : sessions.values()) {
			if (identity.equals(session.identity())) {
				if (session.nowPlaying() != null) {
					return Optional.of(session); // prefer a session that has a snapshot
				}
				if (firstMatch == null) {
					firstMatch = session; // remember the first match as a fallback
				}
			}
		}
		return Optional.ofNullable(firstMatch);
	}

	// ------------------------------------------------------------------
	// Internals (server thread only)
	// ------------------------------------------------------------------

	/**
	 * Silently re-arms the vanilla note-particle animation for a jukebox that
	 * holds a radio disc but whose {@link JukeboxSongPlayer} has no song (its
	 * {@code song} field is null - the case vanilla's {@code loadAdditional}
	 * leaves after a save where the radio song had already elapsed, so
	 * {@code JukeboxSongPlayer.tick} spawns no particles). Uses
	 * {@code setSongWithoutPlaying}, exactly like vanilla's own load path, so it
	 * fires no level event, "Now Playing" toast or record sound - only
	 * {@code play} does those, and we never call it here. A no-op when the player
	 * is already playing, so a freshly inserted disc ({@code setTheItem} already
	 * called {@code play}) and the running-tick loop are untouched.
	 *
	 * <p>Gated to radio discs by the caller (reached only when {@code resolveDisc}
	 * returned non-null); a normal music disc that has legitimately finished never
	 * reaches here and stays finished. Server thread only. Wrapped in try/catch:
	 * a radio bug must never disturb a vanilla jukebox. Uses {@link #chunkNow} so
	 * it never files a chunk ticket.</p>
	 */
	private void ensureRadioAnimationPlaying(ServerLevel level, BlockPos pos, ItemStack item) {
		try {
			LevelChunk chunk = chunkNow(level, pos);
			if (chunk == null || !(chunk.getBlockEntity(pos) instanceof JukeboxBlockEntity jukebox)) {
				return;
			}
			JukeboxSongPlayer songPlayer = jukebox.getSongPlayer();
			if (songPlayer.isPlaying()) {
				return;
			}
			JukeboxSong.fromStack(item).ifPresent(song -> songPlayer.setSongWithoutPlaying(song, 0L));
		} catch (Throwable t) {
			JockeyRadioMod.LOGGER.error("[jockey] radio animation resume failed", t);
		}
	}

	private void checkLoadedJukebox(MinecraftServer server, GlobalPos globalPos) {
		ServerLevel level = server.getLevel(globalPos.dimension());
		if (level == null) {
			return;
		}
		BlockPos pos = globalPos.pos();
		LevelChunk chunk = chunkNow(level, pos);
		if (chunk == null) {
			// Chunk vanished again this tick; a suspended session (if any)
			// simply waits for the next load.
			return;
		}
		if (!(chunk.getBlockEntity(pos) instanceof JukeboxBlockEntity jukebox)) {
			// The jukebox was removed between LOAD and end of tick.
			RadioSession session = sessions.get(globalPos);
			if (session != null) {
				stop(session, "broken");
			}
			return;
		}
		onJukeboxItemChanged(level, pos, jukebox.getTheItem());
	}

	private void start(GlobalPos globalPos, Resolution resolution) {
		RadioSession session = new RadioSession(
				resolution.identity(), resolution.label(), resolution.source(), globalPos,
				resolution.fingerprint());
		sessions.put(globalPos, session);
		session.activate(server);
		if (resolution.source() == null) {
			// Unknown/deleted disc id: the session is tracked (so eject/replace
			// stay clean) but holds no feed and plays nothing.
			JockeyRadioMod.LOGGER.info("[jockey] unknown disc id={} (inert)", resolution.label());
		} else {
			JockeyRadioMod.LOGGER.info("[jockey] session start {} station={}",
					session.describePos(), resolution.label());
		}
	}

	private void resume(RadioSession session) {
		session.activate(server);
		JockeyRadioMod.LOGGER.info("[jockey] session resume {} station={}",
				session.describePos(), session.stationKey());
	}

	private void suspend(RadioSession session, String reason) {
		session.suspend();
		JockeyRadioMod.LOGGER.info("[jockey] session suspend {} station={} reason={}",
				session.describePos(), session.stationKey(), reason);
	}

	private void stop(RadioSession session, String reason) {
		sessions.remove(session.pos(), session);
		session.stop();
		JockeyRadioMod.LOGGER.info("[jockey] session stop {} station={} reason={}",
				session.describePos(), session.stationKey(), reason);
	}

	/**
	 * The already-loaded chunk holding {@code pos}, or null. Never files a
	 * chunk ticket - manager code must not force chunks to (re)load.
	 */
	private static LevelChunk chunkNow(ServerLevel level, BlockPos pos) {
		return level.getChunkSource().getChunkNow(
				SectionPos.blockToSectionCoord(pos.getX()),
				SectionPos.blockToSectionCoord(pos.getZ()));
	}

	private void checkServerThread(String what) {
		if (server != null && !server.isSameThread()) {
			JockeyRadioMod.LOGGER.error("[jockey] {} called off the server thread ({}) - this is a bug",
					what, Thread.currentThread().getName(), new IllegalStateException());
		}
	}

	private RadioManager() {
	}
}
