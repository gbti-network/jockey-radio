package fm.gbti.jockey.disc;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/**
 * Which platform links Jockey Radio plays. YouTube is not supported: a YouTube link is
 * refused when a disc is saved and again when one plays, and yt-dlp runs with every
 * YouTube extractor switched off, so a short link, an embed or a page that embeds a
 * YouTube video cannot reach it either.
 */
public final class PlatformPolicy {

	private PlatformPolicy() {
	}

	/** Refused domains; their subdomains (www., m., music.) are refused too. */
	private static final List<String> REFUSED_DOMAINS =
			List.of("youtube.com", "youtu.be", "youtube-nocookie.com", "youtubekids.com");

	/**
	 * yt-dlp {@code --use-extractors} value: its default set minus every YouTube extractor
	 * (21 in 2026.08.19, including {@code web.archive:youtube} and {@code YoutubeYtBe}).
	 */
	public static final String YTDLP_EXTRACTORS = "default,-.*youtube.*";

	/** Shown to admins wherever a YouTube link is refused. */
	public static final String REFUSED_MESSAGE = "YouTube links are not supported by Jockey Radio.";

	/** True for a link to a refused platform. */
	public static boolean isRefused(String url) {
		String host = host(url);
		if (host == null) {
			return false;
		}
		for (String domain : REFUSED_DOMAINS) {
			if (host.equals(domain) || host.endsWith("." + domain)) {
				return true;
			}
		}
		return false;
	}

	/** The lower-case host, reading a link pasted without a scheme as https. */
	private static String host(String url) {
		if (url == null || url.isBlank()) {
			return null;
		}
		String link = url.trim();
		if (!link.contains("://")) {
			link = "https://" + link;
		}
		try {
			String host = new URI(link).getHost();
			if (host == null) {
				return null;
			}
			host = host.toLowerCase(Locale.ROOT);
			// "youtube.com." is the same host as "youtube.com".
			return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
		} catch (URISyntaxException e) {
			return null;
		}
	}
}
