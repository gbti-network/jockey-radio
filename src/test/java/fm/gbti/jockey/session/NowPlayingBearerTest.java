package fm.gbti.jockey.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import fm.gbti.jockey.disc.DiscDefinition;

import org.junit.jupiter.api.Test;

/** The stream token is only ever sent to the stream's own origin. */
class NowPlayingBearerTest {

	private static final String STREAM = "http://127.0.0.1:7101/api/public/stations/2/stream/trunk.m3u8";

	@Test
	void streamTokenGoesToTheDerivedSameOriginEndpoint() {
		DiscDefinition def = disc(STREAM, "SECRET");
		assertEquals("SECRET", NowPlayingPoller.bearerFor(def, def.resolveNowPlayingUrl()));
	}

	@Test
	void streamTokenNeverGoesToAnotherHost() {
		DiscDefinition def = disc(STREAM, "SECRET");
		assertNull(NowPlayingPoller.bearerFor(def, "https://nowplaying.example.org/api/now-playing"));
	}

	@Test
	void streamTokenNeverGoesToAnotherPortOnTheSameHost() {
		DiscDefinition def = disc(STREAM, "SECRET");
		assertNull(NowPlayingPoller.bearerFor(def, "http://127.0.0.1:7102/api/public/stations/2/now-playing"));
	}

	@Test
	void legacyHostDiscSendsItsTokenToItsOwnHost() {
		DiscDefinition def = disc(null, "SECRET");
		def.host = "radio.example.com";
		def.stationId = "2";
		assertEquals("SECRET", NowPlayingPoller.bearerFor(def, def.resolveNowPlayingUrl()));
	}

	@Test
	void anExplicitNowPlayingTokenIsSentToTheNowPlayingEndpoint() {
		DiscDefinition def = disc(STREAM, "SECRET");
		def.nowPlayingToken = "NP";
		assertEquals("NP", NowPlayingPoller.bearerFor(def, "https://nowplaying.example.org/api/now-playing"));
	}

	@Test
	void noTokenNoBearer() {
		assertNull(NowPlayingPoller.bearerFor(disc(STREAM, null), "http://127.0.0.1:7101/x"));
	}

	private static DiscDefinition disc(String streamUrl, String token) {
		DiscDefinition def = new DiscDefinition();
		def.discId = "station";
		def.sourceType = DiscDefinition.SourceType.HLS_PULL;
		def.streamUrl = streamUrl;
		def.token = token;
		return def;
	}
}
