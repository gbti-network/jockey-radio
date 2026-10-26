package fm.gbti.jockey.session;

import fm.gbti.jockey.JockeyRadioMod;
import fm.gbti.jockey.config.RadioConfig;
import fm.gbti.jockey.disc.DiscDefinition;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Fire-and-forget reachability probe behind the {@code /jockey} "Test feed"
 * button. Runs entirely on mod-owned daemon threads - never the server thread -
 * and hands the caller a {@link Result} to deliver from a
 * {@code server.execute(...)} continuation.
 *
 * <ul>
 *   <li>{@link DiscDefinition.SourceType#HLS_PULL} - GETs the trunk URL
 *       ({@link StationEndpoints}) with an 8s timeout and classifies the status:
 *       {@code 200/206} reachable, {@code 404} station idle (not an error),
 *       {@code 401/403} token required/invalid, anything else the code; a
 *       timeout or connect error reports the message. The URL is only ever
 *       shown token-stripped ({@link StationEndpoints.Endpoint#displayUrl()}).</li>
 *   <li>{@link DiscDefinition.SourceType#RTMP_RECEIVE} - an inbound listener
 *       cannot be probed by dialing out, so it reports the bind:port and, from
 *       {@link FeedRegistry}, whether a real feed is currently receiving. The
 *       stream key is always masked to {@code ***}.</li>
 * </ul>
 *
 * <p>Every probe runs on one small, bounded, daemon executor
 * ({@link #PROBE_EXECUTOR}) and shares a single {@link HttpClient}
 * ({@link ClientHolder}), so a burst of Test clicks cannot spawn an unbounded
 * pile of threads or leak a selector/executor per probe.</p>
 */
public final class FeedTester {

	private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(8);

	/**
	 * Cap on concurrent probes. The pool has core 0 with a keep-alive (no thread
	 * lives while idle) and a {@link SynchronousQueue} + abort policy, so work
	 * past the cap is rejected rather than piling up an unbounded backlog - a fast
	 * clicker (or a forged {@code jockey_radio:test} packet loop) can never
	 * spin up more than this many probe threads.
	 */
	private static final int MAX_CONCURRENT_PROBES = 2;

	private static final ExecutorService PROBE_EXECUTOR = new ThreadPoolExecutor(
			0, MAX_CONCURRENT_PROBES, 30L, TimeUnit.SECONDS,
			new SynchronousQueue<>(),
			new ProbeThreadFactory(),
			new ThreadPoolExecutor.AbortPolicy());

	private static final class ProbeThreadFactory implements ThreadFactory {
		private final AtomicInteger index = new AtomicInteger();

		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, "jockey-radio-test-" + index.getAndIncrement());
			thread.setDaemon(true);
			return thread;
		}
	}

	/**
	 * One shared {@link HttpClient} for every HLS probe, created lazily on first
	 * use (the holder idiom) so its SelectorManager thread only spins up when the
	 * Test button is actually clicked. Reused for the JVM's lifetime rather than
	 * built and discarded per probe (each discarded client would leave a selector
	 * thread and executor lingering until GC).
	 */
	private static final class ClientHolder {
		static final HttpClient CLIENT = HttpClient.newBuilder()
				.connectTimeout(PROBE_TIMEOUT)
				// Force HTTP/1.1: the default (HTTP/2) attempts an h2c upgrade on a
				// cleartext http:// probe, which the SavePoint (Node/Express) server
				// rejects with "400 Invalid Upgrade header" - the same bug that made the
				// now-playing poller mis-read every station as unreachable. HTTP/1.1
				// matches how ffmpeg/curl fetch the stream, so a Test reflects reality.
				.version(HttpClient.Version.HTTP_1_1)
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	/** A completed probe: a notice {@code title} and {@code message}, both safe to show. */
	public record Result(String title, String message) {
	}

	private FeedTester() {
	}

	/**
	 * Runs the probe for {@code def} on the shared daemon probe pool and invokes
	 * {@code onResult} with the outcome from a pool thread. The caller is
	 * responsible for hopping the callback back onto the server thread before
	 * touching game state. When the pool is saturated ({@link
	 * #MAX_CONCURRENT_PROBES} probes already in flight) the probe is refused and
	 * {@code onResult} is invoked inline with a "busy, retry" notice - the probe
	 * is never run on the calling thread, which would block the server thread on
	 * network I/O.
	 */
	public static void probeAsync(DiscDefinition def, Consumer<Result> onResult) {
		Runnable task = () -> {
			Result result;
			try {
				result = probe(def);
			} catch (Throwable t) {
				// A probe must never crash the pool thread or leak internals into
				// the dialog; report a generic failure and log the detail server-side.
				JockeyRadioMod.LOGGER.warn("[jockey] feed test failed disc={}", def.discId, t);
				result = new Result("Test failed", "The probe hit an unexpected error. See the server log.");
			}
			onResult.accept(result);
		};
		try {
			PROBE_EXECUTOR.execute(task);
		} catch (RejectedExecutionException rejected) {
			onResult.accept(new Result("Test busy",
					"Too many feed tests are running at once. Try again in a moment."));
		}
	}

	private static Result probe(DiscDefinition def) {
		return switch (def.sourceType) {
			case HLS_PULL -> probeHls(def);
			case RTMP_RECEIVE -> probeRtmp(def);
			// FILE resolves to an http/https URL (direct link or the internal file server),
			// so the same ranged-GET reachability probe applies.
			case FILE -> probeHls(def);
			// PLATFORM's streamUrl is a page URL; a GET checks the page is reachable (yt-dlp
			// does the real resolution at play time).
			case PLATFORM -> probeHls(def);
		};
	}

	private static Result probeHls(DiscDefinition def) {
		StationEndpoints.Endpoint endpoint = StationEndpoints.forDiscResolved(def, RadioConfig.get());
		// displayUrl has the token stripped - the ONLY form that may be shown.
		String shown = endpoint.displayUrl();
		HttpClient client = ClientHolder.CLIENT;
		try {
			// A ranged GET avoids pulling a whole playlist while still exercising
			// auth/routing exactly as ffmpeg's fetch would; the body is discarded.
			HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint.effectiveUrl()))
					.timeout(PROBE_TIMEOUT)
					.header("Range", "bytes=0-0")
					.GET()
					.build();
			HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
			int code = response.statusCode();
			return switch (code) {
				case 200, 206 -> new Result("Feed reachable",
						"HTTP " + code + ": the trunk URL is reachable.\n" + shown);
				case 404 -> new Result("Station idle",
						"HTTP 404: the station is idle (not an error; an idle SavePoint station "
								+ "answers 404 until it goes live).\n" + shown);
				case 401 -> new Result("Token required",
						"HTTP 401: an access token is required or the configured one is invalid.\n" + shown);
				case 403 -> new Result("Access denied",
						"HTTP 403: access denied (token invalid or not permitted).\n" + shown);
				default -> new Result("Unexpected response",
						"HTTP " + code + " from the trunk URL.\n" + shown);
			};
		} catch (java.net.http.HttpTimeoutException e) {
			return new Result("Timed out",
					"No response within " + PROBE_TIMEOUT.toSeconds() + "s.\n" + shown);
		} catch (java.io.IOException e) {
			return new Result("Unreachable",
					"Could not reach the trunk URL: " + safeMessage(e, endpoint.secret()) + "\n" + shown);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Result("Interrupted", "The probe was interrupted.\n" + shown);
		}
	}

	private static Result probeRtmp(DiscDefinition def) {
		// The stream key is a secret and is NEVER shown - the publish URL is
		// printed with the key masked to ***.
		String publishUrl = "rtmp://" + def.rtmpBind + ":" + def.rtmpPort + "/live/***";
		String key = "disc:" + def.discId;
		FeedRegistry registry = FeedRegistry.get();
		long frames = registry.feedFramesRead(key);
		if (frames < 0) {
			return new Result("Listener not running",
					"An RTMP listener cannot be probed by connecting out. It starts when this disc "
							+ "is inserted into a jukebox; then push to:\n" + publishUrl
							+ "\n(replace *** with the stream key)");
		}
		if (!registry.feedIsStreaming(key)) {
			// A feed is registered but it is a stub (ffmpeg is not runnable, or
			// ingest=stub). framesRead() is 0 for a stub exactly as for a bound-idle
			// listener, so reporting "waiting" here would falsely claim the port is
			// bound - nothing is actually listening.
			return new Result("Listener unavailable",
					"This disc is inserted, but audio ingest is disabled (ffmpeg is not runnable or "
							+ "ingest=stub), so no RTMP listener is bound on " + publishUrl + ". Sessions "
							+ "still track; see the server log for the ffmpeg startup error.");
		}
		if (frames == 0) {
			return new Result("Listener waiting",
					"Listener bound on " + publishUrl + " and waiting for a publisher. "
							+ "No audio received yet.\nPush to that URL (replace *** with the stream key).");
		}
		return new Result("Receiving audio",
				"Listener on " + publishUrl + " is currently receiving audio (frames > 0).");
	}

	/**
	 * A short, non-leaky exception message for a dialog line. The probe request
	 * targets {@link StationEndpoints.Endpoint#effectiveUrl()}, which carries the
	 * token; while standard {@code java.net.http} IOExceptions do not embed the
	 * request URI, {@code secret} (the encoded token, if any) is redacted first as
	 * defense-in-depth against a future exception type whose message includes it -
	 * matching the token-stripping discipline applied to {@code displayUrl}.
	 * Redaction runs before truncation so a boundary cut cannot leave a fragment.
	 */
	private static String safeMessage(Exception e, String secret) {
		String message = e.getMessage();
		if (message == null || message.isBlank()) {
			return e.getClass().getSimpleName();
		}
		if (secret != null && !secret.isBlank()) {
			message = message.replace(secret, "***");
		}
		return message.length() > 120 ? message.substring(0, 120) : message;
	}
}
