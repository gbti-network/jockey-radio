package fm.gbti.jockey.session;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fm.gbti.jockey.JockeyRadioMod;
import fm.gbti.jockey.config.RadioConfig;
import fm.gbti.jockey.disc.DiscDefinition;
import fm.gbti.jockey.disc.DiscRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LIVE indicator plumbing. When {@link RadioConfig#nowPlayingPollSeconds} is
 * {@code 0} this does nothing at all - no thread, no log line, no network. When it
 * is positive (it defaults to {@code 15}, so the poller runs on a default install)
 * the poller runs on a single daemon thread and, every N seconds, GETs the
 * now-playing endpoint ({@code /api/public/stations/<id>/now-playing}) for each
 * active disc session with a resolvable endpoint (HLS and RTMP alike) and records a
 * {@link RadioSession.NowPlaying} snapshot (live flag, broadcast source, and the
 * current track title/artist/segment) so the boss bar and the admin disc-detail
 * dialog can show a rich LIVE line.
 *
 * <p>Set {@code nowPlayingPollSeconds == 0} to disable it. It is written to never
 * block the server thread and never spam: one log line when enabled, at most one
 * more on a 401, every per-poll error swallowed, and only a single volatile session
 * field touched. Nothing else in the mod depends on it. The shared {@link HttpClient}
 * is pinned to HTTP/1.1 - the default HTTP/2 attempts an h2c upgrade on the cleartext
 * {@code http://} endpoint that a Node/Express station rejects with "400 Invalid
 * Upgrade header", which would clear every snapshot and mask the live track.</p>
 *
 * <p>The bearer token is {@link DiscDefinition#nowPlayingToken} when set, else
 * the disc's {@link DiscDefinition#token}. It is a secret: sent only in the
 * {@code Authorization} header, never logged.</p>
 */
public final class NowPlayingPoller {

	private static final NowPlayingPoller INSTANCE = new NowPlayingPoller();
	private static final Duration TIMEOUT = Duration.ofSeconds(8);
	/**
	 * Hard backstop for one whole poll pass. Every request already carries an 8s
	 * connect + 8s request timeout and they all fire concurrently (sendAsync), so a
	 * batch settles in ~16s no matter how many hosts are down; this only guards
	 * against a future that somehow never completes.
	 */
	private static final Duration OVERALL_TIMEOUT = TIMEOUT.multipliedBy(2).plusSeconds(2);
	/**
	 * After this many consecutive UNREACHABLE polls the snapshot is cleared so a
	 * stale "LIVE - old track" can never persist while the API is down; the bar
	 * then falls back to feed-state on air/idle.
	 */
	private static final int UNREACHABLE_CLEAR_THRESHOLD = 2;
	/**
	 * After this many CONSECUTIVE reachable-but-erroring polls (5xx / 403 / any
	 * non-2xx/404/401) the snapshot is cleared. A single such response is a transient
	 * blip and the prior snapshot is kept, but a persistent run must not pin a stale
	 * "LIVE - old track" on an endpoint that stays reachable yet keeps erroring -
	 * UNREACHABLE's self-healing twin for the reachable case.
	 */
	private static final int STALE_CLEAR_THRESHOLD = 3;

	private ScheduledExecutorService scheduler;
	private volatile HttpClient client;
	/** Guards the single 401 log line per poller run (never spam). Reset on start. */
	private final AtomicBoolean unauthorizedLogged = new AtomicBoolean(false);

	public static NowPlayingPoller get() {
		return INSTANCE;
	}

	private NowPlayingPoller() {
	}

	/**
	 * Starts polling when enabled; a no-op (silent) when
	 * {@code nowPlayingPollSeconds <= 0}. Called from
	 * {@code RadioManager.onServerStarting}.
	 */
	public synchronized void start() {
		int seconds = RadioConfig.get().nowPlayingPollSeconds;
		if (seconds <= 0) {
			return; // disabled (default): no thread, no log, no network
		}
		if (scheduler != null) {
			return; // already running
		}
		ThreadFactory factory = runnable -> {
			Thread thread = new Thread(runnable, "jockey-radio-nowplaying");
			thread.setDaemon(true);
			return thread;
		};
		client = HttpClient.newBuilder()
				.connectTimeout(TIMEOUT)
				// Force HTTP/1.1. The default (HTTP/2) makes the client attempt an h2c
				// upgrade on a cleartext http:// URL - it sends an "Upgrade: h2c" +
				// "HTTP2-Settings" header pair that the SavePoint (Node/Express) server
				// rejects with "400 Invalid Upgrade header", so every poll came back 400
				// and the snapshot was cleared (bar/detail fell back to feed-state "on
				// air"/"live" and never showed the real track). curl/browsers avoid this
				// only because they default to HTTP/1.1. Verified: HTTP/1.1 -> 200 + JSON.
				.version(HttpClient.Version.HTTP_1_1)
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
		unauthorizedLogged.set(false); // a fresh run may log its first 401 again
		scheduler = Executors.newSingleThreadScheduledExecutor(factory);
		// The one log line the contract allows when enabled.
		JockeyRadioMod.LOGGER.info("[jockey] now-playing poller enabled interval={}s", seconds);
		scheduler.scheduleWithFixedDelay(this::pollAllSafely, seconds, seconds, TimeUnit.SECONDS);
	}

	/** Stops the poller and releases its threads. Called from server shutdown. */
	public synchronized void stop() {
		if (scheduler != null) {
			scheduler.shutdownNow();
			scheduler = null;
		}
		HttpClient http = client;
		client = null; // stops any in-flight pass from issuing a new send
		if (http != null) {
			// Reclaim the client's SelectorManager thread and default executor
			// promptly instead of waiting for GC (they otherwise linger past a
			// return-to-title on the integrated server). shutdownNow, not close:
			// stop() runs on the server thread at SERVER_STOPPING and must not
			// block on in-flight I/O - the scheduler thread was just interrupted.
			http.shutdownNow();
		}
	}

	/**
	 * One poll pass over every active, non-inert disc session whose disc resolves a
	 * now-playing endpoint ({@link DiscDefinition#resolveNowPlayingUrl()} - so RTMP
	 * discs with a now-playing URL are polled too, not just HLS). Wraps the whole
	 * pass so a thrown error can never suppress future scheduled runs
	 * ({@code scheduleWithFixedDelay} cancels the task on an uncaught throw), and
	 * wraps each session so one bad disc cannot skip the rest.
	 */
	private void pollAllSafely() {
		HttpClient http = client;
		if (http == null) {
			return;
		}
		try {
			// Fire every eligible session's GET concurrently (sendAsync) so a batch of
			// unreachable or slow hosts cannot serialize 8s each on this one daemon thread
			// and starve status/now-playing refresh for the sessions later in the list.
			// Responses are then drained sequentially on THIS thread, so the per-session
			// streak counters and the 401 log gate keep their single-thread invariant.
			List<Pending> pending = new ArrayList<>();
			for (RadioSession session : RadioManager.get().getSessions()) {
				if (session.state() != RadioSession.State.ACTIVE || session.isInert()) {
					continue;
				}
				DiscDefinition def = DiscRegistry.get().get(session.stationKey()).orElse(null);
				if (def == null) {
					continue; // config-station session: no disc definition, no endpoint here
				}
				String url = def.resolveNowPlayingUrl();
				if (url == null) {
					// A generic non-SavePoint stream (or an RTMP disc with no now-playing
					// URL): no endpoint to poll. Record it and make no outbound request.
					session.setNowPlayingStatus(RadioSession.NowPlayingStatus.NO_ENDPOINT);
					continue;
				}
				try {
					CompletableFuture<HttpResponse<String>> future =
							http.sendAsync(buildRequest(def, url), HttpResponse.BodyHandlers.ofString());
					pending.add(new Pending(session, def, future));
				} catch (Throwable submitFailure) {
					// Bad URI / rejected submit (e.g. client shutting down): treat as
					// unreachable and keep going - one bad disc never stops the others.
					onUnreachable(session);
				}
			}
			drain(pending);
		} catch (Throwable t) {
			// Defensive: keep the scheduled task alive no matter what.
			JockeyRadioMod.LOGGER.debug("[jockey] now-playing poll pass error", t);
		}
	}

	/**
	 * Waits on the in-flight per-session requests and processes each outcome on this
	 * thread. Bounded by {@link #OVERALL_TIMEOUT} as a hard backstop; each request
	 * carries its own 8s timeouts so the batch normally settles well before then.
	 */
	private void drain(List<Pending> pending) {
		long deadlineNanos = System.nanoTime() + OVERALL_TIMEOUT.toNanos();
		for (Pending p : pending) {
			HttpResponse<String> response;
			try {
				long remaining = deadlineNanos - System.nanoTime();
				if (remaining <= 0L) {
					p.future().cancel(true);
					onUnreachable(p.session());
					continue;
				}
				response = p.future().get(remaining, TimeUnit.NANOSECONDS);
			} catch (InterruptedException interrupted) {
				// Shutdown interrupted the poller thread: stop draining, restore the flag.
				Thread.currentThread().interrupt();
				return;
			} catch (TimeoutException timeout) {
				// The backstop fired (should not normally happen): abandon this request.
				p.future().cancel(true);
				onUnreachable(p.session());
				continue;
			} catch (Throwable networkFailure) {
				// ExecutionException wrapping connect-refused / request-timeout / IOException
				// (localhost down, wrong port, DNS fail): the host is unreachable. Never
				// spam - record the status and, after repeated failures, clear the snapshot.
				onUnreachable(p.session());
				continue;
			}
			try {
				handleResponse(p.session(), p.def(), response);
			} catch (Throwable handleFailure) {
				JockeyRadioMod.LOGGER.debug("[jockey] now-playing handle error", handleFailure);
			}
		}
	}

	/** Builds the GET, attaching the bearer (secret: header only, never logged) when present. */
	private static HttpRequest buildRequest(DiscDefinition def, String url) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
				.timeout(TIMEOUT)
				.GET();
		String bearer = def.nowPlayingToken != null ? def.nowPlayingToken : def.token;
		if (bearer != null && !bearer.isBlank()) {
			builder.header("Authorization", "Bearer " + bearer);
		}
		return builder.build();
	}

	/**
	 * Records an UNREACHABLE poll and, once {@link #UNREACHABLE_CLEAR_THRESHOLD}
	 * consecutive failures accumulate, clears the snapshot so the bar falls back to
	 * feed-state instead of pinning a stale "LIVE - old track" while the API is down.
	 */
	private static void onUnreachable(RadioSession session) {
		session.setNowPlayingStatus(RadioSession.NowPlayingStatus.UNREACHABLE);
		int streak = session.nowPlayingUnreachableStreak() + 1;
		session.setNowPlayingUnreachableStreak(streak);
		if (streak >= UNREACHABLE_CLEAR_THRESHOLD) {
			session.updateNowPlaying(null);
		}
	}

	/**
	 * A reachable-but-erroring response (5xx, 403, any non-2xx/404/401). One such
	 * response is a transient blip and the prior snapshot is kept, but a CONSECUTIVE
	 * run of them clears it after {@link #STALE_CLEAR_THRESHOLD} so a persistently
	 * erroring-yet-reachable endpoint cannot pin a stale "LIVE - old track" forever.
	 */
	private static void onStaleResponse(RadioSession session, DiscDefinition def, int status) {
		int streak = session.nowPlayingStaleStreak() + 1;
		session.setNowPlayingStaleStreak(streak);
		if (streak >= STALE_CLEAR_THRESHOLD) {
			session.updateNowPlaying(null);
		}
		JockeyRadioMod.LOGGER.debug(
				"[jockey] now-playing http status={} station={} staleStreak={}", status, def.discId, streak);
	}

	/** Processes one received HTTP response. Runs on the poller thread (single-threaded). */
	private void handleResponse(RadioSession session, DiscDefinition def, HttpResponse<String> response) {
		// We reached the host and got a response: it is not unreachable. Reset the
		// consecutive-unreachable streak regardless of status code.
		session.setNowPlayingUnreachableStreak(0);
		int status = response.statusCode();
		switch (status) {
			case 200 -> {
				session.setNowPlayingStaleStreak(0);
				parseInto(session, response.body());
				session.setNowPlayingStatus(RadioSession.NowPlayingStatus.OK);
			}
			// Now-playing disabled for this station, or the station is idle. The
			// public API returns 404 for BOTH, so we cannot tell them apart and must
			// not force "idle": clear any prior snapshot (updateNowPlaying(null)) and
			// let the dialog fall back to shared feed state. A genuinely idle station
			// then reads feed-state idle (equivalent), while a station whose feed is
			// live but whose now-playing feature is merely disabled keeps its correct
			// feed-state "live" badge instead of being stuck on a false "idle".
			case 404 -> {
				session.setNowPlayingStaleStreak(0);
				session.updateNowPlaying(null);
				session.setNowPlayingStatus(RadioSession.NowPlayingStatus.DISABLED);
			}
			// Token required and missing/invalid - including a token that worked
			// before (a 200 stored a live snapshot) and was later rotated/revoked.
			// Now-playing is unavailable, so clear any prior snapshot on EVERY 401 so
			// a stale "LIVE - <old track>" can never persist, and let the dialog drive
			// the badge from feed state. The one-time log is gated separately.
			case 401 -> {
				session.setNowPlayingStaleStreak(0);
				session.updateNowPlaying(null);
				session.setNowPlayingStatus(RadioSession.NowPlayingStatus.UNAUTHORIZED);
				if (unauthorizedLogged.compareAndSet(false, true)) {
					JockeyRadioMod.LOGGER.info("[jockey] now-playing unauthorized (401) station={}"
							+ " - now-playing unavailable, using feed-state fallback", def.discId);
				}
			}
			// Any other status (5xx, 403, ...): keep the prior snapshot for a single
			// blip, but a persistent run eventually clears it so it cannot pin forever.
			default -> onStaleResponse(session, def, status);
		}
	}

	/** One in-flight per-session request awaiting its response. */
	private record Pending(RadioSession session, DiscDefinition def,
			CompletableFuture<HttpResponse<String>> future) {
	}

	/**
	 * Parses the now-playing JSON per SavePoint's public API schema
	 * ({@code server/routes/public-api.js}): {@code station.isLive},
	 * {@code currentTrack.title}/{@code artist} (the {@code currentTrack} object is
	 * null when nothing is playing), {@code segment.name}, and top-level
	 * {@code broadcastSource} ({@code master} / {@code relay} / {@code master-key}).
	 * Tolerant: any missing or null field becomes null (or {@code false} for
	 * {@code isLive}); a malformed or non-object body leaves the prior snapshot
	 * untouched. Never throws out of the poll loop.
	 */
	private static void parseInto(RadioSession session, String body) {
		try {
			JsonElement root = JsonParser.parseString(body);
			if (!root.isJsonObject()) {
				return; // unexpected shape: keep the prior snapshot
			}
			JsonObject obj = root.getAsJsonObject();

			JsonObject station = objField(obj, "station");
			boolean live = station != null && boolField(station, "isLive");

			JsonObject track = objField(obj, "currentTrack");
			String title = track == null ? null : strField(track, "title");
			String artist = track == null ? null : strField(track, "artist");

			JsonObject segment = objField(obj, "segment");
			String segmentName = segment == null ? null : strField(segment, "name");

			String source = strField(obj, "broadcastSource");

			session.updateNowPlaying(new RadioSession.NowPlaying(live, source, title, artist, segmentName));
		} catch (RuntimeException e) {
			// Malformed/unexpected body: keep the prior snapshot, note it at debug.
			JockeyRadioMod.LOGGER.debug("[jockey] now-playing parse error", e);
		}
	}

	/** The named child object, or null when absent/null/not an object. */
	private static JsonObject objField(JsonObject obj, String name) {
		JsonElement el = obj.get(name);
		return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
	}

	/** The named boolean primitive, or false when absent/null/not a boolean. */
	private static boolean boolField(JsonObject obj, String name) {
		JsonElement el = obj.get(name);
		if (el != null && el.isJsonPrimitive()) {
			try {
				return el.getAsBoolean();
			} catch (RuntimeException ignored) {
				// present but not a boolean
			}
		}
		return false;
	}

	/** Cap on any now-playing string field. It flows to the server-thread boss bar / marquee. */
	private static final int FIELD_MAX_CHARS = 128;

	/**
	 * The named string primitive, or null when absent/null/blank, <b>sanitized</b> for the
	 * render path. The now-playing endpoint is admin-configured but only semi-trusted (a
	 * compromised/hostile station must not be able to stall the tick or inject formatting), and
	 * these values are consumed on the server thread by {@code NowPlayingHud} - both the built-in
	 * bar and the per-disc marquee template. So we strip control characters (incl. NUL, which the
	 * template collapse uses as its empty-field sentinel) and the section sign {@code §}, and cap
	 * the length to {@link #FIELD_MAX_CHARS}. This keeps a giant or separator-only title from
	 * bloating the bar or making the template's collapse pass super-linear, and prevents a NUL in
	 * a value from being mistaken for the sentinel. Mirrors the character discipline on disc names.
	 */
	private static String strField(JsonObject obj, String name) {
		JsonElement el = obj.get(name);
		if (el == null || !el.isJsonPrimitive()) {
			return null;
		}
		String value = el.getAsString();
		if (value == null || value.isBlank()) {
			return null;
		}
		StringBuilder sb = new StringBuilder(Math.min(value.length(), FIELD_MAX_CHARS));
		for (int i = 0; i < value.length() && sb.length() < FIELD_MAX_CHARS; i++) {
			char c = value.charAt(i);
			if (c >= 0x20 && c != 0x7F && c != '§') {
				sb.append(c);
			}
		}
		String cleaned = sb.toString();
		return cleaned.isBlank() ? null : cleaned;
	}
}
