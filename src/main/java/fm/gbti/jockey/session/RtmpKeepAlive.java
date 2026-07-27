package fm.gbti.jockey.session;

import fm.gbti.jockey.JockeyRadioMod;
import fm.gbti.jockey.config.RadioConfig;
import fm.gbti.jockey.disc.DiscDefinition;
import fm.gbti.jockey.disc.DiscRegistry;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * Keeps every RTMP-receive disc's ffmpeg listener bound and consuming 24/7,
 * independent of players/sessions (sow-008), so a station's push endpoint never
 * disappears on an idle server.
 *
 * <p><b>Mechanism.</b> Holds a permanent "anchor" reference in {@link
 * FeedRegistry} for each keep-alive RTMP disc, keyed exactly like a jukebox
 * session ({@code disc:<id>}, via {@link RadioManager#discFeedSource}). The
 * anchor pins the ref-count &ge; 1 so the shared feed - and its
 * {@link RtmpReceiveFeed} listener - stays alive; when idle the feed's delivery
 * loop simply paces frames to zero sinks, still draining ffmpeg's output so the
 * RTMP push keeps flowing. A jukebox session that tunes in shares the SAME warm
 * feed (instant audio, no cold listener bind), and leaving drops back to the
 * anchor.
 *
 * <p><b>Lifecycle.</b> {@link #arm()} is called once, AFTER
 * {@link FeedRegistry#runStartupDoctor()}, so anchors are real ffmpeg feeds and
 * the pre-doctor {@code DiscRegistry.load()} that fires the change-listener is a
 * safe no-op. Thereafter {@link #onDiscsChanged()} (fired by the
 * {@link DiscRegistry} change-listener on create/edit/delete/reload) and
 * {@link #shutdownAll()} (on server stop, BEFORE
 * {@link RadioManager#shutdownAll()}) keep the anchor set in sync.
 *
 * <p><b>Threading.</b> Every trigger - server start, dialog save/delete, reload
 * command, server stop - runs on the server thread, the same thread
 * {@link RadioManager} uses for session {@code acquire}/{@code release}; the
 * anchor map is additionally guarded by {@code this}.
 */
public final class RtmpKeepAlive {

	private static final RtmpKeepAlive INSTANCE = new RtmpKeepAlive();

	/** Anchored registry key ({@code disc:<id>}) -> the disc def used to acquire it. */
	private final Map<String, DiscDefinition> anchored = new HashMap<>();

	/** Off until {@link #arm()} runs after the startup doctor. */
	private boolean armed;

	private RtmpKeepAlive() {
	}

	public static RtmpKeepAlive get() {
		return INSTANCE;
	}

	/** Number of permanent RTMP feed anchors. Safe for diagnostics on any thread. */
	public synchronized int anchoredCount() {
		return anchored.size();
	}

	/**
	 * Arms keep-alive and performs the initial reconcile. Call once from
	 * {@code RadioManager.onServerStarting} AFTER the startup doctor has decided
	 * ffmpeg-vs-stub, so anchors are real feeds.
	 */
	public synchronized void arm() {
		armed = true;
		reconcile();
	}

	/** Change-listener target: reconcile if armed, else ignore (startup load). */
	public synchronized void onDiscsChanged() {
		if (armed) {
			reconcile();
		}
	}

	/**
	 * Brings the live anchor set in line with the desired set (keep-alive RTMP
	 * discs when {@link RadioConfig#rtmpKeepAlive} is on): releases anchors that are
	 * gone or whose RTMP identity (bind/port/key) changed, then acquires the ones
	 * not yet anchored. An identity change therefore release+re-acquires, which
	 * recreates the feed with new settings when it is idle (no session holding it).
	 */
	private void reconcile() {
		if (!armed) {
			return;
		}
		Map<String, DiscDefinition> desired = new HashMap<>();
		if (RadioConfig.get().rtmpKeepAlive) {
			for (DiscDefinition def : DiscRegistry.get().list()) {
				if (def.sourceType == DiscDefinition.SourceType.RTMP_RECEIVE) {
					desired.put("disc:" + def.discId, def);
				}
			}
		}

		// Release anchors no longer wanted, or whose ingest identity changed.
		Iterator<Map.Entry<String, DiscDefinition>> it = anchored.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<String, DiscDefinition> entry = it.next();
			String key = entry.getKey();
			DiscDefinition want = desired.get(key);
			if (want == null || rtmpIdentityChanged(entry.getValue(), want)) {
				it.remove();
				try {
					// An audible monitor holds an independent ref on this same feed key. Unless it
					// is torn down first, release() below cannot reach zero refs, the stale feed is
					// never stopped, and the re-acquire hands back the OLD listener (old bind/port/
					// stream key) - so a rotated key silently keeps accepting the revoked value.
					if (key.startsWith("disc:")) {
						MonitorService.get().stopForDisc(key.substring("disc:".length()));
					}
					FeedRegistry.get().release(key);
					JockeyRadioMod.LOGGER.info("[jockey] rtmp keep-alive released station={}", key);
				} catch (Throwable t) {
					JockeyRadioMod.LOGGER.error("[jockey] rtmp keep-alive release failed station={}", key, t);
				}
			}
		}

		// Acquire the desired anchors not yet held.
		for (Map.Entry<String, DiscDefinition> entry : desired.entrySet()) {
			String key = entry.getKey();
			DiscDefinition def = entry.getValue();
			if (anchored.containsKey(key)) {
				continue;
			}
			try {
				FeedRegistry.get().acquire(RadioManager.discFeedSource(def.discId, def));
				anchored.put(key, def);
				JockeyRadioMod.LOGGER.info(
						"[jockey] rtmp keep-alive anchored station={} port={} - listener held 24/7",
						key, def.rtmpPort);
			} catch (Throwable t) {
				// Never block server start on a bad disc; it just won't be kept alive.
				JockeyRadioMod.LOGGER.error("[jockey] rtmp keep-alive acquire failed station={}", key, t);
			}
		}
	}

	/** True if the ffmpeg listen params (bind/port/key) differ - needs a fresh feed. */
	private static boolean rtmpIdentityChanged(DiscDefinition a, DiscDefinition b) {
		return a.rtmpPort != b.rtmpPort
				|| !Objects.equals(a.rtmpBind, b.rtmpBind)
				|| !Objects.equals(a.streamKey, b.streamKey);
	}

	/**
	 * Releases every anchor and disarms. Called from a {@code SERVER_STOPPING}
	 * handler registered BEFORE {@link RadioManager#shutdownAll()} so ref-counts
	 * unwind while the registry is still live (no "unknown station" warnings).
	 */
	public synchronized void shutdownAll() {
		armed = false;
		if (anchored.isEmpty()) {
			return;
		}
		for (String key : anchored.keySet()) {
			try {
				FeedRegistry.get().release(key);
			} catch (Throwable t) {
				JockeyRadioMod.LOGGER.error("[jockey] rtmp keep-alive release failed on shutdown station={}", key, t);
			}
		}
		JockeyRadioMod.LOGGER.info("[jockey] rtmp keep-alive released {} anchor(s) on shutdown", anchored.size());
		anchored.clear();
	}
}
