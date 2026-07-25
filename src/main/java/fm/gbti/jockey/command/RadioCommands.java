package fm.gbti.jockey.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import fm.gbti.jockey.RadioAudit;
import fm.gbti.jockey.Secrets;
import fm.gbti.jockey.config.RadioConfig;
import fm.gbti.jockey.dialog.DialogScreens;
import fm.gbti.jockey.dialog.JockeyRadioDialogs;
import fm.gbti.jockey.disc.DiscDefinition;
import fm.gbti.jockey.disc.DiscRegistry;
import fm.gbti.jockey.disc.RadioDiscFactory;
import fm.gbti.jockey.session.FeedRegistry;
import fm.gbti.jockey.session.RadioManager;
import fm.gbti.jockey.session.RadioSession;
import fm.gbti.jockey.session.StationEndpoints;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * Registers {@code /jocky give [station] [player]} and the dev inspection
 * subcommand {@code /jocky sessions} (both permission level 2).
 *
 * <p>The station defaults to the configured default station and the target
 * player defaults to the executing player; the console must name a player.</p>
 */
public final class RadioCommands {

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			// Single consolidated /jocky command tree: bare /jocky opens the admin dialog UI, and
			// every admin subcommand (give / station / list / reload / sessions / diag / heal) hangs
			// off it for console/headless use. Gated at op level adminPermissionLevel (default 3 =
			// LEVEL_ADMINS); the dialog handler re-checks the same gate on every submit.
			dispatcher.register(
					Commands.literal("jocky")
							.requires(Commands.hasPermission(adminPermissionCheck()))
							.executes(RadioCommands::openMenu)
							.then(Commands.literal("give")
									.then(Commands.argument("discId", StringArgumentType.word())
											.suggests((ctx, builder) -> {
												String remaining = builder.getRemainingLowerCase();
												for (DiscDefinition def : DiscRegistry.get().list()) {
													if (def.discId.startsWith(remaining)) {
														builder.suggest(def.discId);
													}
												}
												return builder.buildFuture();
											})
											.executes(ctx ->
													giveDisc(ctx, StringArgumentType.getString(ctx, "discId"), null))
											.then(Commands.argument("player", EntityArgument.player())
													.executes(ctx -> giveDisc(ctx,
															StringArgumentType.getString(ctx, "discId"),
															EntityArgument.getPlayer(ctx, "player"))))))
							.then(Commands.literal("station")
									.executes(ctx -> give(ctx, null, null))
									.then(Commands.argument("station", StringArgumentType.word())
											.suggests((ctx, builder) -> {
												String remaining = builder.getRemainingLowerCase();
												for (String key : RadioConfig.get().stations.keySet()) {
													if (key.toLowerCase().startsWith(remaining)) {
														builder.suggest(key);
													}
												}
												return builder.buildFuture();
											})
											.executes(ctx ->
													give(ctx, StringArgumentType.getString(ctx, "station"), null))
											.then(Commands.argument("player", EntityArgument.player())
													.executes(ctx -> give(ctx,
															StringArgumentType.getString(ctx, "station"),
															EntityArgument.getPlayer(ctx, "player"))))))
							.then(Commands.literal("list")
									.executes(RadioCommands::listDiscs))
							.then(Commands.literal("reload")
									.executes(RadioCommands::reloadDiscs))
							.then(Commands.literal("sessions")
									.executes(RadioCommands::sessions))
							.then(Commands.literal("diag")
									.executes(RadioCommands::diag))
							.then(Commands.literal("heal")
									.executes(RadioCommands::heal)));
		});
	}

	/**
	 * The permission gate for {@code /jocky}, read from config at
	 * registration ({@code adminPermissionLevel}, default 3). The
	 * per-submit dialog handler in W2 will re-check the same gate server-side.
	 */
	private static PermissionCheck adminPermissionCheck() {
		int level = RadioConfig.get().adminPermissionLevel;
		return new PermissionCheck.Require(new Permission.HasCommandLevel(PermissionLevel.byId(level)));
	}

	/** Dev helper: prints the tracked radio sessions and the live feed stats. */
	private static int sessions(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		RadioManager manager = RadioManager.get();
		int count = manager.getSessionCount();
		source.sendSuccess(() -> Component.literal("Radio sessions: " + count), false);
		for (RadioSession session : manager.getSessions()) {
			BlockPos pos = session.pos().pos();
			String line = "  " + session.stationKey()
					+ " @ " + pos.getX() + "," + pos.getY() + "," + pos.getZ()
					+ " in " + session.pos().dimension().identifier()
					+ " (" + session.state() + ")";
			source.sendSuccess(() -> Component.literal(line), false);
		}
		List<String> feedLines = FeedRegistry.get().feedStatsLines();
		source.sendSuccess(() -> Component.literal("Radio feeds: " + feedLines.size()), false);
		for (String feedLine : feedLines) {
			String line = "  " + feedLine;
			source.sendSuccess(() -> Component.literal(line), false);
		}
		return count;
	}

	/**
	 * Re-stamps placed radio discs from the current registry, in place, healing items whose NBT was
	 * damaged by an upgrade (e.g. the rename that stripped the custom jukebox-song override, leaving a
	 * disc to play its base vanilla track over the radio) and refreshing stale name/lore/custom_data.
	 * Only jukeboxes in loaded chunks are reachable, so run it near the discs. See
	 * {@link RadioManager#healPlacedDiscs()}.
	 */
	private static int heal(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		RadioManager.HealResult result = RadioManager.get().healPlacedDiscs();
		source.sendSuccess(() -> Component.literal(
				"Healed " + result.healed() + " disc(s) of " + result.discSessions()
						+ " active registry disc(s)"
						+ (result.unreachable() > 0
								? " (" + result.unreachable() + " in unloaded chunks - move nearer and re-run)"
								: "")
						+ "."), true);
		return result.healed();
	}

	/**
	 * Dev helper (sow-005 Phase 0): prints a per-live-feed diagnostics snapshot -
	 * state, framesRead/delivered/silence, drops (+ dropsLast60s frame count),
	 * glitchEvents (+ glitchEventsLast60s, the micro-glitch proof metric - one per
	 * catch-up/resync/underrun occurrence), underruns, buffered/latencyMs/targetMs,
	 * reconnects, effective delivered PCM bitrate, buffer-occupancy min/max/avg, and
	 * the redacted ffmpeg arg-shape. Reads counters and three small rings, so it is
	 * cheap and safe on any thread.
	 */
	private static int diag(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		List<String> diagLines = FeedRegistry.get().feedDiagLines();
		source.sendSuccess(() -> Component.literal("Radio feed diagnostics: " + diagLines.size()), false);
		for (String diagLine : diagLines) {
			String line = "  " + diagLine;
			source.sendSuccess(() -> Component.literal(line), false);
		}

		// Now-playing connectivity, one line per active disc session: the last poll
		// outcome (off|ok|unauthorized|disabled|unreachable|no-endpoint) and the
		// resolved endpoint HOST (never the token). Status text only, never an error.
		List<RadioSession> sessions = RadioManager.get().getSessions().stream()
				.filter(s -> s.state() == RadioSession.State.ACTIVE && !s.isInert())
				.toList();
		source.sendSuccess(() -> Component.literal("Radio now-playing: " + sessions.size()), false);
		for (RadioSession session : sessions) {
			DiscDefinition def = DiscRegistry.get().get(session.stationKey()).orElse(null);
			String endpoint = def != null ? def.resolveNowPlayingUrl() : null;
			String host = nowPlayingEndpointHost(endpoint);
			String line = "  " + session.stationKey()
					+ " nowPlaying=" + session.nowPlayingStatus().token()
					+ " endpoint=" + host;
			source.sendSuccess(() -> Component.literal(line), false);
		}
		return diagLines.size();
	}

	/**
	 * The host[:port] of a resolved now-playing endpoint for the diag line, or
	 * {@code "-"} when there is none. Never returns a token: only the authority host
	 * and port are shown (any query/userinfo is dropped by {@link java.net.URI}).
	 */
	private static String nowPlayingEndpointHost(String endpoint) {
		if (endpoint == null) {
			return "-";
		}
		try {
			java.net.URI uri = new java.net.URI(endpoint);
			String host = uri.getHost();
			if (host == null) {
				return "-";
			}
			int port = uri.getPort();
			return port > 0 ? host + ":" + port : host;
		} catch (java.net.URISyntaxException e) {
			return "-";
		}
	}

	private static int give(CommandContext<CommandSourceStack> ctx, String stationKey, ServerPlayer target) {
		CommandSourceStack source = ctx.getSource();
		RadioConfig config = RadioConfig.get();

		String key = stationKey != null ? stationKey : config.defaultStation;
		// A null station value (config load drops these, but stay defensive)
		// takes the same error path as an unknown key.
		RadioConfig.Station station = key != null ? config.stations.get(key) : null;
		if (station == null) {
			String known = config.stations.isEmpty()
					? "(none configured)"
					: String.join(", ", config.stations.keySet());
			source.sendFailure(Component.literal(
					"Unknown station '" + key + "'. Known stations: " + known));
			return 0;
		}

		ServerPlayer player = target != null ? target : source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal(
					"The console must specify a player: /jocky give <station> <player>"));
			return 0;
		}

		Optional<ItemStack> disc = RadioDiscFactory.createDisc(source.registryAccess(), key, station);
		if (disc.isEmpty()) {
			source.sendFailure(Component.literal(
					"Could not create the radio disc: jukebox song 'jockey_radio:radio' is not loaded."));
			return 0;
		}

		ItemStack stack = disc.get();
		if (!player.addItem(stack)) {
			source.sendFailure(Component.literal(
					player.getName().getString() + "'s inventory is full."));
			return 0;
		}

		String display = station.displayName != null ? station.displayName : key;
		RadioAudit.logGive(key, RadioAudit.actor(source), RadioAudit.actor(player));
		source.sendSuccess(() -> Component.literal(
				"Gave a " + display + " radio disc to " + player.getName().getString()), true);
		return 1;
	}

	// ------------------------------------------------------------------
	// /jocky (admin disc registry)
	// ------------------------------------------------------------------

	/**
	 * Opens the {@code /jocky} root dialog for the executing player. Requires
	 * a player context (the dialog is pushed to a client connection); the console
	 * uses the give/list/reload subcommands instead.
	 */
	private static int openMenu(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) {
			ctx.getSource().sendFailure(Component.literal(
					"Only a player can open the Jockey Radio menu. Use /jocky give|list|reload from the console."));
			return 0;
		}
		DialogScreens.send(player, JockeyRadioDialogs.rootMenu(player.registryAccess()));
		return 1;
	}

	private static int giveDisc(CommandContext<CommandSourceStack> ctx, String discId, ServerPlayer target) {
		CommandSourceStack source = ctx.getSource();
		DiscDefinition def = DiscRegistry.get().get(discId).orElse(null);
		if (def == null) {
			List<DiscDefinition> known = DiscRegistry.get().list();
			String list = known.isEmpty()
					? "(none created)"
					: known.stream().map(d -> d.discId).reduce((a, b) -> a + ", " + b).orElse("");
			source.sendFailure(Component.literal("Unknown disc '" + discId + "'. Known discs: " + list));
			return 0;
		}

		ServerPlayer player = target != null ? target : source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal(
					"The console must specify a player: /jocky give <discId> <player>"));
			return 0;
		}

		Optional<ItemStack> disc = RadioDiscFactory.createFromDefinition(source.registryAccess(), def);
		if (disc.isEmpty()) {
			source.sendFailure(Component.literal(
					"Could not create the radio disc: jukebox song 'jockey_radio:radio' is not loaded."));
			return 0;
		}

		if (!player.addItem(disc.get())) {
			source.sendFailure(Component.literal(player.getName().getString() + "'s inventory is full."));
			return 0;
		}

		String display = def.name != null ? def.displayName() : def.discId;
		RadioAudit.logGive(def.discId, RadioAudit.actor(source), RadioAudit.actor(player));
		source.sendSuccess(() -> Component.literal(
				"Gave the '" + display + "' radio disc to " + player.getName().getString()), true);
		return 1;
	}

	/** Lists every registry disc with its source type; tokens/stream keys are masked. */
	private static int listDiscs(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		List<DiscDefinition> discs = DiscRegistry.get().list();
		source.sendSuccess(() -> Component.literal("Radio discs: " + discs.size()), false);
		for (DiscDefinition def : discs) {
			String detail = switch (def.sourceType) {
				// url=... shows the resolved display URL (any inline token stripped),
				// keeping the secret-masking discipline; legacy discs still show host/station.
				case HLS_PULL -> "hls_pull "
						+ (def.streamUrl != null
								? "url=" + StationEndpoints.forDiscResolved(def, RadioConfig.get()).displayUrl()
								: "host=" + def.host + " station=" + def.stationId)
						+ " token=" + Secrets.mask(def.token)
						+ " nowPlayingToken=" + Secrets.mask(def.nowPlayingToken);
				case RTMP_RECEIVE -> "rtmp_receive bind=" + def.rtmpBind + " port=" + def.rtmpPort
						+ " streamKey=" + Secrets.mask(def.streamKey);
				case FILE -> "file url="
						+ StationEndpoints.forDiscResolved(def, RadioConfig.get()).displayUrl();
				case PLATFORM -> "platform link="
						+ StationEndpoints.forDiscResolved(def, RadioConfig.get()).displayUrl();
			};
			String line = "  " + def.discId + " \"" + def.name + "\" [" + detail
					+ " radius=" + def.radiusBlocks + "]";
			source.sendSuccess(() -> Component.literal(line), false);
		}
		return discs.size();
	}

	private static int reloadDiscs(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		DiscRegistry.get().reload();
		int count = DiscRegistry.get().size();
		String tail = DiscRegistry.get().isReadOnly()
				? " disc(s) - but " + DiscRegistry.FILE_NAME + " could not be fully read, so SAVING IS DISABLED. "
						+ "Fix or remove that file, then reload again."
				: " disc(s).";
		source.sendSuccess(() -> Component.literal("Reloaded disc registry: " + count + tail), true);
		return count;
	}

	private RadioCommands() {
	}
}
