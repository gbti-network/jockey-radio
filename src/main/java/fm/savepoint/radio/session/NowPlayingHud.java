package fm.savepoint.radio.session;

import fm.savepoint.radio.SavePointRadioMod;
import fm.savepoint.radio.config.RadioConfig;
import fm.savepoint.radio.disc.DiscDefinition;
import fm.savepoint.radio.disc.DiscRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-side NOW-PLAYING boss bar: shows every player standing within a playing
 * SavePoint disc's audible radius a vanilla {@link ServerBossEvent} naming the
 * station and its current track/live state. A boss bar is a server→client
 * feature, so this needs no client mod, no networking, and works for every
 * vanilla player.
 *
 * <p>One {@link ServerBossEvent} (GREEN, PROGRESS) is kept per playing session,
 * keyed by the jukebox {@link GlobalPos}. A throttled server-thread sweep
 * (~1s) reconciles bar membership against the online players' positions and
 * refreshes each bar's name; bars whose session stops playing (ejected,
 * suspended, feed gone) are torn down. On player disconnect the player is
 * dropped from every bar, and on server stop every bar is dropped.</p>
 *
 * <p><b>Nearest-only:</b> a player inside two playing discs' radii sees only the
 * NEAREST disc's bar (by Euclidean distance to the jukebox block centre; ties
 * resolved by session iteration order). This avoids stacking two now-playing
 * bars on one screen. Independent of {@code MonitorService}'s admin monitor bar,
 * which serves a different purpose and may show at the same time.</p>
 *
 * <p><b>Threading:</b> every method here runs on the server thread.
 * {@link #onEndServerTick} is driven from {@code END_SERVER_TICK} and
 * {@link #stopAll} from {@code SERVER_STOPPING}, both server-thread events.
 * {@link #onPlayerDisconnect} is invoked from
 * {@code ServerPlayConnectionEvents.DISCONNECT}, which can fire on the Netty
 * event-loop thread for a network-level drop (quit / timeout / TCP reset), so the
 * wiring in {@code SavePointRadioMod} marshals it through {@code server.execute} to
 * run it on the server thread. All boss-bar mutation and every session/feed read
 * therefore stay on the server thread per the guardrail, and the plain
 * {@link #bars} map is only ever touched there. Never blocks the server thread:
 * all reads are cheap map lookups.</p>
 */
public final class NowPlayingHud {

	private static final NowPlayingHud INSTANCE = new NowPlayingHud();

	/**
	 * Ticks between membership/name sweeps (~1s at 20 TPS). The scan walks every
	 * online player against every playing session, so it is throttled rather than
	 * run every tick; a player entering/leaving a radius sees the bar appear/vanish
	 * within one interval, which is imperceptible for a now-playing indicator.
	 */
	private static final int SCAN_INTERVAL_TICKS = 20;

	/**
	 * sow-006 marquee. When a bar's name exceeds {@value #MARQUEE_WIDTH} chars it
	 * scrolls: a window of that width slides across {@code fullText + gap} every
	 * {@value #MARQUEE_ADVANCE_TICKS} ticks (~200&nbsp;ms/char at 20&nbsp;TPS), so long
	 * "title · artist" lines are fully readable. Short names render static + styled.
	 */
	private static final int MARQUEE_WIDTH = 44;
	private static final int MARQUEE_ADVANCE_TICKS = 4;
	private static final String MARQUEE_GAP = "   •   ";

	/**
	 * "Beacon" trigger blocks matched by {@code getBlock()} identity: in
	 * {@code nowPlayingHudMode="beacon"} the now-playing bar shows only while one of
	 * these (or ANY block in the {@link BlockTags#LANTERNS} tag - lantern, soul
	 * lantern, and every copper-lantern variant, tested separately) sits within the
	 * configured cube around the jukebox. Lava matches its block regardless of
	 * source/flowing level. Sea lantern is NOT in the lanterns tag, so it stays here
	 * explicitly. Immutable and identity-hashed (Block has no equals override), so
	 * {@code contains} is a cheap reference lookup.
	 */
	private static final Set<Block> TRIGGER_BLOCKS = Set.of(
			Blocks.SEA_LANTERN,
			Blocks.LAVA,
			Blocks.REDSTONE_BLOCK,
			Blocks.REDSTONE_TORCH);

	/** One bar per playing session, keyed by the jukebox position. Server-thread only. */
	private final Map<GlobalPos, Bar> bars = new HashMap<>();

	/** Server-thread-only tick counter throttling the sweep. */
	private int ticksSinceScan;
	/** Server-thread-only tick counter throttling the (faster) marquee scroll. */
	private int ticksSinceMarquee;

	public static NowPlayingHud get() {
		return INSTANCE;
	}

	/**
	 * Throttled per-tick sweep: reconcile bar membership and names against the
	 * playing sessions and online players. When the HUD is disabled in config, any
	 * bars left over from before the toggle are torn down and nothing else runs.
	 * Server thread (END_SERVER_TICK). A cosmetic bar must never crash the tick, so
	 * the whole sweep is guarded.
	 */
	public void onEndServerTick(MinecraftServer server) {
		if (!RadioConfig.get().nowPlayingHud) {
			// Toggled off (or never on): drop any bars a previous on-state left up.
			if (!bars.isEmpty()) {
				teardownAll("disabled");
			}
			ticksSinceScan = 0;
			ticksSinceMarquee = 0;
			return;
		}
		// Marquee scroll (sow-006): faster than the membership sweep so long titles
		// glide. Only advances already-built scrolling bars; never changes membership,
		// and skips bars whose title fits (marqueeText == null) so they send no packets.
		if (++ticksSinceMarquee >= MARQUEE_ADVANCE_TICKS) {
			ticksSinceMarquee = 0;
			try {
				advanceMarquee();
			} catch (Throwable t) {
				SavePointRadioMod.LOGGER.warn("[jukebox] now-playing marquee failed", t);
			}
		}
		if (++ticksSinceScan >= SCAN_INTERVAL_TICKS) {
			ticksSinceScan = 0;
			try {
				scan(server);
			} catch (Throwable t) {
				SavePointRadioMod.LOGGER.warn("[jukebox] now-playing hud sweep failed", t);
			}
		}
	}

	/**
	 * Drops a leaving player from every bar so no stale {@link ServerPlayer}
	 * reference lingers between sweeps. Runs on the server thread: the DISCONNECT
	 * wiring in {@code SavePointRadioMod} marshals this call through
	 * {@code server.execute}, keeping the {@link #bars} map and every
	 * {@link ServerBossEvent} player-set mutation off the Netty event-loop thread.
	 */
	public void onPlayerDisconnect(ServerPlayer player) {
		if (bars.isEmpty()) {
			return;
		}
		for (Bar bar : bars.values()) {
			try {
				bar.event.removePlayer(player);
			} catch (Throwable ignored) {
				// A closing connection may reject the remove packet; the bar is
				// dropped wholesale on the next sweep anyway.
			}
		}
	}

	/** Drops every bar - called on SERVER_STOPPING. Idempotent. Server thread. */
	public void stopAll() {
		if (bars.isEmpty()) {
			return;
		}
		teardownAll("server-stop");
	}

	// ------------------------------------------------------------------
	// Internals (server thread only)
	// ------------------------------------------------------------------

	private void scan(MinecraftServer server) {
		// Pass 1: the sessions that should carry a bar this sweep (ACTIVE, non-inert,
		// backed by a REAL streaming feed - a stub/inert session shows nothing - and,
		// in "beacon" mode, with a trigger block near the jukebox). The gate is
		// re-evaluated every sweep, so placing/removing a lantern (etc.) shows/hides
		// the bar within one interval via the normal create (below) / Pass-2 teardown.
		// Create missing bars and refresh each name; remember the live keys.
		List<RadioSession> playing = new ArrayList<>();
		Set<GlobalPos> liveKeys = new HashSet<>();
		for (RadioSession session : RadioManager.get().getSessions()) {
			if (!isPlaying(session) || !hudGateAllows(server, session)) {
				continue;
			}
			GlobalPos key = session.pos();
			liveKeys.add(key);
			playing.add(session);
			try {
				Bar bar = bars.get(key);
				if (bar == null) {
					bar = createBar(session);
					bars.put(key, bar);
				}
				refreshName(bar, session);
			} catch (Throwable t) {
				SavePointRadioMod.LOGGER.warn("[jukebox] now-playing hud refresh failed station={}",
						session.stationKey(), t);
			}
		}

		// Pass 2: drop bars whose session no longer plays (ejected, suspended, feed
		// stopped, disc removed). removeAllPlayers + forget = idempotent teardown.
		Iterator<Map.Entry<GlobalPos, Bar>> it = bars.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<GlobalPos, Bar> entry = it.next();
			if (!liveKeys.contains(entry.getKey())) {
				teardownBar(entry.getValue());
				it.remove();
			}
		}

		if (bars.isEmpty()) {
			return;
		}

		// Pass 3: membership. Each online player joins only the NEAREST playing
		// session whose radius contains them (nearest-only, to avoid stacking bars).
		Map<GlobalPos, Set<ServerPlayer>> wanted = new HashMap<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			try {
				RadioSession nearest = nearestAudible(player, playing);
				if (nearest != null) {
					wanted.computeIfAbsent(nearest.pos(), k -> new HashSet<>()).add(player);
				}
			} catch (Throwable t) {
				SavePointRadioMod.LOGGER.warn("[jukebox] now-playing hud membership failed player={}",
						player.getName().getString(), t);
			}
		}

		// Reconcile each bar's audience: remove players no longer wanted, add
		// newcomers. Both ServerBossEvent ops are idempotent and only send a packet
		// on an actual change, so re-adding an existing member is free.
		for (Map.Entry<GlobalPos, Bar> entry : bars.entrySet()) {
			Set<ServerPlayer> want = wanted.get(entry.getKey());
			ServerBossEvent event = entry.getValue().event;
			for (ServerPlayer present : new ArrayList<>(event.getPlayers())) {
				if (want == null || !want.contains(present)) {
					event.removePlayer(present);
				}
			}
			if (want != null) {
				for (ServerPlayer player : want) {
					event.addPlayer(player);
				}
			}
		}
	}

	/**
	 * The nearest playing session whose audible radius contains {@code player}
	 * (same dimension, Euclidean distance to the jukebox block centre within the
	 * session's {@code radiusBlocks}), or null when the player is outside every
	 * radius. Ties resolve to the first in iteration order.
	 */
	private static RadioSession nearestAudible(ServerPlayer player, List<RadioSession> playing) {
		RadioSession nearest = null;
		double nearestSq = Double.MAX_VALUE;
		double px = player.getX();
		double py = player.getY();
		double pz = player.getZ();
		for (RadioSession session : playing) {
			GlobalPos jukebox = session.pos();
			if (!player.level().dimension().equals(jukebox.dimension())) {
				continue;
			}
			int radius = session.radiusBlocks();
			if (radius <= 0) {
				continue;
			}
			BlockPos pos = jukebox.pos();
			double dx = (pos.getX() + 0.5) - px;
			double dy = (pos.getY() + 0.5) - py;
			double dz = (pos.getZ() + 0.5) - pz;
			double distSq = dx * dx + dy * dy + dz * dz;
			if (distSq <= (double) radius * radius && distSq < nearestSq) {
				nearestSq = distSq;
				nearest = session;
			}
		}
		return nearest;
	}

	/** A session eligible for a bar: ACTIVE, not inert, backed by a real (non-stub) feed. */
	private static boolean isPlaying(RadioSession session) {
		if (session.state() != RadioSession.State.ACTIVE || session.isInert()) {
			return false;
		}
		// identity() is the namespaced feed-registry key; a real streaming feed
		// (not a StubStationFeed) means the session can actually play audio.
		return FeedRegistry.get().feedIsStreaming(session.identity());
	}

	/**
	 * Whether the configured HUD gate mode admits this session's bar this sweep.
	 * {@code "always"} admits every playing session; {@code "beacon"} (the default,
	 * and the fallback for any other value) admits only sessions with a trigger
	 * block near the jukebox. Server thread.
	 */
	private static boolean hudGateAllows(MinecraftServer server, RadioSession session) {
		if ("always".equals(RadioConfig.get().nowPlayingHudMode)) {
			return true;
		}
		// "beacon" (default) - require a nearby trigger block.
		return hasTriggerBlock(server, session.pos());
	}

	/**
	 * True when any cell of the cube centred on the jukebox holds a "beacon" block:
	 * any block in the {@link BlockTags#LANTERNS} tag (lantern, soul lantern, and
	 * every copper-lantern variant) or one of {@link #TRIGGER_BLOCKS} (sea lantern,
	 * lava, redstone block, redstone torch). The cube "radius" per axis is
	 * {@link RadioConfig#nowPlayingHudTriggerRadius} (default 4 = 9x9x9). Server
	 * thread.
	 *
	 * <p>Reads only already-loaded chunks via {@code getChunkNow} (the grounded
	 * manager pattern): a null chunk means that column is unloaded and is skipped,
	 * NEVER force-loaded, so an unloaded jukebox neighbourhood simply reports no
	 * trigger. The radius crosses chunk boundaries, so the chunk is resolved PER
	 * {@code (dx,dz)} column and reused down that column's y span. The y span is
	 * clamped to the world's build range so we never read out of bounds.
	 * Allocation-light - one reused {@link BlockPos.MutableBlockPos}, early-returns
	 * on the first match.</p>
	 */
	private static boolean hasTriggerBlock(MinecraftServer server, GlobalPos jukebox) {
		ServerLevel level = server.getLevel(jukebox.dimension());
		if (level == null) {
			return false;
		}
		int radius = RadioConfig.get().nowPlayingHudTriggerRadius;
		BlockPos center = jukebox.pos();
		int cx = center.getX();
		int cy = center.getY();
		int cz = center.getZ();
		int minY = level.getMinY();
		int maxY = level.getMaxY();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				int worldX = cx + dx;
				int worldZ = cz + dz;
				// getChunkNow returns null for an unloaded chunk - skip that column,
				// never file a chunk ticket (mirrors RadioManager.chunkNow). Radius > 1
				// crosses chunk boundaries, so resolve the chunk per column.
				LevelChunk chunk = level.getChunkSource().getChunkNow(
						SectionPos.blockToSectionCoord(worldX),
						SectionPos.blockToSectionCoord(worldZ));
				if (chunk == null) {
					continue;
				}
				for (int dy = -radius; dy <= radius; dy++) {
					int worldY = cy + dy;
					// Never read outside the world's build range.
					if (worldY < minY || worldY > maxY) {
						continue;
					}
					cursor.set(worldX, worldY, worldZ);
					BlockState st = chunk.getBlockState(cursor);
					if (st.is(BlockTags.LANTERNS) || TRIGGER_BLOCKS.contains(st.getBlock())) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private Bar createBar(RadioSession session) {
		ServerBossEvent event = new ServerBossEvent(UUID.randomUUID(),
				buildName(stationDisplayName(session), session.nowPlaying(), false, false, templateFor(session),
						FeedRegistry.get().feedIsLive(session.identity())),
				BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
		// Static full bar - the now-playing record carries no elapsed/duration, so
		// there is nothing meaningful to fill partially.
		event.setProgress(1.0f);
		SavePointRadioMod.LOGGER.info("[jukebox] now-playing hud show {} station={}",
				session.describePos(), session.stationKey());
		return new Bar(event);
	}

	private void refreshName(Bar bar, RadioSession session) {
		String station = stationDisplayName(session);
		RadioSession.NowPlaying np = session.nowPlaying();
		// A real feed is registered (isPlaying gated on feedIsStreaming); "on air"
		// means it is actually receiving frames, "idle" a bound-but-silent listener.
		boolean receiving = FeedRegistry.get().feedFramesRead(session.identity()) > 0;
		// sow-005 Phase A: the feed's DECLARED playout phase - "buffering" while the
		// delivery loop pre-rolls / re-anchor rebuffers / reconnects. Renders a distinct
		// "buffering..." title that falls back to the LIVE/on-air/idle text once
		// streaming. A volatile-backed read; null when (racily) no feed is registered.
		boolean buffering = "buffering".equals(FeedRegistry.get().feedPlayoutState(session.identity()));

		// Only rebuild + resend the name Component when a described input changed.
		// NUL-joined so a title/artist/segment containing the separator can never
		// forge a false match. Mirrors MonitorService's lastNameKey discipline. The
		// playout token (b=buffering / r=receiving / i=idle) rides the SAME descriptor,
		// so a buffering<->streaming transition re-fires setName through this one guard.
		boolean live = np != null && np.live();
		// The per-disc marquee template rides the descriptor too, so editing a disc's
		// template re-fires setName on the next sweep even when the track is unchanged.
		String template = templateFor(session);
		// sow-006: source-level liveness (live HLS station / RTMP publisher / live platform) vs finite
		// (FILE / non-live VOD). The HUD shows "on air"/track only for a truly-live source; a non-live
		// playing disc shows just its name (no misleading "on air").
		boolean sourceLive = FeedRegistry.get().feedIsLive(session.identity());
		String descriptor = station + '\0' + (live ? "1" : "0") + '\0'
				+ nul(np == null ? null : np.source()) + '\0'
				+ nul(np == null ? null : np.title()) + '\0'
				+ nul(np == null ? null : np.artist()) + '\0'
				+ nul(np == null ? null : np.segment()) + '\0'
				+ (buffering ? "b" : receiving ? "r" : "i") + '\0'
				+ (sourceLive ? "L" : "n") + '\0'
				+ nul(template);
		if (descriptor.equals(bar.lastNameKey)) {
			return;
		}
		bar.lastNameKey = descriptor;
		// sow-006: styled + static when it fits; otherwise scroll a plain-text window.
		// getString() gives the exact plain text of the styled line - no logic duplicated.
		Component styled = buildName(station, np, receiving, buffering, template, sourceLive);
		String full = styled.getString();
		// Measure in CODE POINTS, not UTF-16 units: a styled name (sow-010) uses astral glyphs
		// (2 units each), so a char-length test would over-count and scroll a line that visually fits.
		if (full.codePointCount(0, full.length()) <= MARQUEE_WIDTH) {
			bar.marqueeText = null;
			bar.event.setName(styled);
		} else {
			// Content changed and overflows: (re)start the scroll from the beginning.
			bar.marqueeText = full;
			bar.scrollOffset = 0;
			bar.event.setName(Component.literal(marqueeWindow(full, 0)));
		}
	}

	/**
	 * Advances every scrolling bar one char and resends its windowed name. Server
	 * thread only (marquee tick). Bars whose title fits have {@code marqueeText == null}
	 * and are skipped, so a short name never scrolls nor resends a name packet.
	 */
	private void advanceMarquee() {
		if (bars.isEmpty()) {
			return;
		}
		for (Bar bar : bars.values()) {
			if (bar.marqueeText == null) {
				continue;
			}
			bar.scrollOffset++;
			bar.event.setName(Component.literal(marqueeWindow(bar.marqueeText, bar.scrollOffset)));
		}
	}

	/**
	 * A {@value #MARQUEE_WIDTH}-<b>glyph</b> window into {@code full + gap}, wrapping so the text
	 * loops seamlessly. {@code offset} is reduced modulo the looped glyph count. Windows over CODE
	 * POINTS, not UTF-16 units, so a styled name's astral glyphs (surrogate pairs from
	 * {@link fm.savepoint.radio.disc.NameStyler}'s math-alphanumeric styles) are never sliced
	 * mid-pair into a lone surrogate (which the client renders as tofu) at the scroll edges.
	 */
	private static String marqueeWindow(String full, int offset) {
		int[] cps = (full + MARQUEE_GAP).codePoints().toArray();
		int n = cps.length;
		int start = ((offset % n) + n) % n;
		StringBuilder sb = new StringBuilder(MARQUEE_WIDTH * 2);
		for (int i = 0; i < MARQUEE_WIDTH; i++) {
			sb.appendCodePoint(cps[(start + i) % n]);
		}
		return sb.toString();
	}

	private void teardownBar(Bar bar) {
		try {
			bar.event.removeAllPlayers();
			bar.event.setVisible(false);
		} catch (Throwable t) {
			SavePointRadioMod.LOGGER.warn("[jukebox] now-playing hud teardown failed", t);
		}
	}

	private void teardownAll(String reason) {
		for (Bar bar : bars.values()) {
			teardownBar(bar);
		}
		bars.clear();
		SavePointRadioMod.LOGGER.info("[jukebox] now-playing hud cleared ({})", reason);
	}

	/**
	 * The station's display name: an admin disc's {@link DiscDefinition#name}, a
	 * config station's {@code displayName}, or the bare key as a fallback. Read
	 * from the live registries so an edited name shows on the next sweep.
	 */
	private static String stationDisplayName(RadioSession session) {
		String identity = session.identity();
		String key = session.stationKey();
		if (identity.startsWith("disc:")) {
			DiscDefinition def = DiscRegistry.get().get(key).orElse(null);
			if (def != null && def.name != null && !def.name.isBlank()) {
				return def.displayName(); // sow-010: styled name on the boss bar
			}
		} else if (identity.startsWith("station:")) {
			RadioConfig.Station station = RadioConfig.get().stations.get(key);
			if (station != null && station.displayName != null && !station.displayName.isBlank()) {
				return station.displayName;
			}
		}
		return key;
	}

	/**
	 * The boss-bar title. Untrusted now-playing {@code title}/{@code artist}/
	 * {@code segment} are appended with literal concatenation (never a {@code %s}
	 * format), matching {@code MonitorService} and the dialog code, so a value
	 * containing {@code %} can never break formatting. Secrets (token/streamKey)
	 * are never part of any input here.
	 */
	private static Component buildName(String station, RadioSession.NowPlaying np, boolean receiving,
			boolean buffering, String template, boolean sourceLive) {
		MutableComponent name = Component.literal("♪ ").append(Component.literal(station));
		// sow-005 Phase A: a buffering feed declares the pause explicitly and takes
		// precedence over the LIVE/track text - there is no audio to describe mid
		// pre-roll/re-anchor. Falls back to the normal text once streaming resumes.
		if (buffering) {
			return name.append(Component.literal(" — buffering…"));
		}
		// sow-006 review-feedback: the "on air"/LIVE/idle status is shown only for a truly-live
		// source (a live HLS station, an RTMP publisher, or a live platform stream). A non-live
		// source - a FILE disc or a non-live platform VOD - shows just its styled name, with no
		// misleading "on air"/idle suffix. (A non-live VOD also reports np.live()=false, so the
		// track/LIVE block below would not fire anyway; this makes the intent explicit and also
		// suppresses the bound-but-silent "idle" line for a finite disc between tracks.)
		if (!sourceLive) {
			return name;
		}
		if (np != null && np.live()) {
			String title = np.title();
			if (title != null && !title.isBlank()) {
				// sow-006 review-feedback: only the live-track line is templatable. A set,
				// non-empty-rendering template replaces the built-in layout; anything else
				// (blank/absent template, or one that collapses to empty) falls through to it.
				if (template != null) {
					String rendered = applyTemplate(template, station, title, np.artist(), np.segment());
					if (rendered != null) {
						return Component.literal(rendered);
					}
				}
				MutableComponent line = name.append(Component.literal(" — LIVE — ")).append(Component.literal(title));
				String artist = np.artist();
				if (artist != null && !artist.isBlank()) {
					line.append(Component.literal(" · ")).append(Component.literal(artist));
				}
				return line;
			}
			// Live DJ takeover (relay / master-key) with no track metadata.
			String source = np.source();
			if ("relay".equals(source) || "master-key".equals(source)) {
				String segment = np.segment();
				if (segment != null && !segment.isBlank()) {
					return name.append(Component.literal(" — LIVE ("))
							.append(Component.literal(segment))
							.append(Component.literal(" · live DJ)"));
				}
				return name.append(Component.literal(" — LIVE (live DJ)"));
			}
		}
		// Feed-state fallback: receiving audio vs a bound-but-silent feed.
		return name.append(Component.literal(receiving ? " — ● on air" : " — ○ idle"));
	}

	/** Placeholders substituted in a per-disc marquee template; unknown {tokens} stay literal. */
	private static final Pattern TEMPLATE_PLACEHOLDER = Pattern.compile("\\{(station|title|artist|segment)\\}");
	/** Separator run (whitespace + common dividers) collapsed away next to an empty placeholder. */
	private static final String TEMPLATE_SEP = "[\\s·—–\\-•|:/]+";
	/**
	 * Hard cap on the substituted string the collapse regexes run over. Callers already pass
	 * short/sanitized inputs (station name ≤48; now-playing fields are control-stripped and capped
	 * in {@code NowPlayingPoller}), but the collapse is a backtracking regex, so bound its input
	 * here too - defence-in-depth against a separator-only value making the pass super-linear on
	 * the server thread. A boss bar only ever scrolls a short window, so nothing legible is lost.
	 */
	private static final int TEMPLATE_RENDER_MAX = 1024;

	/**
	 * The disc's per-disc marquee template ({@link DiscDefinition#nowPlayingTemplate}),
	 * or null when unset or this session is not an admin disc. Read live from the
	 * registry so an edited template takes effect on the next sweep. Server thread.
	 */
	private static String templateFor(RadioSession session) {
		if (!session.identity().startsWith("disc:")) {
			return null;
		}
		DiscDefinition def = DiscRegistry.get().get(session.stationKey()).orElse(null);
		if (def == null) {
			return null;
		}
		String template = def.nowPlayingTemplate;
		return template == null || template.isBlank() ? null : template;
	}

	/**
	 * Renders a per-disc marquee template: substitutes {@code {station}}/{@code {title}}/
	 * {@code {artist}}/{@code {segment}} in a single pass (so a value that itself contains
	 * {@code {...}} is never re-substituted), marking a null/blank field, then collapses a
	 * separator adjacent to any empty field so a missing artist never leaves a dangling
	 * " · ". Unknown {@code {tokens}} are left literal. Returns the collapsed one-line
	 * result, or null when it renders empty (the caller then uses the built-in layout).
	 * Pure literal substitution - never a {@code %s} format - so an untrusted title/artist
	 * containing {@code %} is safe.
	 */
	private static String applyTemplate(String template, String station, String title, String artist, String segment) {
		Matcher m = TEMPLATE_PLACEHOLDER.matcher(template);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			String value = switch (m.group(1)) {
				case "station" -> station;
				case "title" -> title;
				case "artist" -> artist;
				case "segment" -> segment;
				default -> "";
			};
			// '\0' marks an empty field so the collapse pass can eat its separator.
			m.appendReplacement(sb, Matcher.quoteReplacement(value == null || value.isBlank() ? "\0" : value));
		}
		m.appendTail(sb);
		if (sb.length() > TEMPLATE_RENDER_MAX) {
			sb.setLength(TEMPLATE_RENDER_MAX);
		}
		String rendered = sb.toString()
				.replaceAll("\0" + TEMPLATE_SEP, "")   // empty field followed by a separator
				.replaceAll(TEMPLATE_SEP + "\0", "")   // a separator followed by empty field
				.replace("\0", "")                       // any bare empty field
				.replaceAll("\\s{2,}", " ")
				.trim();
		return rendered.isEmpty() ? null : rendered;
	}

	private static String nul(String value) {
		return value == null ? "" : value;
	}

	/** One now-playing bar. Written only on the server thread. */
	private static final class Bar {
		final ServerBossEvent event;
		/** Last name descriptor, so the name packet is only resent on a real change. */
		String lastNameKey = "";
		/** Non-null when the name overflows and is scrolling: the full plain text. */
		String marqueeText;
		/** Marquee scroll position in chars, advanced on the marquee tick. */
		int scrollOffset;

		Bar(ServerBossEvent event) {
			this.event = event;
		}
	}

	private NowPlayingHud() {
	}
}
