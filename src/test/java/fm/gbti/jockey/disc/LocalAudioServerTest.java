package fm.gbti.jockey.disc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loopback file server behind FILE discs, run for real on an ephemeral port: byte ranges
 * (ffmpeg seeks with them) and the limits that stop stalled or excess connections from holding
 * its threads.
 */
class LocalAudioServerTest {

	private static final byte[] AUDIO = new byte[1000];

	static {
		for (int i = 0; i < AUDIO.length; i++) {
			AUDIO[i] = (byte) i;
		}
	}

	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
	private LocalAudioServer server;

	@AfterEach
	void stop() {
		if (server != null) {
			server.stop();
		}
	}

	private String serve(Path dir, LocalAudioServer s) throws IOException {
		Files.write(dir.resolve("track.mp3"), AUDIO);
		server = s;
		int free;
		try (java.net.ServerSocket probe = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			free = probe.getLocalPort();
		}
		server.start(dir, free);
		assertTrue(server.port() > 0, "server did not start");
		return "http://127.0.0.1:" + server.port() + "/audio/track.mp3";
	}

	private HttpResponse<byte[]> get(String url, String range) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET();
		if (range != null) {
			b.header("Range", range);
		}
		return client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
	}

	private static byte[] slice(int from, int toInclusive) {
		return Arrays.copyOfRange(AUDIO, from, toInclusive + 1);
	}

	@Test
	@DisplayName("byte ranges: suffix, open-ended, closed, over-long, unsatisfiable and ignored")
	void byteRanges(@TempDir Path dir) throws Exception {
		String url = serve(dir, new LocalAudioServer());

		HttpResponse<byte[]> suffix = get(url, "bytes=-100");
		assertEquals(206, suffix.statusCode(), "bytes=-100 is the last 100 bytes");
		assertEquals("bytes 900-999/1000", suffix.headers().firstValue("Content-Range").orElse(""));
		assertArrayEquals(slice(900, 999), suffix.body());

		HttpResponse<byte[]> longSuffix = get(url, "bytes=-5000");
		assertEquals(206, longSuffix.statusCode());
		assertEquals("bytes 0-999/1000", longSuffix.headers().firstValue("Content-Range").orElse(""));
		assertArrayEquals(AUDIO, longSuffix.body());

		HttpResponse<byte[]> open = get(url, "bytes=990-");
		assertEquals(206, open.statusCode());
		assertEquals("bytes 990-999/1000", open.headers().firstValue("Content-Range").orElse(""));
		assertArrayEquals(slice(990, 999), open.body());

		HttpResponse<byte[]> closed = get(url, "bytes=10-19");
		assertEquals(206, closed.statusCode());
		assertEquals("bytes 10-19/1000", closed.headers().firstValue("Content-Range").orElse(""));
		assertArrayEquals(slice(10, 19), closed.body());

		HttpResponse<byte[]> overLong = get(url, "bytes=995-123456");
		assertEquals(206, overLong.statusCode());
		assertEquals("bytes 995-999/1000", overLong.headers().firstValue("Content-Range").orElse(""));

		for (String unsatisfiable : List.of("bytes=1000-", "bytes=5000-6000", "bytes=-0")) {
			HttpResponse<byte[]> r = get(url, unsatisfiable);
			assertEquals(416, r.statusCode(), unsatisfiable);
			assertEquals("bytes */1000", r.headers().firstValue("Content-Range").orElse(""), unsatisfiable);
		}

		for (String ignored : List.of("bytes=abc", "bytes=20-10", "bytes=0-1,5-6", "items=0-1")) {
			HttpResponse<byte[]> r = get(url, ignored);
			assertEquals(200, r.statusCode(), ignored);
			assertArrayEquals(AUDIO, r.body(), ignored);
		}

		HttpResponse<byte[]> whole = get(url, null);
		assertEquals(200, whole.statusCode());
		assertArrayEquals(AUDIO, whole.body());
	}

	/** Opens a connection and sends only part of a request line, then goes quiet. */
	private Socket stalledRequest(int port) throws IOException {
		Socket s = new Socket(InetAddress.getLoopbackAddress(), port);
		s.setSoTimeout(3000);
		s.getOutputStream().write("GET /audio/track.mp3 HT".getBytes(StandardCharsets.US_ASCII));
		s.getOutputStream().flush();
		return s;
	}

	/** True when the server closed the connection before the client's read timeout. */
	private static boolean closedByServer(Socket s) {
		try (s) {
			InputStream in = s.getInputStream();
			byte[] buf = new byte[4096];
			while (in.read(buf) != -1) {
				// drain anything the server sent before closing
			}
			return true;
		} catch (SocketTimeoutException stillOpen) {
			return false;
		} catch (IOException reset) {
			return true;
		}
	}

	@Test
	@DisplayName("a request that never finishes is closed after the request timeout")
	void stalledRequestIsClosed(@TempDir Path dir) throws Exception {
		serve(dir, new LocalAudioServer(4, 300, 60_000));
		Socket stalled = stalledRequest(server.port());
		assertTrue(closedByServer(stalled), "the stalled request was still open after 3 s");
	}

	@Test
	@DisplayName("connections beyond the thread limit are refused, not queued")
	void excessConnectionsAreRefused(@TempDir Path dir) throws Exception {
		String url = serve(dir, new LocalAudioServer(2, 60_000, 60_000));
		List<Socket> holding = new ArrayList<>();
		holding.add(stalledRequest(server.port()));
		holding.add(stalledRequest(server.port()));
		Thread.sleep(300); // let both reach a worker thread

		long started = System.nanoTime();
		boolean refused;
		try {
			get(url, null);
			refused = false;
		} catch (IOException closed) {
			refused = true;
		}
		long tookMs = (System.nanoTime() - started) / 1_000_000;
		assertTrue(refused, "a third connection was served although both threads were busy");
		assertTrue(tookMs < 2500, "the refusal took " + tookMs + " ms; it should be immediate");
		for (Socket s : holding) {
			s.close();
		}
	}

	@Test
	@DisplayName("a response whose reader stops reading is cut after the stall timeout")
	void stalledResponseIsCut(@TempDir Path dir) throws Exception {
		// Far bigger than the loopback socket buffers, so the server's write blocks once they fill.
		int size = 32 * 1024 * 1024;
		Files.write(dir.resolve("big.mp3"), new byte[size]);
		serve(dir, new LocalAudioServer(4, 60_000, 400));
		long received = 0;
		try (Socket s = new Socket()) {
			s.setReceiveBufferSize(4096);
			s.connect(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), server.port()));
			s.setSoTimeout(3000);
			s.getOutputStream().write(("GET /audio/big.mp3 HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
					.getBytes(StandardCharsets.US_ASCII));
			s.getOutputStream().flush();
			Thread.sleep(1500); // stop reading for well over the stall timeout
			InputStream in = s.getInputStream();
			byte[] buf = new byte[64 * 1024];
			int n;
			try {
				while ((n = in.read(buf)) != -1) {
					received += n;
				}
			} catch (IOException resetOrTimeout) {
				// a reset also ends the response
			}
		}
		assertTrue(received < size, "the whole file arrived, so the stalled response was never cut");
	}
}
