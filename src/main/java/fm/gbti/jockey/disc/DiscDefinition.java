package fm.gbti.jockey.disc;

import com.google.gson.annotations.SerializedName;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A single admin-authored radio disc, persisted in {@code discs.json} by
 * {@link DiscRegistry}. Plain-field, Gson-friendly (matching the codebase's
 * {@code RadioConfig.Station} style): a no-arg constructor plus public fields
 * with sensible defaults so files written by an older build load unchanged.
 *
 * <p>Two feed shapes share this one record; only the fields for the selected
 * {@link SourceType} are meaningful:</p>
 * <ul>
 *   <li>{@link SourceType#HLS_PULL} - {@link #host} + {@link #stationId} (and
 *       optional {@link #token}); the mod pulls a SavePoint HLS trunk playlist
 *       (URL built by {@code StationEndpoints}).</li>
 *   <li>{@link SourceType#RTMP_RECEIVE} - the mod runs an ffmpeg RTMP listener
 *       on {@link #rtmpBind}:{@link #rtmpPort} guarded by {@link #streamKey};
 *       a DJ/OBS/relay publishes into it.</li>
 * </ul>
 *
 * <p>Secrets ({@link #token}, {@link #streamKey}) live ONLY here on the server;
 * they never enter item NBT (the disc item carries only {@link #discId}).</p>
 */
public final class DiscDefinition {

	/** Feed backend. JSON is the lowercase form; Java keeps screaming-case. */
	public enum SourceType {
		@SerializedName("hls_pull")
		HLS_PULL,
		@SerializedName("rtmp_receive")
		RTMP_RECEIVE,
		/** sow-009: loop a finite audio file reached over HTTP (direct link or config folder). */
		@SerializedName("file")
		FILE,
		/** sow-007: a platform page URL (SoundCloud/Mixcloud/Bandcamp/Twitch/Kick) resolved via yt-dlp. */
		@SerializedName("platform")
		PLATFORM
	}

	/** discId: lowercase, {@code [a-z0-9_-]}, 1..32 chars. */
	private static final Pattern DISC_ID = Pattern.compile("[a-z0-9_-]{1,32}");
	/** {@code #RRGGBB}. */
	private static final Pattern HEX_COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");
	/** Bare authority {@code host[:port]} - no scheme, no path (those chars are absent from the class). */
	private static final Pattern HOST_AUTHORITY = Pattern.compile("[A-Za-z0-9.-]+(?::(\\d{1,5}))?");
	/** All digits. */
	private static final Pattern DIGITS = Pattern.compile("\\d+");
	/** Stream key: {@code [A-Za-z0-9_-]}, 1..64. */
	private static final Pattern STREAM_KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");
	/** Lenient bind host / IPv4 (no scheme, no path). */
	private static final Pattern BIND_HOST = Pattern.compile("[A-Za-z0-9.:_-]+");
	/** {@code namespace:path} resource location shape (used only to reject obvious garbage). */
	private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
	/** SavePoint DSA stream path: {@code .../api/public/stations/<id>/stream/trunk.m3u8}. */
	private static final Pattern DSA_STREAM_PATH = Pattern.compile(".*/api/public/stations/(\\d+)/stream/trunk\\.m3u8");
	/** SavePoint raw-broadcast path: {@code .../radio/broadcast/<id>/trunk.m3u8}. */
	private static final Pattern RAW_BROADCAST_PATH = Pattern.compile(".*/radio/broadcast/(\\d+)/trunk\\.m3u8");

	public static final String DEFAULT_BASE_DISC = "minecraft:music_disc_13";
	public static final String DEFAULT_RTMP_BIND = "127.0.0.1";
	/** New UI-created RTMP discs target remote/managed-host publishers by default. */
	public static final String NEW_RTMP_BIND = "0.0.0.0";
	public static final int DEFAULT_RADIUS_BLOCKS = 32;

	public String discId;
	public String name;
	public String nameColorHex = "#37C871";
	/**
	 * Optional display-name style (sow-010): a {@link NameStyler} style id ({@code small_caps},
	 * {@code fullwidth}, {@code bold}, {@code italic}, {@code script}, {@code monospace});
	 * {@code null} / {@code plain} = no styling. The stored {@link #name} stays PLAIN; the fancy
	 * form is produced at render time by {@link #displayName()}, so validation/length are unchanged.
	 */
	public String nameStyle;
	public String baseDisc = DEFAULT_BASE_DISC;
	public SourceType sourceType = SourceType.HLS_PULL;

	// HLS_PULL
	/**
	 * The full stream source URL ffmpeg pulls (m3u8, icecast http, ...). When set
	 * this is the STREAM source and {@link #host}/{@link #stationId} are ignored
	 * for ingest (they remain only as an optional now-playing identity + backward
	 * compat). Must be an absolute http/https URL; validated in {@link #validate}.
	 */
	public String streamUrl;
	public String host;
	public String stationId;
	public String token;
	/**
	 * Optional bearer token for the opt-in now-playing endpoint
	 * ({@code /api/public/stations/<id>/now-playing}), used only by the
	 * {@code NowPlayingPoller} when {@code nowPlayingPollSeconds > 0}. A secret:
	 * masked on every visible surface, never logged, never in item NBT. Falls
	 * back to {@link #token} when unset. Hand-set in {@code discs.json}; not
	 * exposed in the create/edit form, so a dialog edit preserves it.
	 */
	public String nowPlayingToken;
	/**
	 * Optional explicit now-playing endpoint URL (a full http/https URL, e.g.
	 * {@code http://localhost:7101/api/public/stations/2/now-playing}). When set it
	 * is used verbatim by {@link #resolveNowPlayingUrl()} and overrides any URL that
	 * would be derived from {@link #streamUrl}. This is the field that lets an
	 * {@link SourceType#RTMP_RECEIVE} disc (whose ingest carries no station identity)
	 * still show real track info: paste the station's now-playing URL here. Not a
	 * secret (the URL itself carries no token; the bearer stays in
	 * {@link #nowPlayingToken}/{@link #token}). Validated like a stream URL when set.
	 */
	public String nowPlayingUrl;
	/**
	 * Optional per-disc template for the on-air now-playing boss bar (the marquee).
	 * When set (non-blank) and a LIVE track is playing, {@code NowPlayingHud} renders
	 * this instead of the built-in {@code ♪ {station} — LIVE — {title} · {artist}}
	 * layout, substituting the placeholders {@code {station}}, {@code {title}},
	 * {@code {artist}} and {@code {segment}} (an empty field collapses its adjacent
	 * separator, and an unknown {@code {token}} is left literal). Only the live-track
	 * line is templated; idle / buffering / no-metadata keep their built-in text, and a
	 * template that renders empty also falls back to the default. Blank = the default
	 * layout. Not a secret. Validated: {@code <=128} chars, no control chars / section
	 * signs (a boss-bar title is one line).
	 */
	public String nowPlayingTemplate;

	// RTMP_RECEIVE
	public int rtmpPort;
	public String streamKey;
	public String rtmpBind = DEFAULT_RTMP_BIND;

	public int radiusBlocks = DEFAULT_RADIUS_BLOCKS;
	/**
	 * Whether a FINITE source loops (sow-012). Default {@code true} = today's behavior (loop forever),
	 * so an older {@code discs.json} without the key loads unchanged (the no-arg ctor initializer +
	 * Gson leave an absent key at {@code true}). When {@code false}, a finite source plays ONCE then
	 * stops like a vanilla disc: a FILE disc, or a NON-LIVE platform VOD (yt-dlp {@code is_live=false}).
	 * Ignored by continuous sources (live platform streams, HLS pull, RTMP receive), which always
	 * reconnect. Any boolean is valid, so {@code validate()} does not check it.
	 */
	public boolean repeat = true;

	public DiscDefinition() {
	}

	/**
	 * Fills in blank/absent optional fields with their defaults. Called after
	 * Gson load and before use so a hand-edited file missing e.g. {@code baseDisc}
	 * still works. Does not touch required fields (those are validated instead).
	 */
	public void applyDefaults() {
		if (discId != null) {
			discId = discId.trim().toLowerCase(Locale.ROOT);
		}
		if (baseDisc == null || baseDisc.isBlank()) {
			baseDisc = DEFAULT_BASE_DISC;
		}
		if (nameColorHex == null || nameColorHex.isBlank()) {
			nameColorHex = "#37C871";
		}
		if (nameStyle != null) {
			nameStyle = nameStyle.trim().toLowerCase(Locale.ROOT);
			// null = plain; an unknown/blank/plain id normalizes to null so displayName() is a no-op.
			if (nameStyle.isBlank() || nameStyle.equals("plain") || !NameStyler.isKnown(nameStyle)) {
				nameStyle = null;
			}
		}
		// sourceType is deliberately NOT defaulted here. An ABSENT key already leaves the
		// field at its initializer (HLS_PULL), which is the only case that should default.
		// A PRESENT but unparsable value ("File", "rtmp", a trailing space) deserializes to
		// null, and coercing that to HLS_PULL silently reclassified the disc: a mistyped
		// "file" lost -stream_loop/-re and its repeat setting, and a mistyped "platform"
		// bypassed the enablePlatformLinks gate entirely. Leaving it null lets validate()
		// reject the entry with an accurate message (that branch was dead until now).
		if (rtmpBind == null || rtmpBind.isBlank()) {
			rtmpBind = DEFAULT_RTMP_BIND;
		}
		if (radiusBlocks == 0) {
			radiusBlocks = DEFAULT_RADIUS_BLOCKS;
		}
		if (streamUrl != null) {
			streamUrl = streamUrl.trim();
			if (streamUrl.isBlank()) {
				streamUrl = null;
			}
		}
		if (nowPlayingUrl != null) {
			nowPlayingUrl = nowPlayingUrl.trim();
			if (nowPlayingUrl.isBlank()) {
				nowPlayingUrl = null;
			}
		}
		if (nowPlayingTemplate != null) {
			nowPlayingTemplate = nowPlayingTemplate.trim();
			if (nowPlayingTemplate.isBlank()) {
				nowPlayingTemplate = null;
			}
		}
		if (token != null && token.isBlank()) {
			token = null;
		}
		if (nowPlayingToken != null && nowPlayingToken.isBlank()) {
			nowPlayingToken = null;
		}
	}

	/**
	 * A compact fingerprint of every field that decides HOW this disc is ingested and
	 * heard. Two definitions with the same fingerprint produce an equivalent feed.
	 *
	 * <p>Used by {@code RadioManager} to tell, after a registry edit, which live sessions
	 * actually need rebuilding. Without it an edit either restarted every playing disc or
	 * - as it did before - never reached the running feed at all, so a rotated RTMP stream
	 * key kept accepting the OLD key until the server restarted.</p>
	 *
	 * <p>Excludes every field that does NOT change the ffmpeg command or feed key: the
	 * colour/base-disc/name-style presentation fields, and the now-playing URL / token /
	 * template - those last three are re-read live from the registry on every poll and HUD
	 * sweep, so editing the marquee text must NOT tear down and re-preroll the feed.</p>
	 */
	public String ingestFingerprint() {
		return String.valueOf(sourceType) + '|' + streamUrl + '|' + token + '|' + host + '|' + stationId
				+ '|' + rtmpBind + '|' + rtmpPort + '|' + streamKey + '|' + repeat + '|' + radiusBlocks;
	}

	/**
	 * The display name rendered in its {@link #nameStyle} (sow-010) - the fancy-Unicode form when a
	 * style is set, else the plain {@link #name}. This is the single place render sites (item name,
	 * boss bar, browse list, give message) should call so styling is applied consistently. Null-safe.
	 */
	public String displayName() {
		return NameStyler.apply(name, nameStyle);
	}

	/**
	 * Validates every field. Returns an empty optional when the definition is
	 * usable, otherwise a short human-readable reason (uniqueness of
	 * {@link #discId} is enforced by {@link DiscRegistry}, not here).
	 */
	public Optional<String> validate() {
		if (discId == null || !DISC_ID.matcher(discId).matches()) {
			return Optional.of("discId must match [a-z0-9_-]{1,32}");
		}
		if (name == null || name.isEmpty() || name.length() > 48) {
			return Optional.of("name must be 1..48 characters");
		}
		// The name is rendered verbatim (/jockey list, the give message) and
		// written into the item name component. Control characters (newlines etc.)
		// and the legacy section sign let a hostile name inject apparent output
		// lines or colour codes, so reject them - matching the character discipline
		// on discId/streamKey/host.
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c < 0x20 || c == 0x7F || c == '§') {
				return Optional.of("name must not contain control characters or section signs");
			}
		}
		if (nameColorHex == null || !HEX_COLOR.matcher(nameColorHex).matches()) {
			// Do not echo the client-controlled value back into the error string:
			// it is surfaced verbatim in a dialog notice, and every other client
			// value shown there is sanitized. Keeping it out avoids that vector.
			return Optional.of("nameColorHex must be #RRGGBB");
		}
		if (baseDisc != null && !baseDisc.isBlank() && !ITEM_ID.matcher(baseDisc).matches()) {
			return Optional.of("baseDisc must be a 'namespace:path' item id");
		}
		if (radiusBlocks < 1 || radiusBlocks > 512) {
			return Optional.of("radiusBlocks must be 1..512");
		}
		if (sourceType == null) {
			return Optional.of("sourceType is required (hls_pull, rtmp_receive, file, or platform)");
		}
		// The now-playing URL is valid for either source type (an RTMP disc uses it
		// to acquire a now-playing identity its ingest lacks). Validate it like a
		// stream URL whenever it is set, for both branches.
		if (nowPlayingUrl != null) {
			Optional<String> npError = validateUrlValue(nowPlayingUrl, "nowPlayingUrl");
			if (npError.isPresent()) {
				return npError;
			}
		}
		// The template is rendered verbatim into the boss-bar title (one line), so cap
		// its length and reject control chars / section signs exactly as the name is.
		if (nowPlayingTemplate != null) {
			if (nowPlayingTemplate.length() > 128) {
				return Optional.of("nowPlayingTemplate must be 128 characters or fewer");
			}
			for (int i = 0; i < nowPlayingTemplate.length(); i++) {
				char c = nowPlayingTemplate.charAt(i);
				if (c < 0x20 || c == 0x7F || c == '§') {
					return Optional.of("nowPlayingTemplate must not contain control characters or section signs");
				}
			}
		}
		return switch (sourceType) {
			case HLS_PULL -> validateHls();
			case RTMP_RECEIVE -> validateRtmp();
			case FILE -> validateFile();
			case PLATFORM -> validatePlatform();
		};
	}

	/**
	 * sow-007: a PLATFORM disc's {@link #streamUrl} is a platform PAGE url (SoundCloud /
	 * Mixcloud / Bandcamp / Twitch / Kick) that yt-dlp resolves to an audio stream.
	 * Validated as a plain http/https URL here; yt-dlp decides whether it is actually
	 * supported at play time.
	 */
	private Optional<String> validatePlatform() {
		if (streamUrl == null || streamUrl.isBlank()) {
			return Optional.of("streamUrl (a platform page URL) is required for platform");
		}
		return validateStreamUrl();
	}

	/**
	 * sow-009: a FILE disc plays a finite audio file reached over HTTP - either a direct
	 * link the admin pasted, or a config-folder file the mod serves over its internal
	 * loopback HTTP endpoint. Either way {@link #streamUrl} is the (required) http/https
	 * URL; file:// is never accepted (the feed's protocol whitelist blocks it regardless).
	 */
	private Optional<String> validateFile() {
		if (streamUrl == null || streamUrl.isBlank()) {
			return Optional.of("streamUrl (an http/https audio-file URL) is required for file");
		}
		return validateStreamUrl();
	}

	private Optional<String> validateHls() {
		// A full stream URL, when set, is the ingest source and REPLACES the legacy
		// host/stationId pair. The old requirement applies only as a fallback when
		// streamUrl is null, so pre-existing host/stationId discs still validate.
		if (streamUrl != null) {
			return validateStreamUrl();
		}
		if (host == null || host.isBlank()) {
			return Optional.of("host is required for hls_pull");
		}
		// Cap host so a stored value can never exceed the edit form's TextInput
		// maxLength (128); otherwise text() would silently truncate on prefill.
		if (host.length() > 128) {
			return Optional.of("host must be at most 128 characters");
		}
		if (host.contains("://") || host.contains("/")) {
			return Optional.of("host must be a bare host[:port], with no scheme or path");
		}
		java.util.regex.Matcher m = HOST_AUTHORITY.matcher(host);
		if (!m.matches()) {
			return Optional.of("host must be a valid host[:port]");
		}
		if (m.group(1) != null) {
			int port = Integer.parseInt(m.group(1));
			if (port < 1 || port > 65535) {
				return Optional.of("host port must be 1..65535");
			}
		}
		if (stationId == null || !DIGITS.matcher(stationId).matches()) {
			return Optional.of("stationId must be all digits");
		}
		// Cap stationId so a stored value can never exceed the edit form's
		// TextInput maxLength (32); otherwise text() would silently truncate it.
		if (stationId.length() > 32) {
			return Optional.of("stationId must be at most 32 digits");
		}
		return Optional.empty();
	}

	/**
	 * Validates a full stream URL for hls_pull: an absolute http/https URL only,
	 * with a non-blank host and length <= 512 (the edit form's TextInput maxLength).
	 * Rejects any whitespace or control character so a hostile value can never be
	 * split into extra ffmpeg arguments, and rejects every other scheme (file:,
	 * pipe:, ftp:, ...) so only network stream fetches are ever handed to ffmpeg.
	 */
	private Optional<String> validateStreamUrl() {
		return validateUrlValue(streamUrl, "streamUrl");
	}

	/**
	 * Validates a full stream/now-playing URL: an absolute http/https URL only, with
	 * a non-blank host and length <= 512 (the edit form's TextInput maxLength).
	 * Rejects any whitespace or control character so a hostile value can never be
	 * split into extra ffmpeg arguments (or an extra request line), and rejects every
	 * other scheme (file:, pipe:, ftp:, ...) so only network fetches are ever made.
	 * {@code field} names the offending field in the returned message.
	 */
	private static Optional<String> validateUrlValue(String value, String field) {
		if (value.length() > 512) {
			return Optional.of(field + " must be at most 512 characters");
		}
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c <= 0x20 || c == 0x7F) {
				return Optional.of(field + " must not contain whitespace or control characters");
			}
		}
		URI uri;
		try {
			uri = new URI(value);
		} catch (URISyntaxException e) {
			return Optional.of(field + " must be a valid absolute URL");
		}
		if (!uri.isAbsolute() || uri.getScheme() == null) {
			return Optional.of(field + " must be an absolute URL (http or https)");
		}
		String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("http") && !scheme.equals("https")) {
			return Optional.of(field + " scheme must be http or https");
		}
		if (uri.getHost() == null || uri.getHost().isBlank()) {
			return Optional.of(field + " must have a non-blank host");
		}
		return Optional.empty();
	}

	/**
	 * Keeps each carried-over secret bound to the origin it was entered for. When an
	 * edit moves the stream or the now-playing endpoint to another host, the stored
	 * token is dropped instead of being sent there; the admin enters it again if the new
	 * host needs one. Returns the names of the secrets dropped, for the save notice.
	 *
	 * @param previous the stored definition this edit replaces
	 * @param streamTokenCarried whether {@link #token} was carried over from
	 *        {@code previous} (a token typed in this edit is trusted as entered)
	 */
	public List<String> dropSecretsMovedFrom(DiscDefinition previous, boolean streamTokenCarried) {
		List<String> dropped = new ArrayList<>();
		if (streamTokenCarried && token != null
				&& !Origins.same(streamTokenTarget(), previous.streamTokenTarget())) {
			token = null;
			dropped.add("stream token");
		}
		if (nowPlayingToken != null
				&& !Origins.same(resolveNowPlayingUrl(), previous.resolveNowPlayingUrl())) {
			nowPlayingToken = null;
			dropped.add("now-playing token");
		}
		return dropped;
	}

	/**
	 * Where the stream {@link #token} is sent: the {@link #streamUrl}, else the legacy
	 * {@code https://<host>} trunk. {@code null} when neither is set. The token is only
	 * valid for this URL's {@link Origins origin}.
	 */
	public String streamTokenTarget() {
		if (streamUrl != null && !streamUrl.isBlank()) {
			return streamUrl.trim();
		}
		if (host != null && !host.isBlank()) {
			return "https://" + host.trim() + "/";
		}
		return null;
	}

	/**
	 * Resolves the now-playing endpoint for this disc, or {@code null} when it has
	 * none (a generic non-SavePoint stream). Precedence:
	 * <ol>
	 *   <li>an explicit {@link #nowPlayingUrl} (used verbatim);</li>
	 *   <li>else a URL <em>derived</em> from {@link #streamUrl} when it matches a
	 *       SavePoint shape - keep scheme + authority (host and PORT), read the
	 *       numeric station id from the path, and swap the trunk suffix for
	 *       {@code /now-playing}: {@code .../api/public/stations/<id>/stream/trunk.m3u8}
	 *       or {@code .../radio/broadcast/<id>/trunk.m3u8} →
	 *       {@code <scheme>://<authority>/api/public/stations/<id>/now-playing}
	 *       (http stays http, the :7101 dev port is preserved);</li>
	 *   <li>else the legacy {@link #host}+{@link #stationId} pair
	 *       ({@code https://<host>/api/public/stations/<stationId>/now-playing}),
	 *       for backward compatibility only;</li>
	 *   <li>else {@code null}.</li>
	 * </ol>
	 * Pure and side-effect free; safe on any thread.
	 */
	public String resolveNowPlayingUrl() {
		if (nowPlayingUrl != null && !nowPlayingUrl.isBlank()) {
			return nowPlayingUrl.trim();
		}
		if (streamUrl != null && !streamUrl.isBlank()) {
			String derived = deriveNowPlayingFromStream(streamUrl);
			if (derived != null) {
				return derived;
			}
		}
		if (host != null && !host.isBlank() && stationId != null && !stationId.isBlank()) {
			return "https://" + host + "/api/public/stations/" + stationId + "/now-playing";
		}
		return null;
	}

	/**
	 * Derives a SavePoint now-playing URL from a stream URL, or {@code null} when the
	 * stream URL is not a recognised SavePoint trunk shape. Uses {@link URI} so a
	 * trailing query (the token param) is ignored (the path is matched alone), and
	 * preserves scheme + authority verbatim so host, port and http-vs-https all carry.
	 */
	private static String deriveNowPlayingFromStream(String url) {
		URI uri;
		try {
			uri = new URI(url.trim());
		} catch (URISyntaxException e) {
			return null;
		}
		String scheme = uri.getScheme();
		String authority = uri.getRawAuthority();
		String path = uri.getPath();
		if (scheme == null || authority == null || path == null) {
			return null;
		}
		String id = null;
		Matcher dsa = DSA_STREAM_PATH.matcher(path);
		if (dsa.matches()) {
			id = dsa.group(1);
		} else {
			Matcher raw = RAW_BROADCAST_PATH.matcher(path);
			if (raw.matches()) {
				id = raw.group(1);
			}
		}
		if (id == null) {
			return null;
		}
		return scheme + "://" + authority + "/api/public/stations/" + id + "/now-playing";
	}

	private Optional<String> validateRtmp() {
		if (rtmpPort < 1024 || rtmpPort > 65535) {
			return Optional.of("rtmpPort must be 1024..65535");
		}
		// 1935 is the standard RTMP port, but on a SavePoint host the local
		// SavePoint server already owns it - a mod listener there can never bind
		// (ffmpeg loops "Address already in use", frames=0, silent). Reject it
		// with a clear message rather than letting the disc fail invisibly; use
		// 1936 (the known-free port) instead.
		if (rtmpPort == 1935) {
			return Optional.of("rtmpPort 1935 is reserved by the SavePoint server; use another port (e.g. 1936)");
		}
		if (streamKey == null || !STREAM_KEY.matcher(streamKey).matches()) {
			return Optional.of("streamKey must be [A-Za-z0-9_-]{1,64}");
		}
		if (rtmpBind == null || !BIND_HOST.matcher(rtmpBind).matches()) {
			return Optional.of("rtmpBind must be a bare host/IP");
		}
		return Optional.empty();
	}
}
