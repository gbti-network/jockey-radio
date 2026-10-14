package fm.gbti.jockey.disc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The validation that stands between admin input (or a hand-edited discs.json) and an ffmpeg
 * command line, an outbound request or a listening socket. Each case starts from a valid disc
 * and changes one field.
 */
class DiscValidationTest {

	private static DiscDefinition hls() {
		DiscDefinition d = new DiscDefinition();
		d.discId = "lofi";
		d.name = "Lo-fi";
		d.sourceType = DiscDefinition.SourceType.HLS_PULL;
		d.streamUrl = "https://radio.example.com/live.m3u8";
		return d;
	}

	private static DiscDefinition legacyHls() {
		DiscDefinition d = hls();
		d.streamUrl = null;
		d.host = "radio.example.com";
		d.stationId = "2";
		return d;
	}

	private static DiscDefinition rtmp() {
		DiscDefinition d = new DiscDefinition();
		d.discId = "tavern";
		d.name = "Tavern";
		d.sourceType = DiscDefinition.SourceType.RTMP_RECEIVE;
		d.rtmpPort = 1936;
		d.streamKey = "k3y_With-Mixed_Case0123";
		d.rtmpBind = "127.0.0.1";
		return d;
	}

	private static Optional<String> check(DiscDefinition d, Consumer<DiscDefinition> change) {
		change.accept(d);
		d.applyDefaults();
		return d.validate();
	}

	private static void valid(DiscDefinition d, Consumer<DiscDefinition> change, String why) {
		assertEquals(Optional.empty(), check(d, change), why);
	}

	private static void refused(DiscDefinition d, Consumer<DiscDefinition> change, String why) {
		assertTrue(check(d, change).isPresent(), why + " was accepted");
	}

	@Test
	@DisplayName("the starting discs are valid")
	void baselines() {
		valid(hls(), d -> { }, "hls");
		valid(legacyHls(), d -> { }, "legacy hls");
		valid(rtmp(), d -> { }, "rtmp");
	}

	@Test
	@DisplayName("disc ids are 1 to 32 of a-z, 0-9, _ and -")
	void discIds() {
		valid(hls(), d -> d.discId = "lofi_beats-2", "lofi_beats-2");
		valid(hls(), d -> d.discId = "a".repeat(32), "32 characters");
		valid(hls(), d -> d.discId = "  LoFi ", "case and spaces are normalised");
		for (String bad : List.of("", "a".repeat(33), "lo fi", "../etc", "lofi/2", "ló-fi", "lofi;rm")) {
			refused(hls(), d -> d.discId = bad, "disc id '" + bad + "'");
		}
		refused(hls(), d -> d.discId = null, "a null disc id");
	}

	@Test
	@DisplayName("stream and now-playing URLs are absolute http or https only")
	void urlSchemes() {
		valid(hls(), d -> d.streamUrl = "http://127.0.0.1:7101/radio/broadcast/2/trunk.m3u8", "http");
		valid(hls(), d -> d.streamUrl = "HTTPS://Radio.Example.com/live.m3u8", "scheme case");
		List<String> refusedUrls = List.of(
				"file:///etc/passwd", "file://localhost/etc/passwd", "FILE://server/share/a.mp3",
				"ftp://radio.example.com/a.mp3", "pipe:0", "rtmp://radio.example.com/live",
				"javascript:alert(1)", "data:audio/mp3;base64,AAAA", "concat:a.mp3|b.mp3",
				"//radio.example.com/live.m3u8", "radio.example.com/live.m3u8", "http:///live.m3u8",
				"-i http://radio.example.com/a", "https://radio.example.com/a b", "https://radio.example.com/a\tb",
				"https://radio.example.com/a\r\nX-Injected: 1", "https://radio.example.com/" + "a".repeat(500));
		for (String bad : refusedUrls) {
			refused(hls(), d -> d.streamUrl = bad, "stream URL '" + bad + "'");
			refused(hls(), d -> d.nowPlayingUrl = bad, "now-playing URL '" + bad + "'");
			refused(rtmp(), d -> d.nowPlayingUrl = bad, "now-playing URL on an RTMP disc '" + bad + "'");
		}
	}

	@Test
	@DisplayName("file and platform discs need an http or https URL")
	void fileAndPlatformUrls() {
		DiscDefinition file = hls();
		file.sourceType = DiscDefinition.SourceType.FILE;
		valid(file, d -> d.streamUrl = "http://127.0.0.1:24462/audio/track.mp3", "a local file URL");
		refused(hls(), d -> {
			d.sourceType = DiscDefinition.SourceType.FILE;
			d.streamUrl = "file:///home/server/track.mp3";
		}, "a file: URL on a file disc");
		refused(hls(), d -> {
			d.sourceType = DiscDefinition.SourceType.FILE;
			d.streamUrl = null;
		}, "a file disc without a URL");
		refused(hls(), d -> {
			d.sourceType = DiscDefinition.SourceType.PLATFORM;
			d.streamUrl = null;
		}, "a platform disc without a URL");
		refused(hls(), d -> d.sourceType = null, "a missing source type");
	}

	@Test
	@DisplayName("a legacy host is a bare host[:port] and the station id is digits")
	void legacyHostAndStation() {
		valid(legacyHls(), d -> d.host = "radio.example.com:8443", "host with a port");
		valid(legacyHls(), d -> d.host = "127.0.0.1:7101", "IP with a port");
		for (String bad : List.of("https://radio.example.com", "radio.example.com/live", "radio.example.com:0",
				"radio.example.com:99999", "radio_example.com", "radio example.com", "a".repeat(129))) {
			refused(legacyHls(), d -> d.host = bad, "host '" + bad + "'");
		}
		for (String bad : List.of("", "2a", "-2", "1".repeat(33))) {
			refused(legacyHls(), d -> d.stationId = bad, "station id '" + bad + "'");
		}
	}

	@Test
	@DisplayName("RTMP ports are 1024 to 65535, never 1935")
	void rtmpPorts() {
		valid(rtmp(), d -> d.rtmpPort = 1024, "1024");
		valid(rtmp(), d -> d.rtmpPort = 65535, "65535");
		for (int bad : new int[] {0, 80, 1023, 1935, 65536, -1}) {
			refused(rtmp(), d -> d.rtmpPort = bad, "port " + bad);
		}
	}

	@Test
	@DisplayName("stream keys are 1 to 64 of A-Z, a-z, 0-9, _ and -, and are never echoed")
	void streamKeys() {
		valid(rtmp(), d -> d.streamKey = "k".repeat(64), "64 characters");
		// The 16-character minimum is the dialog's rule for a newly entered key: validate()
		// also runs on load, where a shorter key saved by an older build must still work.
		valid(rtmp(), d -> d.streamKey = "abc", "a short key from an older build");
		for (String bad : List.of("", "k".repeat(65), "key with space", "key;rm -rf", "kéy", "key\u0000")) {
			Optional<String> error = check(rtmp(), d -> d.streamKey = bad);
			assertTrue(error.isPresent(), "stream key '" + bad + "' was accepted");
			if (!bad.isEmpty()) {
				assertFalse(error.get().contains(bad), "the error repeats the rejected key");
			}
		}
		refused(rtmp(), d -> d.streamKey = null, "a missing stream key");
	}

	@Test
	@DisplayName("RTMP binds are a bare host or IP")
	void rtmpBinds() {
		valid(rtmp(), d -> d.rtmpBind = "0.0.0.0", "0.0.0.0");
		valid(rtmp(), d -> d.rtmpBind = "::", "::");
		for (String bad : List.of("0.0.0.0/0", "127.0.0.1 -f", "rtmp://0.0.0.0")) {
			refused(rtmp(), d -> d.rtmpBind = bad, "bind '" + bad + "'");
		}
	}

	@Test
	@DisplayName("radius, colour and base disc are range and shape checked")
	void presentation() {
		valid(hls(), d -> d.radiusBlocks = 0, "0 means the default radius");
		refused(hls(), d -> d.radiusBlocks = 513, "radius 513");
		refused(hls(), d -> d.radiusBlocks = -1, "radius -1");
		Optional<String> colour = check(hls(), d -> d.nameColorHex = "#ZZ0000");
		assertTrue(colour.isPresent(), "colour #ZZ0000 was accepted");
		assertFalse(colour.get().contains("#ZZ0000"), "the error repeats the rejected colour");
		refused(hls(), d -> d.baseDisc = "not an item", "base disc 'not an item'");
		refused(hls(), d -> d.name = "n".repeat(49), "a 49-character name");
		refused(hls(), d -> d.nowPlayingTemplate = "t".repeat(129), "a 129-character template");
	}

	@Test
	@DisplayName("origins resist userinfo and lookalike hosts")
	void origins() {
		assertEquals("https://evil.net:443", Origins.of("https://radio.example.com@evil.net/np"));
		assertFalse(Origins.same("https://radio.example.com/a", "https://radio.example.com@evil.net/a"));
		assertFalse(Origins.same("https://radio.example.com/a", "https://evil.net/?radio.example.com"));
		assertFalse(Origins.same("https://radio.example.com/a", "https://radio.example.com./a"),
				"a trailing dot is treated as another host, so a token is dropped rather than sent");
		assertTrue(Origins.same("https://radio.example.com:443/a", "https://radio.example.com/b"));
		assertTrue(Origins.same("rtmp://radio.example.com/live", "rtmp://radio.example.com:1935/x"));
		assertEquals("http://[::1]:7101", Origins.of("http://[::1]:7101/np"));
		assertNull(Origins.of("https://evil.net\\@radio.example.com/"), "a backslash URL has no origin");
		assertNull(Origins.of("mailto:dj@radio.example.com"), "no host, no origin");
	}
}
