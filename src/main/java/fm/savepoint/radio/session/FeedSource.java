package fm.savepoint.radio.session;

import java.util.function.Supplier;

/**
 * What a {@link RadioSession} needs to obtain its shared feed, resolved once by
 * {@link RadioManager} when a disc is inserted. Decouples the two disc
 * namespaces from {@link FeedRegistry}: the manager builds the right source
 * (a registry-backed admin disc keyed by {@code discId}, or a sow-001 config
 * station keyed by its station key) and the registry just shares one feed per
 * {@link #key()}.
 *
 * <ul>
 *   <li>{@link #key()} - the feed-registry key. Namespaced ({@code "disc:x"} for
 *       admin discs, {@code "station:x"} for config stations) so the two disc
 *       namespaces never collapse onto one shared feed/refcount when an admin
 *       disc id happens to equal a config station key. Never logged.</li>
 *   <li>{@link #stationKey()} - the bare {@code station=} label in every feed log
 *       line. Admin discs use their {@code discId}; config stations keep their
 *       station key (so sow-001's logs are unchanged). This, NOT {@link #key()},
 *       is what feeds report as their {@code stationKey}.</li>
 *   <li>{@link #radiusBlocks()} - the voicechat channel radius for the sink.</li>
 *   <li>{@link #createRealFeed()} - builds the real ffmpeg-backed feed, or
 *       returns {@code null} to fall back to a stub (e.g. a config station that
 *       lost its URL). Invoked only when {@code ingest=ffmpeg} is verified,
 *       under the registry lock; it does no I/O (ffmpeg spawns later in
 *       {@code start()}).</li>
 * </ul>
 */
public final class FeedSource {

	private final String key;
	private final String stationKey;
	private final int radiusBlocks;
	private final Supplier<StationFeed> realFeedFactory;

	public FeedSource(String key, String stationKey, int radiusBlocks, Supplier<StationFeed> realFeedFactory) {
		this.key = key;
		this.stationKey = stationKey;
		this.radiusBlocks = radiusBlocks;
		this.realFeedFactory = realFeedFactory;
	}

	/** Namespaced feed-registry key ({@code "disc:x"} / {@code "station:x"}); never logged. */
	public String key() {
		return key;
	}

	/** Bare {@code station=} log label (discId or config station key). */
	public String stationKey() {
		return stationKey;
	}

	public int radiusBlocks() {
		return radiusBlocks;
	}

	/** The real feed, or null to fall back to a stub. */
	StationFeed createRealFeed() {
		return realFeedFactory.get();
	}
}
