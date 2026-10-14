package fm.gbti.jockey.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The idle grace that stops a hopper from respawning ffmpeg on every cycle, and the two
 * rules that keep it from serving stale audio: a finite feed still stops at once, and an
 * idle feed is only resumed for a source with the same ingest fingerprint.
 */
class FeedRegistryGraceTest {

	private final FeedRegistry registry = FeedRegistry.get();
	private final List<FakeFeed> built = new ArrayList<>();

	@BeforeEach
	void setUp() {
		registry.reset();
		registry.useRealFeedsForTest(true);
	}

	@AfterEach
	void tearDown() {
		// The registry is a process-wide singleton: leave it empty for the next test.
		registry.shutdown(1, TimeUnit.SECONDS);
		registry.reset();
		registry.useRealFeedsForTest(false);
	}

	@Test
	@DisplayName("a live feed survives a quick release and re-acquire, then stops after the grace")
	void liveFeedOutlivesItsLastHolder() {
		FeedSource source = source("disc:live", "fp-1", true);
		StationFeed first = registry.acquire(source);
		registry.release("disc:live");
		assertEquals(0, built.get(0).stops, "stopped at refs 0");

		StationFeed again = registry.acquire(source);
		assertSame(first, again, "a new feed was built inside the grace");
		assertEquals(1, built.size());
		assertEquals(1, built.get(0).starts, "the resumed feed was started twice");

		registry.release("disc:live");
		registry.expireIdle(System.nanoTime() + FeedRegistry.IDLE_GRACE_NANOS / 2);
		assertEquals(0, built.get(0).stops, "stopped before the grace ran out");
		registry.expireIdle(System.nanoTime() + FeedRegistry.IDLE_GRACE_NANOS + 1);
		assertEquals(1, built.get(0).stops);
		assertEquals(0, registry.feedCount());
	}

	@Test
	@DisplayName("an idle feed built from old settings is replaced, and stops before its successor starts")
	void editedSourceNeverResumesAStaleFeed() {
		StationFeed old = registry.acquire(source("disc:rtmp", "key-old", true));
		registry.release("disc:rtmp");

		StationFeed fresh = registry.acquire(source("disc:rtmp", "key-new", true));
		assertNotSame(old, fresh);
		assertEquals(1, built.get(0).stops, "the stale feed is still running");
		assertEquals(1, built.get(1).starts);
		assertEquals(List.of("start 0", "stop 0", "start 1"), events);
	}

	@Test
	@DisplayName("a finite feed stops as soon as its last holder leaves, so re-inserting restarts it")
	void finiteFeedStopsAtOnce() {
		registry.acquire(source("disc:file", "fp", false));
		registry.release("disc:file");
		assertEquals(1, built.get(0).stops);
		assertEquals(0, registry.feedCount());
	}

	@Test
	@DisplayName("stopUnreferenced ends idle feeds without touching held ones")
	void stopUnreferencedEndsOnlyIdleFeeds() {
		registry.acquire(source("disc:a", "fp", true));
		registry.acquire(source("disc:b", "fp", true));
		registry.release("disc:a");

		registry.stopUnreferenced();
		assertEquals(1, built.get(0).stops);
		assertEquals(0, built.get(1).stops);
		assertEquals(1, registry.feedCount());
		registry.release("disc:b");
	}

	@Test
	@DisplayName("releasing an idle feed again is ignored rather than driving refs negative")
	void doubleReleaseIsIgnored() {
		FeedSource source = source("disc:twice", "fp", true);
		registry.acquire(source);
		registry.release("disc:twice");
		registry.release("disc:twice");

		registry.acquire(source);
		registry.release("disc:twice");
		registry.expireIdle(System.nanoTime() + FeedRegistry.IDLE_GRACE_NANOS + 1);
		assertEquals(1, built.get(0).stops);
	}

	private final List<String> events = new ArrayList<>();

	private FeedSource source(String key, String fingerprint, boolean live) {
		return new FeedSource(key, key, 32, fingerprint, () -> {
			FakeFeed feed = new FakeFeed(key, live, built.size());
			built.add(feed);
			return feed;
		});
	}

	private final class FakeFeed implements StationFeed {
		final String key;
		final boolean live;
		final int index;
		int starts;
		int stops;

		FakeFeed(String key, boolean live, int index) {
			this.key = key;
			this.live = live;
			this.index = index;
		}

		@Override
		public String stationKey() {
			return key;
		}

		@Override
		public void start() {
			starts++;
			events.add("start " + index);
		}

		@Override
		public void stop() {
			stops++;
			events.add("stop " + index);
		}

		@Override
		public void addSink(AudioSink sink) {
		}

		@Override
		public void removeSink(AudioSink sink) {
		}

		@Override
		public String getStatsLine() {
			return key;
		}

		@Override
		public boolean isLive() {
			return live;
		}
	}
}
