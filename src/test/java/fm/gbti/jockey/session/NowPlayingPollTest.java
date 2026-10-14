package fm.gbti.jockey.session;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** The now-playing poller's request sharing and its cap on response size. */
class NowPlayingPollTest {

	private record Jukebox(String name, String url, String bearer) {
	}

	private HttpServer server;

	@AfterEach
	void stop() {
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	@DisplayName("sessions on the same endpoint and bearer share one request")
	void oneRequestPerEndpointAndBearer() {
		String np = "https://radio.example.com/api/public/stations/2/now-playing";
		List<Jukebox> sessions = List.of(
				new Jukebox("a", np, "secret"),
				new Jukebox("b", np, "secret"),
				new Jukebox("c", np, null), // same URL, no bearer: a different request
				new Jukebox("d", "https://other.example.com/np", "secret"),
				new Jukebox("e", np, "secret"),
				new Jukebox("f", np, "rotated")); // another bearer can see another answer

		List<String> groups = new ArrayList<>();
		for (List<Jukebox> group : NowPlayingPoller.byRequest(sessions, Jukebox::url, Jukebox::bearer)) {
			groups.add(String.join("", group.stream().map(Jukebox::name).toList()));
		}
		assertEquals(List.of("abe", "c", "d", "f"), groups);
	}

	private String serve(String path, boolean endless, int size) throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext(path, ex -> {
			ex.getResponseHeaders().set("Content-Type", "application/json");
			byte[] chunk = new byte[8192];
			Arrays.fill(chunk, (byte) ' ');
			try (OutputStream out = ex.getResponseBody()) {
				if (endless) {
					ex.sendResponseHeaders(200, 0); // chunked, never ends
					while (true) {
						out.write(chunk);
						out.flush();
					}
				}
				byte[] body = ("{\"station\":{\"isLive\":true}" + " ".repeat(size) + "}")
						.getBytes(StandardCharsets.UTF_8);
				ex.sendResponseHeaders(200, body.length);
				out.write(body);
			} catch (IOException clientGone) {
				// the client stopped reading
			}
		});
		server.start();
		return "http://127.0.0.1:" + server.getAddress().getPort() + path;
	}

	private static String fetch(String url) throws Exception {
		HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build();
		return client.sendAsync(request, NowPlayingPoller.bodyHandler()).get(5, TimeUnit.SECONDS).body();
	}

	@Test
	@DisplayName("a normal now-playing body is read in full")
	void smallBodyIsRead() throws Exception {
		String url = serve("/np", false, 100);
		assertTrue(fetch(url).startsWith("{\"station\":{\"isLive\":true}"));
	}

	@Test
	@DisplayName("a body over the cap is refused without reading the rest")
	void oversizedBodyIsRefused() throws Exception {
		String url = serve("/big", false, 1024 * 1024);
		try {
			String body = fetch(url);
			fail("a 1 MiB body was accepted (" + body.length() + " chars)");
		} catch (ExecutionException refused) {
			assertTrue(refused.getCause() instanceof IOException, String.valueOf(refused.getCause()));
		}
	}

	@Test
	@DisplayName("an endless body ends at the cap instead of holding the poll")
	void endlessBodyIsCut() throws Exception {
		String url = serve("/endless", true, 0);
		try {
			fetch(url);
			fail("an endless body completed");
		} catch (ExecutionException refused) {
			assertTrue(refused.getCause() instanceof IOException, String.valueOf(refused.getCause()));
		} catch (TimeoutException stillReading) {
			fail("the poll was still reading an endless body after 5 s");
		}
	}
}
