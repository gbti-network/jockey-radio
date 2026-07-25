package fm.gbti.jockey.session;

import fm.gbti.jockey.JockeyRadioMod;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Placeholder feed: no network, no audio - just lifecycle logging and a
 * thread-safe sink list so the session plumbing can be exercised end to end.
 * Used when the config sets {@code "ingest": "stub"} or when the startup
 * doctor finds ffmpeg unusable (sessions still track, nothing streams).
 */
public final class StubStationFeed implements StationFeed {

	private final String stationKey;
	private final List<AudioSink> sinks = new CopyOnWriteArrayList<>();

	public StubStationFeed(String stationKey) {
		this.stationKey = stationKey;
	}

	@Override
	public String stationKey() {
		return stationKey;
	}

	@Override
	public void start() {
		JockeyRadioMod.LOGGER.info("[jockey] feed start station={} (stub, no audio yet)", stationKey);
	}

	@Override
	public void stop() {
		JockeyRadioMod.LOGGER.info("[jockey] feed stop station={} (stub)", stationKey);
		sinks.clear();
	}

	@Override
	public void addSink(AudioSink sink) {
		sinks.add(sink);
	}

	@Override
	public void removeSink(AudioSink sink) {
		sinks.remove(sink);
	}

	@Override
	public String getStatsLine() {
		return "station=" + stationKey + " ingest=stub sinks=" + sinks.size();
	}

	public int sinkCount() {
		return sinks.size();
	}
}
