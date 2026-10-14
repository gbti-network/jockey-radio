package fm.gbti.jockey.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The HLS reconnect ladder keeps its shape (2 s, 30 s, 60 s, 1 h) but never waits longer
 * than reconnectBackoffMaxSeconds, so a station that comes back on air is picked up within
 * that time.
 */
class HlsReconnectLadderTest {

	private static HlsPullFeed feed(long maxBackoffSeconds) {
		return new HlsPullFeed("test:hls", new StationEndpoints.Endpoint("https://radio.example.com/live.m3u8",
				"https://radio.example.com/live.m3u8", null), "ffmpeg", 600, 150, 150, maxBackoffSeconds,
				true, 50, 0, "rebuffer", 0.0, 0);
	}

	@Test
	@DisplayName("the default 60 s cap holds the ladder at 60 s instead of climbing to an hour")
	void defaultCapStopsTheHourTier() {
		HlsPullFeed f = feed(60);
		assertEquals(2_000L, f.reconnectDelayMillis(2, true, 1));
		assertEquals(30_000L, f.reconnectDelayMillis(2, true, 6));
		assertEquals(60_000L, f.reconnectDelayMillis(2, true, 16));
		assertEquals(60_000L, f.reconnectDelayMillis(2, true, 26), "attempt 26 waited an hour");
		assertEquals(60_000L, f.reconnectDelayMillis(2, true, 500));
	}

	@Test
	@DisplayName("a cap of 3600 restores the full ladder, and a small cap shortens every tier")
	void capIsTheOperatorsChoice() {
		assertEquals(3600L, HlsPullFeed.delaySeconds(26, 3600));
		assertEquals(60L, HlsPullFeed.delaySeconds(25, 3600));
		assertEquals(10L, HlsPullFeed.delaySeconds(6, 10));
		assertEquals(2L, HlsPullFeed.delaySeconds(1, 10));
	}
}
