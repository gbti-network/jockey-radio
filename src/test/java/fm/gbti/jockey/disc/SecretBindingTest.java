package fm.gbti.jockey.disc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** A stored token stays bound to the origin it was entered for. */
class SecretBindingTest {

	@Test
	void originNormalisesCaseAndDefaultPorts() {
		assertEquals("https://radio.example.com:443", Origins.of("HTTPS://Radio.Example.com/live/trunk.m3u8?x=1"));
		assertTrue(Origins.same("http://127.0.0.1/a", "http://127.0.0.1:80/b"));
		assertFalse(Origins.same("http://127.0.0.1:7101/a", "http://127.0.0.1:7102/a"));
		assertFalse(Origins.same("http://radio.example.com/a", "https://radio.example.com/a"));
		assertFalse(Origins.same("https://radio.example.com/a", "https://radio.example.com.evil.net/a"));
		assertNull(Origins.of("not a url"));
		assertNull(Origins.of(null));
	}

	@Test
	void carriedStreamTokenIsDroppedWhenTheStreamMovesHost() {
		DiscDefinition previous = hls("https://radio.example.com/radio/broadcast/2/trunk.m3u8", "SECRET");
		DiscDefinition edited = hls("https://other.example.net/live.m3u8", "SECRET");

		List<String> dropped = edited.dropSecretsMovedFrom(previous, true);

		assertNull(edited.token);
		assertEquals(List.of("stream token"), dropped);
	}

	@Test
	void carriedStreamTokenSurvivesAPathChangeOnTheSameHost() {
		DiscDefinition previous = hls("https://radio.example.com/radio/broadcast/2/trunk.m3u8", "SECRET");
		DiscDefinition edited = hls("https://radio.example.com/radio/broadcast/3/trunk.m3u8", "SECRET");

		assertTrue(edited.dropSecretsMovedFrom(previous, true).isEmpty());
		assertEquals("SECRET", edited.token);
	}

	@Test
	void aTokenTypedInThisEditIsKeptForTheNewHost() {
		DiscDefinition previous = hls("https://radio.example.com/trunk.m3u8", "OLD");
		DiscDefinition edited = hls("https://other.example.net/trunk.m3u8", "NEW");

		assertTrue(edited.dropSecretsMovedFrom(previous, false).isEmpty());
		assertEquals("NEW", edited.token);
	}

	@Test
	void nowPlayingTokenIsDroppedWhenTheNowPlayingEndpointMovesHost() {
		DiscDefinition previous = hls("https://radio.example.com/trunk.m3u8", null);
		previous.nowPlayingUrl = "https://radio.example.com/now-playing";
		previous.nowPlayingToken = "NP";
		DiscDefinition edited = hls("https://radio.example.com/trunk.m3u8", null);
		edited.nowPlayingUrl = "https://elsewhere.example.org/now-playing";
		edited.nowPlayingToken = "NP";

		assertEquals(List.of("now-playing token"), edited.dropSecretsMovedFrom(previous, false));
		assertNull(edited.nowPlayingToken);
	}

	private static DiscDefinition hls(String streamUrl, String token) {
		DiscDefinition def = new DiscDefinition();
		def.discId = "station";
		def.sourceType = DiscDefinition.SourceType.HLS_PULL;
		def.streamUrl = streamUrl;
		def.token = token;
		return def;
	}
}
