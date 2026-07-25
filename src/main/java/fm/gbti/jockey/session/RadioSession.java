package fm.gbti.jockey.session;

import fm.gbti.jockey.JockeyRadioMod;
import fm.gbti.jockey.voicechat.VoiceChatBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * One radio disc sitting in one jukebox. Keyed by disc presence, not vanilla
 * play state: the session lives while the disc is in the jukebox, survives the
 * vanilla song timing out, suspends over chunk unloads and ends only when the
 * disc leaves (eject, replace, block break) or on manager shutdown.
 *
 * <p>The disc resolves to a {@link FeedSource} (a sow-001 config station or an
 * admin registry disc) when {@link RadioManager} creates the session. A disc
 * whose {@code disc_id} is unknown/deleted has a {@code null} source: the
 * session still exists (so eject/replace stay clean) but acquires no feed and
 * opens no sink - it is silent, or "inert".</p>
 *
 * <p>Only {@link RadioManager} mutates sessions, and only on the server
 * thread. {@code state} is volatile so off-thread readers of
 * {@link RadioManager#getSessions()} (panel HTTP handlers, tests) observe
 * fresh values; {@code feed}/{@code sink} stay server-thread-only and are
 * never exposed.</p>
 */
public final class RadioSession {

	public enum State {
		/** Feed lease held (or inert), sink open. */
		ACTIVE,
		/** Chunk unloaded (or server stopping): no feed lease, resumable. */
		SUSPENDED,
		/** Terminal; the session has been removed from the manager. */
		STOPPED
	}

	/**
	 * An immutable now-playing snapshot from {@code NowPlayingPoller}. Every field
	 * is nullable except {@code live}: {@code title}/{@code artist} are null when no
	 * track is playing, {@code segment} when there is no segment, and {@code source}
	 * ({@code "master"} / {@code "relay"} / {@code "master-key"}; the last two mean a
	 * live DJ takeover) when unknown. Held behind a single volatile reference so a
	 * reader always sees one internally consistent snapshot.
	 */
	public record NowPlaying(boolean live, String source, String title, String artist, String segment) {
	}

	/**
	 * The outcome of the last now-playing poll for this session - a diagnosis of
	 * whether (and why) real track info is or is not flowing. Status text only; the
	 * radio and the boss bar never depend on it. Written off-thread by
	 * {@code NowPlayingPoller}, read on any thread (volatile).
	 * <ul>
	 *   <li>{@link #OFF} - polling disabled ({@code nowPlayingPollSeconds <= 0}) or
	 *       this session not yet polled.</li>
	 *   <li>{@link #OK} - last poll returned 200 with a parsed snapshot.</li>
	 *   <li>{@link #UNAUTHORIZED} - 401 (a bearer token is required and missing/invalid).</li>
	 *   <li>{@link #DISABLED} - 404 (now-playing disabled for the station, or idle).</li>
	 *   <li>{@link #UNREACHABLE} - connect refused / timeout / IOException (host down).</li>
	 *   <li>{@link #NO_ENDPOINT} - the disc has no derivable now-playing URL.</li>
	 * </ul>
	 */
	public enum NowPlayingStatus {
		OFF("off"),
		OK("ok"),
		UNAUTHORIZED("unauthorized"),
		DISABLED("disabled"),
		UNREACHABLE("unreachable"),
		NO_ENDPOINT("no-endpoint");

		private final String token;

		NowPlayingStatus(String token) {
			this.token = token;
		}

		/** The lowercase diag token (e.g. {@code no-endpoint}). */
		public String token() {
			return token;
		}
	}

	/** Namespaced equality key ("disc:x" / "station:x" / "inert:x"); server-internal. */
	private final String identity;
	/** {@code station=} log label: the bare discId or station key. */
	private final String label;
	/** Null for an inert (unknown/deleted disc) session - no feed, no audio. */
	private final FeedSource source;
	private final GlobalPos pos;
	/** Ingest fingerprint of the disc definition this session was built from ("" if none). */
	private final String fingerprint;
	private volatile State state = State.SUSPENDED;
	/**
	 * sow-012: latched once a repeat-off FINITE source (FILE / non-live VOD) has played to its end.
	 * A finished session is silent (feed + sink released) and NEVER re-acquires a feed on resume, so a
	 * finite disc plays exactly once and does not replay on a chunk unload/reload. Lives on the session
	 * object, so it survives suspend/resume; cleared only by tearing the session down (disc
	 * ejected/replaced) - re-inserting the disc makes a fresh session that plays again. Read on the
	 * server thread and (volatile) by the jukebox mixin's server-thread tick.
	 */
	private volatile boolean finished;
	private StationFeed feed;
	private AudioSink sink;

	/**
	 * Latest now-playing snapshot, written off-thread by {@code NowPlayingPoller}
	 * (opt-in; disabled by default) and read on the server thread when a dialog is
	 * built - a single volatile reference to an immutable record so a reader always
	 * sees one consistent snapshot (never an old title mixed with a new artist).
	 * {@code null} means the poller has never reported on this session (disabled,
	 * an RTMP/config-station disc, or not yet polled); dialogs then fall back to the
	 * shared feed state. Nothing in the audio path depends on it.
	 */
	private volatile NowPlaying nowPlaying;

	/**
	 * The last now-playing poll outcome for this session (status text only, never an
	 * error the radio depends on). {@code OFF} until the poller reports. Volatile so
	 * an off-thread reader ({@code /jocky diag}) sees a fresh value.
	 */
	private volatile NowPlayingStatus nowPlayingStatus = NowPlayingStatus.OFF;

	/**
	 * Consecutive UNREACHABLE polls. Touched ONLY by the single {@code NowPlayingPoller}
	 * daemon thread (reset on any HTTP response, incremented on a network failure), so
	 * it needs no synchronization; used to clear a stale snapshot after repeated
	 * failures so a "LIVE - old track" can never persist while the API is down.
	 */
	private int nowPlayingUnreachableStreak;

	/**
	 * Consecutive reachable-but-erroring polls (5xx / 403 / any non-2xx/404/401). Like
	 * {@link #nowPlayingUnreachableStreak} it is touched ONLY on the {@code NowPlayingPoller}
	 * thread that processes responses (requests fire async, but their outcomes are handled
	 * sequentially on that one thread), so it needs no synchronization; used to clear a stale
	 * snapshot after a persistent run of errors so a "LIVE - old track" cannot pin forever on
	 * an endpoint that stays reachable but keeps erroring (UNREACHABLE's reachable twin).
	 */
	private int nowPlayingStaleStreak;

	RadioSession(String identity, String label, FeedSource source, GlobalPos pos, String fingerprint) {
		this.identity = identity;
		this.label = label;
		this.source = source;
		this.pos = pos;
		this.fingerprint = fingerprint == null ? "" : fingerprint;
	}

	/**
	 * The disc definition's ingest fingerprint at the moment this session started. Compared
	 * against the current definition after a registry edit to decide whether this session
	 * must be rebuilt against a fresh feed. Empty for a config station or an inert disc.
	 */
	String fingerprint() {
		return fingerprint;
	}

	/** Server-internal namespaced identity used to tell one disc from another. */
	String identity() {
		return identity;
	}

	/** The {@code station=} log label (discId or config station key). */
	public String stationKey() {
		return label;
	}

	public GlobalPos pos() {
		return pos;
	}

	/**
	 * The audible radius in blocks the session's feed was resolved with - the same
	 * value the voicechat sink uses as its channel radius, so it stays correct even
	 * if the disc definition is later edited (the running sink keeps the old radius).
	 * {@code 0} for an inert session (no feed). Safe on any thread (final field).
	 */
	public int radiusBlocks() {
		return source != null ? source.radiusBlocks() : 0;
	}

	public State state() {
		return state;
	}

	/** True for an inert session (unknown/deleted disc): tracked but silent. */
	public boolean isInert() {
		return source == null;
	}

	/**
	 * The latest now-playing snapshot, or {@code null} when the poller has never
	 * reported on this session. Safe on any thread (one volatile read of an
	 * immutable record).
	 */
	public NowPlaying nowPlaying() {
		return nowPlaying;
	}

	/** Whether the now-playing poller last saw this station live. Safe on any thread. */
	public boolean isNowPlayingLive() {
		NowPlaying snapshot = nowPlaying;
		return snapshot != null && snapshot.live();
	}

	/** The last broadcast source the now-playing poller reported, or null. Safe on any thread. */
	public String broadcastSource() {
		NowPlaying snapshot = nowPlaying;
		return snapshot != null ? snapshot.source() : null;
	}

	/**
	 * Records the latest now-playing snapshot (or {@code null} to clear). Called
	 * only by {@code NowPlayingPoller} from its daemon thread; a single volatile
	 * write that never touches the feed/sink, so it is safe off the server thread.
	 */
	void updateNowPlaying(NowPlaying snapshot) {
		this.nowPlaying = snapshot;
	}

	/**
	 * The last now-playing poll outcome for this session. Safe on any thread (one
	 * volatile read). Never {@code null}; {@code OFF} before the first poll.
	 */
	public NowPlayingStatus nowPlayingStatus() {
		return nowPlayingStatus;
	}

	/** Records the last now-playing poll outcome. Called only by {@code NowPlayingPoller}. */
	void setNowPlayingStatus(NowPlayingStatus status) {
		this.nowPlayingStatus = status;
	}

	/** Consecutive UNREACHABLE polls. Poller-thread only. */
	int nowPlayingUnreachableStreak() {
		return nowPlayingUnreachableStreak;
	}

	/** Sets the consecutive-UNREACHABLE counter. Poller-thread only. */
	void setNowPlayingUnreachableStreak(int streak) {
		this.nowPlayingUnreachableStreak = streak;
	}

	/** Consecutive reachable-but-erroring (non-2xx/404/401) polls. Poller-thread only. */
	int nowPlayingStaleStreak() {
		return nowPlayingStaleStreak;
	}

	/** Sets the consecutive stale-response counter. Poller-thread only. */
	void setNowPlayingStaleStreak(int streak) {
		this.nowPlayingStaleStreak = streak;
	}

	/**
	 * Acquires the shared feed and opens the sink. No-op when already active or
	 * inert (an inert session holds no feed/sink; it just occupies the slot).
	 */
	void activate(MinecraftServer server) {
		if (state == State.ACTIVE) {
			return;
		}
		if (finished) {
			// sow-012: a repeat-off finite disc that already played to its end - resume to a parked,
			// silent ACTIVE state WITHOUT re-acquiring a feed, so a chunk unload/reload never replays it.
			state = State.ACTIVE;
			return;
		}
		if (source != null) {
			feed = FeedRegistry.get().acquire(source);
			sink = selectSink(server);
			sink.open();
			feed.addSink(sink);
		}
		state = State.ACTIVE;
	}

	/**
	 * True when this session is ACTIVE and still running on a placeholder
	 * ({@link NullSink}) sink that the voicechat-ready upgrade should replace.
	 * Server thread only (reads the server-thread-owned {@code sink}). Inert
	 * sessions have no sink and are never upgraded.
	 */
	boolean isOnPlaceholderSink() {
		return state == State.ACTIVE && sink != null && sink.isPlaceholder();
	}

	/**
	 * Swaps the sink of an ACTIVE session for a freshly selected one - the
	 * voicechat-became-ready upgrade path ({@link RadioManager#reopenActiveSinks}).
	 * Server thread only. The feed keeps running; the old sink is detached and
	 * closed first (its contract tolerates the one in-flight frame that may
	 * still land), then the new pick opens and attaches.
	 */
	void reopenSink(MinecraftServer server) {
		if (state != State.ACTIVE || feed == null) {
			return;
		}
		if (sink != null) {
			feed.removeSink(sink);
			sink.close();
		}
		sink = selectSink(server);
		sink.open();
		feed.addSink(sink);
	}

	/** Releases the feed lease and closes the sink, leaving the session resumable. */
	void suspend() {
		releaseResources();
		state = State.SUSPENDED;
	}

	/** Releases resources and marks the session terminal. */
	void stop() {
		releaseResources();
		state = State.STOPPED;
	}

	/** sow-012: whether this session's finite repeat-off source has played to its end. Server thread. */
	public boolean isFinished() {
		return finished;
	}

	/**
	 * sow-012: latch a repeat-off finite source as played-to-end. Releases the (already-drained,
	 * silent) feed + sink and sets {@link #finished}, so the session stays ACTIVE-but-silent, is never
	 * re-fed on resume (chunk reload), and the jukebox mixin can finish the vanilla song (particles
	 * stop). Server thread only. Idempotent.
	 */
	void markFinished() {
		if (finished) {
			return;
		}
		releaseResources();
		finished = true;
	}

	/**
	 * Best sink currently available: a Simple Voice Chat locational channel
	 * when the mod is installed and its server api is ready, otherwise a
	 * {@link NullSink} (with the fallback reason logged). SVC types are only
	 * ever touched through {@link VoiceChatBridge}, which classloads its
	 * backend solely behind the isModLoaded guard. Only called for a non-inert
	 * session (guarded by {@code source != null} in {@link #activate}).
	 */
	private AudioSink selectSink(MinecraftServer server) {
		String reason;
		if (!VoiceChatBridge.isModPresent()) {
			reason = "no-voicechat";
		} else {
			ServerLevel level = server != null ? server.getLevel(pos.dimension()) : null;
			AudioSink voiceSink = level == null
					? null
					: VoiceChatBridge.createSink(level, pos, source.radiusBlocks(), label).orElse(null);
			if (voiceSink != null) {
				return voiceSink;
			}
			reason = "api-not-ready";
		}
		JockeyRadioMod.LOGGER.info("[jockey] sink fallback type=null reason={} {} station={}",
				reason, describePos(), label);
		return new NullSink("station=" + label + " " + describePos());
	}

	private void releaseResources() {
		if (feed != null) {
			if (sink != null) {
				feed.removeSink(sink);
			}
			FeedRegistry.get().release(source.key());
			feed = null;
		}
		if (sink != null) {
			sink.close();
			sink = null;
		}
	}

	/** {@code pos=<x,y,z> dim=<id>} - the exact shape the log contract greps for. */
	String describePos() {
		BlockPos p = pos.pos();
		return "pos=" + p.getX() + "," + p.getY() + "," + p.getZ()
				+ " dim=" + pos.dimension().identifier();
	}
}
