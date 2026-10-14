package fm.gbti.jockey.disc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Minecraft operator is not always the host operator, so a disc URL must not be able to
 * point ffmpeg, yt-dlp or the now-playing poller at the cloud metadata service or another
 * link-local address. Checked on the literal URL, the way ffmpeg's resolver reads it.
 */
class MetadataTargetTest {

	private static DiscDefinition withStream(String url) {
		DiscDefinition d = new DiscDefinition();
		d.discId = "probe";
		d.name = "Probe";
		d.sourceType = DiscDefinition.SourceType.HLS_PULL;
		d.streamUrl = url;
		d.applyDefaults();
		return d;
	}

	@Test
	@DisplayName("link-local and metadata targets are refused for every URL field")
	void metadataTargetsAreRefused() {
		List<String> refused = List.of(
				"http://169.254.169.254/latest/meta-data/",
				"http://169.254.0.1:8080/x",
				"http://0251.254.169.254/latest/meta-data/", // inet_aton reads 0251 as octal 169
				"http://169.254.169.0376/", // and 0376 as 254
				"http://[fe80::1]/",
				"http://[FEBF::1]/",
				"http://[fd00:ec2::254]/latest/meta-data/",
				"http://[::ffff:169.254.169.254]/",
				"http://[::ffff:a9fe:a9fe]/",
				"http://metadata.google.internal/computeMetadata/v1/",
				"http://METADATA.GOOGLE.INTERNAL./computeMetadata/v1/",
				"http://metadata/computeMetadata/v1/",
				"http://instance-data/latest/meta-data/",
				"http://instance-data.ec2.internal/latest/meta-data/");
		for (String url : refused) {
			assertTrue(withStream(url).validate().isPresent(), "stream URL " + url + " was accepted");
			DiscDefinition np = withStream("https://radio.example.com/live.m3u8");
			np.nowPlayingUrl = url;
			assertTrue(np.validate().isPresent(), "now-playing URL " + url + " was accepted");
		}
		DiscDefinition legacy = withStream(null);
		legacy.host = "169.254.169.254";
		legacy.stationId = "2";
		assertTrue(legacy.validate().isPresent(), "legacy host 169.254.169.254 was accepted");
	}

	@Test
	@DisplayName("loopback, LAN, Tailscale and ordinary hosts still work")
	void ordinaryTargetsAreKept() {
		List<String> kept = List.of(
				"http://127.0.0.1:24462/audio/track.mp3", // the local audio server
				"http://localhost:7101/api/public/stations/2/now-playing",
				"http://[::1]:7101/np",
				"http://192.168.1.10:8000/stream",
				"http://10.0.0.5/live.m3u8",
				"http://100.100.100.100/live.m3u8",
				"http://169.255.0.1/live.m3u8",
				"http://[fec0::1]/live.m3u8",
				"https://metadata.example.com/live.m3u8",
				"https://radio.example.com/live.m3u8");
		for (String url : kept) {
			assertEquals(Optional.empty(), withStream(url).validate(), url);
		}
	}
}
