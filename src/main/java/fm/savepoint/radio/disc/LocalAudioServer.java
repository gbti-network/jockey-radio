package fm.savepoint.radio.disc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fm.savepoint.radio.SavePointRadioMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * sow-009 (P3): a tiny loopback-only, read-only HTTP server that exposes the audio
 * files in {@code <configDir>/savepoint_radio/audio/} so a {@code FILE} disc can loop
 * one over HTTP - WITHOUT ever whitelisting {@code file:} in ffmpeg (the
 * {@link fm.savepoint.radio.session.HlsPullFeed}/{@code FileLoopFeed} guardrail: a stream
 * must never turn ffmpeg into a local-file reader). The admin picks a file in
 * {@code /savepoint}; the disc's {@code streamUrl} becomes {@link #urlFor}.
 *
 * <p>Hardening: bound to {@code 127.0.0.1} only; serves GET (+ HEAD) exclusively; every
 * request path is decoded, stripped to a bare file name, and re-resolved strictly INSIDE
 * the audio root (any {@code ..} / absolute / subdir escape is rejected); only known audio
 * extensions are served. Following the sow-005 networking lesson it advertises {@code 127.0.0.1},
 * never {@code localhost} (IPv4-vs-IPv6). Fully fail-safe: any startup problem logs and
 * leaves the feature disabled ({@code port()==0}); the mod runs unaffected.
 */
public final class LocalAudioServer {

	private static final LocalAudioServer INSTANCE = new LocalAudioServer();

	private static final String DIR_NAME = "savepoint_radio";
	private static final String AUDIO_DIR = "audio";
	private static final Set<String> AUDIO_EXTS =
			Set.of("mp3", "ogg", "oga", "opus", "wav", "flac", "m4a", "aac", "mp4");

	private volatile HttpServer server;
	private volatile int port;
	private volatile Path audioRoot;

	private LocalAudioServer() {
	}

	public static LocalAudioServer get() {
		return INSTANCE;
	}

	/** The audio folder ({@code <configDir>/savepoint_radio/audio}). */
	public static Path audioDir() {
		return FabricLoader.getInstance().getConfigDir().resolve(DIR_NAME).resolve(AUDIO_DIR);
	}

	/** Bound port, or {@code 0} when the server is not running. */
	public int port() {
		return port;
	}

	/**
	 * Starts the loopback server on {@code configuredPort} ({@code <=0} disables it).
	 * Creates the audio folder if missing so admins have somewhere to drop files.
	 * Never throws.
	 */
	public synchronized void start(int configuredPort) {
		stop();
		if (configuredPort <= 0) {
			return;
		}
		try {
			Path dir = audioDir();
			Files.createDirectories(dir);
			this.audioRoot = dir.toRealPath();
			HttpServer s = HttpServer.create(
					new InetSocketAddress(InetAddress.getLoopbackAddress(), configuredPort), 0);
			s.createContext("/audio/", this::handle);
			// A cached pool, NOT a fixed one. Every request here is a long-lived streaming
			// response: FileLoopFeed passes ffmpeg -re, so the handler blocks inside write()
			// at the media clock for as long as the disc plays. A fixed pool of N therefore
			// caps CONCURRENT FILE DISCS at N, and the overflow does not fail - it sits in the
			// executor's unbounded queue, so ffmpeg waits forever for response headers, the
			// ingest loop never reconnects, and the disc is silent with nothing logged.
			// One thread per playing file disc is the correct sizing, and the pool reaps
			// idle threads after 60 s.
			s.setExecutor(Executors.newCachedThreadPool(r -> {
				Thread t = new Thread(r, "savepoint-audio-http");
				t.setDaemon(true);
				return t;
			}));
			s.start();
			this.server = s;
			this.port = s.getAddress().getPort();
			SavePointRadioMod.LOGGER.info("[radio] local audio server on 127.0.0.1:{} serving {} ({} file(s))",
					port, audioRoot, listAudioFiles().size());
		} catch (Throwable t) {
			this.server = null;
			this.port = 0;
			SavePointRadioMod.LOGGER.warn("[radio] local audio server failed to start; file-folder discs disabled", t);
		}
	}

	/** Stops the server if running. Idempotent, never throws. */
	public synchronized void stop() {
		HttpServer s = server;
		server = null;
		port = 0;
		if (s != null) {
			try {
				s.stop(0);
			} catch (Throwable ignored) {
				// best-effort
			}
		}
	}

	/** Sorted names of the audio files in the folder (empty on any error / when off). */
	public List<String> listAudioFiles() {
		Path root = audioRoot;
		if (root == null || !Files.isDirectory(root)) {
			return List.of();
		}
		try (var stream = Files.list(root)) {
			return stream
					// NOFOLLOW so a symlink is not listed: resolve()'s toRealPath child-of-root
					// check rejects one, and listing it would recreate the exact silent-404 the
					// shared name predicate below closes for ordinary files.
					.filter(p -> Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS))
					.map(p -> p.getFileName().toString())
					// Same predicate resolve() uses, so the Browse dialog can never offer a file
					// the server would then refuse (a disc saved from such an entry 404s on every
					// play, silently, with nothing in the UI or the log to explain it).
					.filter(LocalAudioServer::isServableName)
					.sorted(String.CASE_INSENSITIVE_ORDER)
					.toList();
		} catch (Throwable t) {
			return List.of();
		}
	}

	/** The internal http URL for a folder file, or {@code null} if the server is off. */
	public String urlFor(String fileName) {
		int p = port;
		if (p <= 0 || fileName == null) {
			return null;
		}
		String enc = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
		return "http://127.0.0.1:" + p + "/audio/" + enc;
	}

	private static boolean hasAudioExt(String name) {
		int dot = name.lastIndexOf('.');
		return dot > 0 && AUDIO_EXTS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
	}

	private void handle(HttpExchange ex) throws IOException {
		try {
			String method = ex.getRequestMethod();
			boolean head = "HEAD".equalsIgnoreCase(method);
			if (!head && !"GET".equalsIgnoreCase(method)) {
				ex.sendResponseHeaders(405, -1);
				return;
			}
			Path file = resolve(ex.getRequestURI().getRawPath());
			if (file == null) {
				ex.sendResponseHeaders(404, -1);
				return;
			}
			long len = Files.size(file);
			ex.getResponseHeaders().set("Content-Type", contentType(file));
			ex.getResponseHeaders().set("Accept-Ranges", "bytes");

			// Minimal single-range support (ffmpeg may probe with Range: bytes=0-).
			String range = ex.getRequestHeaders().getFirst("Range");
			long start = 0;
			long end = len - 1;
			boolean partial = false;
			if (range != null && range.startsWith("bytes=")) {
				try {
					String spec = range.substring(6);
					int dash = spec.indexOf('-');
					String a = spec.substring(0, dash);
					String b = spec.substring(dash + 1);
					start = a.isEmpty() ? 0 : Long.parseLong(a);
					end = b.isEmpty() ? len - 1 : Math.min(Long.parseLong(b), len - 1);
					if (start > end || start >= len) {
						ex.getResponseHeaders().set("Content-Range", "bytes */" + len);
						ex.sendResponseHeaders(416, -1);
						return;
					}
					partial = true;
				} catch (RuntimeException malformed) {
					start = 0;
					end = len - 1;
					partial = false;
				}
			}
			long count = end - start + 1;
			if (partial) {
				ex.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + len);
			}
			ex.sendResponseHeaders(partial ? 206 : 200, head ? -1 : count);
			if (head) {
				return;
			}
			try (var in = Files.newInputStream(file); OutputStream out = ex.getResponseBody()) {
				in.skipNBytes(start);
				byte[] buf = new byte[64 * 1024];
				long remaining = count;
				int n;
				while (remaining > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, remaining))) != -1) {
					out.write(buf, 0, n);
					remaining -= n;
				}
			}
		} catch (Throwable t) {
			try {
				ex.sendResponseHeaders(500, -1);
			} catch (IOException ignored) {
				// connection already gone
			}
		} finally {
			ex.close();
		}
	}

	/**
	 * Resolves a request path to a real audio file strictly inside the audio root, or
	 * {@code null} if it escapes, is missing, or is not an allowed audio type. Uses only
	 * the decoded BASENAME (no subdirectories), then verifies the real path is a direct
	 * child of the root - so {@code ..}, absolute paths, and symlink escapes are refused.
	 */
	private Path resolve(String rawPath) {
		Path root = audioRoot;
		if (root == null) {
			return null;
		}
		String decoded = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
		String prefix = "/audio/";
		if (!decoded.startsWith(prefix)) {
			return null;
		}
		String name = decoded.substring(prefix.length());
		if (!isServableName(name)) {
			return null;
		}
		try {
			Path candidate = root.resolve(name).toRealPath();
			if (!candidate.getParent().equals(root) || !Files.isRegularFile(candidate)) {
				return null;
			}
			return candidate;
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * Whether {@code name} is a bare, allowed audio file name this server will serve. The
	 * single predicate behind BOTH {@link #listAudioFiles()} and {@link #resolve(String)},
	 * so the Browse dialog and the server can never disagree about what exists.
	 *
	 * <p>Traversal is refused by the separator check plus {@link #resolve}'s post-
	 * {@code toRealPath} check that the file is a direct child of the root. A {@code ..}
	 * SUBSTRING is deliberately not refused: it cannot escape anything without a separator,
	 * and refusing it only made ordinary names like {@code set1..mp3} unplayable.</p>
	 */
	private static boolean isServableName(String name) {
		return name != null && !name.isEmpty()
				&& !name.contains("/") && !name.contains("\\")
				&& hasAudioExt(name);
	}

	private static String contentType(Path file) {
		String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
		if (n.endsWith(".mp3")) {
			return "audio/mpeg";
		}
		if (n.endsWith(".ogg") || n.endsWith(".oga") || n.endsWith(".opus")) {
			return "audio/ogg";
		}
		if (n.endsWith(".wav")) {
			return "audio/wav";
		}
		if (n.endsWith(".flac")) {
			return "audio/flac";
		}
		if (n.endsWith(".m4a") || n.endsWith(".aac") || n.endsWith(".mp4")) {
			return "audio/mp4";
		}
		return "application/octet-stream";
	}
}
