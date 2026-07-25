package fm.gbti.jockey.session;

import fm.gbti.jockey.JockeyRadioMod;
import fm.gbti.jockey.config.HostCapabilityProbe;
import fm.gbti.jockey.config.RadioConfig;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ref-counted registry of shared {@link StationFeed}s: the first session tuned
 * to a station starts its feed, the last one leaving stops it.
 *
 * <p>Thread-safe (all state guarded by the registry lock), though feed
 * lifecycle calls in practice come from the server thread via
 * {@link RadioManager}. Also owns the daemon executor the ffmpeg feeds run
 * their ingest/delivery work on - created lazily here so shutdown ordering
 * lives in one place.</p>
 *
 * <p>Which implementation {@link #acquire(FeedSource)} hands out is decided once
 * per server run by {@link #runStartupDoctor()}: ffmpeg feeds when the config
 * says {@code "ingest": "ffmpeg"} AND the binary answers {@code -version},
 * stub feeds otherwise (sessions still track, nothing streams).</p>
 */
public final class FeedRegistry {

	private static final FeedRegistry INSTANCE = new FeedRegistry();
	private static final long DOCTOR_PROBE_TIMEOUT_SECONDS = 5;

	private final Object lock = new Object();
	private final Map<String, Entry> feeds = new HashMap<>();
	private ExecutorService feedExecutor;
	private boolean shutDown;

	/** Decided by {@link #runStartupDoctor()}; stub feeds until it says otherwise. */
	private volatile boolean useFfmpeg;

	private static final class Entry {
		final StationFeed feed;
		int refs;

		Entry(StationFeed feed) {
			this.feed = feed;
		}
	}

	public static FeedRegistry get() {
		return INSTANCE;
	}

	/**
	 * Acquires (creating and starting if necessary) the shared feed for a
	 * source, incrementing its reference count. The source's namespaced {@link
	 * FeedSource#key()} keys the shared feed: two jukeboxes with the same disc
	 * (or the same config station) share one feed/process, while an admin disc
	 * and a config station that share a bare name stay separate.
	 *
	 * <p>{@link StationFeed#start()} runs outside the registry lock: a real
	 * feed's start/stop may block on its worker threads, and those workers call
	 * back into {@link #feedExecutor()}/{@link #release(String)} - invoking
	 * feed lifecycle methods under the lock would deadlock the server
	 * thread.</p>
	 *
	 * <p>The map key is {@link FeedSource#key()} (namespaced: {@code "disc:x"} /
	 * {@code "station:x"}) so an admin disc and a config station that share a bare
	 * name never collapse onto one shared feed/refcount. Every log line still
	 * reports the bare {@link StationFeed#stationKey()} / {@link
	 * FeedSource#stationKey()}, so the {@code station=} contract is unchanged.</p>
	 *
	 * <p>After {@link #shutdown} has latched, acquire hands out an UNREGISTERED
	 * stub instead: a jukebox event racing {@code SERVER_STOPPING} must not
	 * plant a fresh entry in the cleared feeds map (its ffmpeg start would be
	 * refused anyway - the executor is gone - and the dead entry would survive
	 * into the next run as a stale-feed warning). The later
	 * {@link #release(String)} for it logs "unknown station" and is ignored,
	 * which is exactly right.</p>
	 */
	public StationFeed acquire(FeedSource source) {
		String key = source.key();
		Entry entry;
		StationFeed created = null;
		int refs;
		synchronized (lock) {
			if (shutDown) {
				JockeyRadioMod.LOGGER.warn(
						"[jockey] feed acquire during shutdown station={} - handing out unregistered stub feed",
						source.stationKey());
				return new StubStationFeed(source.stationKey());
			}
			entry = feeds.get(key);
			if (entry == null) {
				entry = new Entry(createFeed(source));
				feeds.put(key, entry);
				created = entry.feed;
			}
			entry.refs++;
			refs = entry.refs;
		}
		if (created != null) {
			try {
				created.start();
			} catch (Throwable t) {
				JockeyRadioMod.LOGGER.error("[jockey] feed failed to start station={}", created.stationKey(), t);
			}
		}
		JockeyRadioMod.LOGGER.info("[jockey] feed acquire station={} refs={}", entry.feed.stationKey(), refs);
		return entry.feed;
	}

	/**
	 * Releases one reference to a station's feed, stopping and discarding the
	 * feed when the count reaches zero. The stop itself runs outside the lock
	 * (see {@link #acquire(FeedSource)}). {@code key} is the namespaced {@link
	 * FeedSource#key()} used to acquire; the {@code station=} log value comes
	 * from the feed's bare {@link StationFeed#stationKey()} so the contract is
	 * unchanged.
	 */
	public void release(String key) {
		StationFeed toStop = null;
		synchronized (lock) {
			Entry entry = feeds.get(key);
			if (entry == null) {
				JockeyRadioMod.LOGGER.warn("[jockey] feed release for unknown station={} (ignored)", key);
				return;
			}
			entry.refs--;
			JockeyRadioMod.LOGGER.info("[jockey] feed release station={} refs={}",
					entry.feed.stationKey(), entry.refs);
			if (entry.refs <= 0) {
				feeds.remove(key);
				toStop = entry.feed;
			}
		}
		if (toStop != null) {
			try {
				toStop.stop();
			} catch (Throwable t) {
				JockeyRadioMod.LOGGER.error("[jockey] feed failed to stop station={}", toStop.stationKey(), t);
			}
		}
	}

	/**
	 * Picks the feed implementation for a source. Only called under the
	 * registry lock, and does no I/O - the ffmpeg process is spawned later by
	 * {@code start()} on a feed executor thread. When {@code ingest=ffmpeg} is
	 * verified the source builds the real feed; a null return (e.g. a config
	 * station with no URL) or {@code ingest=stub} falls back to a stub so
	 * sessions still track and nothing crashes.
	 */
	private StationFeed createFeed(FeedSource source) {
		if (useFfmpeg) {
			StationFeed feed = source.createRealFeed();
			if (feed != null) {
				return feed;
			}
		}
		// Bare stationKey (not the namespaced registry key) so the stub reports
		// the same station= label as a real feed would.
		return new StubStationFeed(source.stationKey());
	}

	/**
	 * Startup doctor, called from {@code RadioManager.onServerStarting}.
	 * Decides between real ffmpeg feeds and stub feeds for this server run:
	 * config {@code "ingest": "stub"} short-circuits to stubs; otherwise the
	 * configured binary must answer {@code -version} within
	 * {@value #DOCTOR_PROBE_TIMEOUT_SECONDS}s or the registry logs one clear
	 * error and falls back to stubs so the server still runs and sessions
	 * still track.
	 */
	public void runStartupDoctor() {
		RadioConfig config = RadioConfig.get();
		if ("stub".equals(config.ingest)) {
			useFfmpeg = false;
			JockeyRadioMod.LOGGER.info("[jockey] ingest=stub (configured) - feeds will not fetch audio");
			return;
		}
		String binary = config.ffmpegBinary;
		// sow-013: a binary uploaded through a managed host's file manager (Modrinth Servers,
		// Pterodactyl panels, ...) lands WITHOUT the execute bit, and those panels expose no chmod.
		// The operator has no shell, so if we don't set it for them the binary is unusable and the
		// error looks identical to "not installed". Only touches a path that actually exists.
		HostCapabilityProbe.ensureExecutable(binary);
		// Same story for yt-dlp: uploaded 644, and a platform disc would otherwise fail with a
		// permission error that reads as "yt-dlp is not installed". Cheap and a no-op when unused.
		HostCapabilityProbe.ensureExecutable(config.ytdlpBinary);
		String failure = probeFfmpeg(binary);
		if (failure == null) {
			useFfmpeg = true;
			JockeyRadioMod.LOGGER.info("[jockey] ingest=ffmpeg binary={} verified", binary);
		} else {
			useFfmpeg = false;
			JockeyRadioMod.LOGGER.error(
					"[jockey] ffmpeg is not runnable ('{} -version' failed: {}) - falling back to stub feeds; "
							+ "sessions still track but no audio will stream. Install ffmpeg or set "
							+ "'ffmpegBinary' in jockey_radio.json.",
					binary, failure);
		}
	}

	/** Runs {@code <binary> -version}; returns null on success, else a short failure description. */
	private static String probeFfmpeg(String binary) {
		Process process = null;
		try {
			// -version prints well under the OS pipe buffer, so waiting without
			// draining cannot wedge the child.
			process = new ProcessBuilder(binary, "-version").redirectErrorStream(true).start();
			if (!process.waitFor(DOCTOR_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				return "timed out after " + DOCTOR_PROBE_TIMEOUT_SECONDS + "s";
			}
			int exitCode = process.exitValue();
			return exitCode == 0 ? null : "exit code " + exitCode;
		} catch (IOException e) {
			return e.getMessage();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return "interrupted";
		} finally {
			if (process != null && process.isAlive()) {
				process.destroyForcibly();
			}
		}
	}

	/**
	 * One {@link StationFeed#getStatsLine()} per live feed, for
	 * {@code /jocky sessions}. The lines are built outside the registry
	 * lock; safe on any thread.
	 */
	public List<String> feedStatsLines() {
		List<StationFeed> snapshot = new ArrayList<>();
		synchronized (lock) {
			for (Entry entry : feeds.values()) {
				snapshot.add(entry.feed);
			}
		}
		List<String> lines = new ArrayList<>(snapshot.size());
		for (StationFeed feed : snapshot) {
			lines.add(feed.getStatsLine());
		}
		return lines;
	}

	/**
	 * One {@link StationFeed#getDiagLine()} per live feed, for the sow-005
	 * {@code /jocky diag} snapshot. Built outside the registry lock like
	 * {@link #feedStatsLines()}; safe on any thread.
	 */
	public List<String> feedDiagLines() {
		List<StationFeed> snapshot = new ArrayList<>();
		synchronized (lock) {
			for (Entry entry : feeds.values()) {
				snapshot.add(entry.feed);
			}
		}
		List<String> lines = new ArrayList<>(snapshot.size());
		for (StationFeed feed : snapshot) {
			lines.add(feed.getDiagLine());
		}
		return lines;
	}

	/**
	 * The mod-owned daemon executor feeds run their upstream work on. Lazily
	 * created; null once {@link #shutdown} has latched (until {@link #reset}).
	 */
	public ExecutorService feedExecutor() {
		synchronized (lock) {
			if (feedExecutor == null && !shutDown) {
				AtomicInteger index = new AtomicInteger();
				ThreadFactory factory = runnable -> {
					Thread thread = new Thread(runnable, "jockey-radio-feed-" + index.getAndIncrement());
					thread.setDaemon(true);
					return thread;
				};
				feedExecutor = Executors.newCachedThreadPool(factory);
			}
			return feedExecutor;
		}
	}

	/**
	 * Stops every remaining feed and shuts the executor down, waiting up to
	 * the given timeout for in-flight work. Called once from
	 * {@code SERVER_STOPPING}; the registry refuses new executors afterwards.
	 */
	public void shutdown(long timeout, TimeUnit unit) {
		List<StationFeed> toStop = new ArrayList<>();
		ExecutorService executor;
		synchronized (lock) {
			shutDown = true;
			for (Entry entry : feeds.values()) {
				toStop.add(entry.feed);
			}
			feeds.clear();
			executor = feedExecutor;
			feedExecutor = null;
		}
		for (StationFeed feed : toStop) {
			try {
				feed.stop();
			} catch (Throwable t) {
				JockeyRadioMod.LOGGER.error("[jockey] feed failed to stop station={} during shutdown",
						feed.stationKey(), t);
			}
		}
		if (executor != null) {
			executor.shutdown();
			try {
				if (!executor.awaitTermination(timeout, unit)) {
					JockeyRadioMod.LOGGER.warn("[jockey] feed executor did not terminate within {} {}, forcing",
							timeout, unit);
					executor.shutdownNow();
				}
			} catch (InterruptedException e) {
				executor.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * Re-arms the registry for a new server run. {@link #shutdown} latches
	 * {@code shutDown}, and with the integrated server a single JVM can start
	 * several servers back to back - without this the executor would stay
	 * unobtainable in every world after the first. Called from
	 * {@code RadioManager.onServerStarting}; feeds are expected to be empty
	 * because the previous shutdown cleared them.
	 */
	public void reset() {
		synchronized (lock) {
			shutDown = false;
			if (!feeds.isEmpty()) {
				JockeyRadioMod.LOGGER.warn("[jockey] feed registry reset with {} stale feed(s) still registered",
						feeds.size());
			}
		}
	}

	/** Number of live feeds (for the dev command / tests). */
	public int feedCount() {
		synchronized (lock) {
			return feeds.size();
		}
	}

	/**
	 * Frames received so far by the live feed registered under the namespaced
	 * {@link FeedSource#key()}, or {@code -1} when no feed is registered for it.
	 * Used by the "Test feed" probe to distinguish a bound-but-idle RTMP
	 * listener ({@code 0}) from one actively receiving ({@code > 0}) and from an
	 * uninserted disc ({@code -1}). Safe on any thread.
	 */
	public long feedFramesRead(String key) {
		synchronized (lock) {
			Entry entry = feeds.get(key);
			return entry == null ? -1L : entry.feed.framesRead();
		}
	}

	/**
	 * Whether the feed registered under the namespaced {@link FeedSource#key()} is
	 * a real streaming feed rather than a {@link StubStationFeed}. False when no
	 * feed is registered OR the live feed is a stub ({@code ingest=stub}, or the
	 * startup doctor found ffmpeg unusable and fell back). The "Test feed" probe
	 * uses this so an RTMP disc backed by a stub - whose {@link
	 * StationFeed#framesRead()} is {@code 0}, indistinguishable from a bound-idle
	 * listener - does not falsely report a bound listener. Safe on any thread.
	 */
	public boolean feedIsStreaming(String key) {
		synchronized (lock) {
			Entry entry = feeds.get(key);
			return entry != null && !(entry.feed instanceof StubStationFeed);
		}
	}

	/**
	 * The declared PLAYOUT phase ({@code "streaming"}/{@code "buffering"}/{@code "idle"})
	 * of the feed registered under the namespaced {@link FeedSource#key()}, or
	 * {@code null} when no feed is registered (sow-005 Phase A). {@code NowPlayingHud}
	 * uses this to render a distinct "buffering..." title while a feed pre-rolls or
	 * rebuffers. Safe on any thread - a volatile-backed read on the feed.
	 */
	public String feedPlayoutState(String key) {
		synchronized (lock) {
			Entry entry = feeds.get(key);
			return entry == null ? null : entry.feed.playoutState();
		}
	}

	/** sow-006: whether the feed for {@code key} carries a truly-live source (else finite/VOD/absent). */
	public boolean feedIsLive(String key) {
		synchronized (lock) {
			Entry entry = feeds.get(key);
			return entry != null && entry.feed.isLive();
		}
	}

	private FeedRegistry() {
	}
}
