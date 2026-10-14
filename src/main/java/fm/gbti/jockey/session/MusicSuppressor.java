package fm.gbti.jockey.session;

import fm.gbti.jockey.JockeyRadioMod;
import fm.gbti.jockey.config.RadioConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-side game-music suppression: while a player stands within the audible
 * radius of a disc that is delivering audio, hard-mutes that player's own vanilla
 * background music so Minecraft's music track never fights the streamed radio. A
 * radio that is silent (an off-air station, an RTMP listener with no publisher, a
 * source that keeps failing) leaves the game music alone.
 *
 * <p>Purely server→client, so it needs no client mod and works for every vanilla
 * player: a throttled server-thread sweep (~1s) sends each in-range player a
 * {@link ClientboundStopSoundPacket}{@code (null, }{@link SoundSource#MUSIC}{@code )},
 * which stops all currently-playing {@code MUSIC}-category sounds on that client.
 * The client's {@code MusicManager} will start the next track after its usual
 * delay, so the packet is re-sent every sweep to keep the music suppressed while
 * the player stays in range - a small packet, so re-sending is cheap.</p>
 *
 * <p><b>Hard on/off, not a fade.</b> By the owner's no-client-mod choice this is
 * a blunt stop rather than a smooth duck: near-radio game music is simply off.
 * There is no per-player state and nothing to tear down - once the player leaves
 * the radius (or the session stops, or the config toggle goes off) the sweep just
 * stops sending, and the client's music resumes naturally on its next
 * {@code MusicManager} cycle. Nothing to clean up on player disconnect or server
 * stop for the same reason.</p>
 *
 * <p><b>Threading:</b> every method here runs on the server thread.
 * {@link #onEndServerTick} is driven from {@code END_SERVER_TICK}, a server-thread
 * event. It never blocks: the sweep is a cheap membership scan plus one small
 * packet per in-range player, and the whole thing is guarded so a cosmetic
 * feature can never crash the tick. Coexists with {@link NowPlayingHud} and
 * {@code MonitorService} (independent server-tick sweeps).</p>
 */
public final class MusicSuppressor {

	private static final MusicSuppressor INSTANCE = new MusicSuppressor();

	/**
	 * Ticks between suppression sweeps (~1s at 20 TPS). Matches {@link NowPlayingHud}:
	 * a player entering/leaving a radius sees their music mute/resume within one
	 * interval, and re-sending the stop once per second keeps a restarted vanilla
	 * track from being audible for more than about a second.
	 */
	private static final int SCAN_INTERVAL_TICKS = 20;

	/** Server-thread-only tick counter throttling the sweep. */
	private int ticksSinceScan;

	public static MusicSuppressor get() {
		return INSTANCE;
	}

	/**
	 * Throttled per-tick sweep: mute game music for every online player standing
	 * within a playing disc's radius. When suppression is disabled in config nothing
	 * runs (and nothing needs undoing - the client resumes music on its own once the
	 * stop packets stop arriving). Server thread (END_SERVER_TICK); guarded so a
	 * cosmetic sweep never crashes the tick.
	 */
	public void onEndServerTick(MinecraftServer server) {
		if (!RadioConfig.get().suppressGameMusic) {
			ticksSinceScan = 0;
			return;
		}
		if (++ticksSinceScan < SCAN_INTERVAL_TICKS) {
			return;
		}
		ticksSinceScan = 0;
		try {
			sweep(server);
		} catch (Throwable t) {
			JockeyRadioMod.LOGGER.warn("[jockey] music suppressor sweep failed", t);
		}
	}

	// ------------------------------------------------------------------
	// Internals (server thread only)
	// ------------------------------------------------------------------

	private void sweep(MinecraftServer server) {
		// The sessions delivering audio this sweep (ACTIVE, non-inert, feed streaming).
		List<RadioSession> playing = new ArrayList<>();
		for (RadioSession session : RadioManager.get().getSessions()) {
			if (isPlaying(session)) {
				playing.add(session);
			}
		}
		if (playing.isEmpty()) {
			return;
		}
		// Any online player inside a playing disc's radius gets a fresh MUSIC-category
		// stop so their vanilla music stays muted while they linger near the radio.
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!inRangeOfAnyPlaying(player, playing)) {
				continue;
			}
			try {
				player.connection.send(new ClientboundStopSoundPacket(null, SoundSource.MUSIC));
			} catch (Throwable ignored) {
				// A closing connection may reject the packet; the next sweep retries,
				// and a truly gone player simply drops off the player list.
			}
		}
	}

	/**
	 * Whether {@code player} stands within at least one playing session's audible
	 * radius (same dimension, Euclidean distance to the jukebox block centre within
	 * the session's {@code radiusBlocks}). Mirrors {@link NowPlayingHud}'s distance
	 * test, but only needs a boolean - the stop packet is not per-session.
	 */
	private static boolean inRangeOfAnyPlaying(ServerPlayer player, List<RadioSession> playing) {
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
			if (distSq <= (double) radius * radius) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A session delivering audio now: ACTIVE, not inert, and its feed past pre-roll and
	 * emitting frames. A feed merely existing is not enough: an off-air station or an RTMP
	 * listener with no publisher is a real feed that plays silence. Brief rebuffers are not
	 * covered, which is harmless: the client's music manager waits minutes before it starts
	 * the next track.
	 */
	private static boolean isPlaying(RadioSession session) {
		if (session.state() != RadioSession.State.ACTIVE || session.isInert()) {
			return false;
		}
		// identity() is the namespaced feed-registry key.
		return "streaming".equals(FeedRegistry.get().feedPlayoutState(session.identity()));
	}

	private MusicSuppressor() {
	}
}
