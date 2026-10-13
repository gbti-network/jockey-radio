package fm.gbti.jockey.disc;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

/**
 * Web origins (scheme, host, port) for binding a stored secret to the endpoint it was
 * entered for. A token for one host must never be sent to another: not after an edit
 * moves the URL, and not when the now-playing endpoint lives somewhere else.
 */
public final class Origins {

	private Origins() {
	}

	/**
	 * {@code scheme://host:port}, lower-cased with the default port made explicit, or
	 * {@code null} for a blank or unparseable URL or one without a host.
	 */
	public static String of(String url) {
		if (url == null || url.isBlank()) {
			return null;
		}
		URI uri;
		try {
			uri = new URI(url.trim());
		} catch (URISyntaxException e) {
			return null;
		}
		String scheme = uri.getScheme();
		String host = uri.getHost();
		if (scheme == null || host == null) {
			return null;
		}
		scheme = scheme.toLowerCase(Locale.ROOT);
		int port = uri.getPort();
		if (port < 0) {
			port = switch (scheme) {
				case "http" -> 80;
				case "https" -> 443;
				case "rtmp" -> 1935;
				default -> -1;
			};
		}
		return scheme + "://" + host.toLowerCase(Locale.ROOT) + ":" + port;
	}

	/** True when both URLs have the same origin. Two URLs without an origin also match. */
	public static boolean same(String a, String b) {
		return Objects.equals(of(a), of(b));
	}
}
