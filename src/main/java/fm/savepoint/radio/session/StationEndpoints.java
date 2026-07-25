package fm.savepoint.radio.session;

import fm.savepoint.radio.config.RadioConfig;
import fm.savepoint.radio.disc.DiscDefinition;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The single seam that turns a {@link DiscDefinition} (or a raw config-station
 * URL) into the HLS trunk URL ffmpeg pulls. Keeping URL shape here means a
 * future endpoint change touches one file.
 *
 * <p>Two SavePoint shapes, chosen by {@link RadioConfig#useRawBroadcastPath}:</p>
 * <ul>
 *   <li>Direct Stream Access (supported, opt-in):
 *       {@code https://<host>/api/public/stations/<id>/stream/trunk.m3u8}</li>
 *   <li>Raw broadcast (default for now, until DSA is deployed):
 *       {@code https://<host>/radio/broadcast/<id>/trunk.m3u8}</li>
 * </ul>
 *
 * <p>An access token is appended as {@code ?token=<urlencoded>}; the playlist
 * itself rewrites segment URIs to carry it, so ffmpeg needs it only on the
 * trunk URL. {@link Endpoint#displayUrl()} is the same URL with the token
 * stripped - the only form that may be logged. {@link Endpoint#secret()} is the
 * exact encoded token for the feed's stderr redaction (null when none).</p>
 */
public final class StationEndpoints {

	/**
	 * A resolved trunk URL: {@link #effectiveUrl()} is handed to ffmpeg,
	 * {@link #displayUrl()} is safe to log, {@link #secret()} is the encoded
	 * token to redact from stderr (null when the source has no token).
	 */
	public record Endpoint(String effectiveUrl, String displayUrl, String secret) {
	}

	private StationEndpoints() {
	}

	/**
	 * Resolves the ingest endpoint for an HLS-pull disc, preferring the disc's
	 * full {@link DiscDefinition#streamUrl} when set (any ffmpeg-consumable stream
	 * URL, tokened via {@link #forUrl}), else the legacy host/stationId trunk URL
	 * built by {@link #forDisc}. This is the form every ingest call site should use.
	 */
	public static Endpoint forDiscResolved(DiscDefinition def, RadioConfig config) {
		if (def.streamUrl != null) {
			return forUrl(def.streamUrl, def.token);
		}
		return forDisc(def, config);
	}

	/** Builds the trunk URL for a legacy host/stationId HLS-pull disc. */
	public static Endpoint forDisc(DiscDefinition def, RadioConfig config) {
		String base = config.useRawBroadcastPath
				? "https://" + def.host + "/radio/broadcast/" + def.stationId + "/trunk.m3u8"
				: "https://" + def.host + "/api/public/stations/" + def.stationId + "/stream/trunk.m3u8";
		return withToken(base, def.token);
	}

	/**
	 * Wraps an already-complete config-station URL (sow-001's static stations).
	 * The URL may carry its access token inline (e.g. {@code ...?token=SECRET})
	 * with no separate {@code token} field; that inline token is split out of the
	 * display URL and folded into the redaction secret so it never reaches the
	 * INFO start log or an unredacted stderr line.
	 */
	public static Endpoint forUrl(String url, String token) {
		if (token != null && !token.isBlank()) {
			// Explicit token field: strip any inline token first so neither the
			// inline nor the appended token can appear in displayUrl.
			return withToken(stripTokenParam(url), token);
		}
		String inline = inlineTokenValue(url);
		if (inline == null) {
			return new Endpoint(url, redactDisplay(url), null);
		}
		// ffmpeg still gets the working tokened URL; displayUrl is stripped and
		// the raw inline token becomes the stderr-redaction secret.
		return new Endpoint(url, redactDisplay(stripTokenParam(url)), inline);
	}

	private static Endpoint withToken(String base, String token) {
		if (token == null || token.isBlank()) {
			return new Endpoint(base, redactDisplay(base), null);
		}
		String encoded = URLEncoder.encode(token, StandardCharsets.UTF_8);
		String separator = base.indexOf('?') >= 0 ? "&" : "?";
		String effective = base + separator + "token=" + encoded;
		return new Endpoint(effective, redactDisplay(base), encoded);
	}

	/**
	 * Sanitizes a URL for every admin-visible surface (disc-detail 'Source' row +
	 * 'Copy source URL' button, the INFO feed-start log, {@code /jukebox list}).
	 * The {@code token=} param is already dropped upstream ({@link #stripTokenParam}),
	 * but a secret can also ride in userinfo credentials ({@code user:pass@host}) or
	 * under any other param name ({@code ?auth=}, {@code ?sig=}); those never reach
	 * the display form here while ffmpeg still receives the untouched effectiveUrl.
	 * Drops authority userinfo and masks EVERY remaining query-param value to
	 * {@code ***} (param names kept so the shape stays legible).
	 */
	static String redactDisplay(String url) {
		if (url == null) {
			return null;
		}
		int q = url.indexOf('?');
		String beforeQuery = q < 0 ? url : url.substring(0, q);
		String query = q < 0 ? null : url.substring(q + 1);

		// Strip userinfo from the authority: between "://" and the '@' that ends
		// the userinfo, bounded by the authority's end ('/' or '#').
		int schemeSep = beforeQuery.indexOf("://");
		if (schemeSep >= 0) {
			int authStart = schemeSep + 3;
			int authEnd = beforeQuery.length();
			for (int i = authStart; i < beforeQuery.length(); i++) {
				char c = beforeQuery.charAt(i);
				if (c == '/' || c == '#') {
					authEnd = i;
					break;
				}
			}
			int at = beforeQuery.lastIndexOf('@', authEnd - 1);
			if (at >= authStart) {
				beforeQuery = beforeQuery.substring(0, authStart) + beforeQuery.substring(at + 1);
			}
		}

		if (query == null || query.isEmpty()) {
			return beforeQuery;
		}
		StringBuilder masked = new StringBuilder(beforeQuery).append('?');
		String[] params = query.split("&");
		for (int i = 0; i < params.length; i++) {
			if (i > 0) {
				masked.append('&');
			}
			String param = params[i];
			int eq = param.indexOf('=');
			if (eq < 0 || eq == param.length() - 1) {
				masked.append(param);
			} else {
				masked.append(param, 0, eq + 1).append("***");
			}
		}
		return masked.toString();
	}

	/** The raw value of an inline {@code token=} query param, or null when absent. */
	private static String inlineTokenValue(String url) {
		int q = url.indexOf('?');
		if (q < 0) {
			return null;
		}
		for (String param : url.substring(q + 1).split("&")) {
			if (param.startsWith("token=")) {
				String value = param.substring("token=".length());
				return value.isEmpty() ? null : value;
			}
		}
		return null;
	}

	/** The URL with any {@code token=} query param removed (other params kept). */
	private static String stripTokenParam(String url) {
		int q = url.indexOf('?');
		if (q < 0) {
			return url;
		}
		String base = url.substring(0, q);
		StringBuilder kept = new StringBuilder();
		for (String param : url.substring(q + 1).split("&")) {
			if (param.isEmpty() || param.equals("token") || param.startsWith("token=")) {
				continue;
			}
			if (kept.length() > 0) {
				kept.append('&');
			}
			kept.append(param);
		}
		return kept.length() == 0 ? base : base + "?" + kept;
	}
}
