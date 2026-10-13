package fm.gbti.jockey.config;

import fm.gbti.jockey.JockeyRadioMod;

import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Ships ffmpeg and yt-dlp INSIDE the jar and installs them on first run, so an
 * operator can drop the mod into {@code mods/} and have working audio with no shell, no
 * PATH, and no manual binary upload.
 *
 * <p><b>Why.</b> The proven deployment target (Modrinth Servers, Pterodactyl panels) gives a
 * file-upload panel and nothing else. A bare {@code ffmpeg}/{@code yt-dlp} on PATH does not
 * exist there. The {@link HostCapabilityProbe} already confirmed such hosts CAN execute a
 * binary we place on disk (verdict: {@code extracted-binary-executable=YES}); this class is
 * what places it. Windows self-hosters get the same drop-in behaviour.</p>
 *
 * <p><b>What it does.</b> On {@link RadioConfig#load()} it extracts this platform's binaries
 * from the jar to {@code <configDir>/jockey_radio/bin/}, sets the execute bit (uploads and
 * extractions both land mode 644 on these hosts), and points {@link RadioConfig#ffmpegBinary}
 * / {@link RadioConfig#ytdlpBinary} at the extracted files when the operator left them at
 * their default bare names. An explicit path in the config is respected while it exists; a
 * path that no longer exists (a moved or deleted folder) falls back to the bundled copy with
 * a warning instead of leaving every disc silent. The override is in-memory only, so the
 * config file stays portable across machines.</p>
 *
 * <p><b>Fail-soft.</b> A thin jar (dev/CI build with no bundled binary), an unsupported
 * platform, or an extraction failure all fall back to the configured value, so nothing here
 * can prevent the server starting - the startup doctor reports the real verdict either way.</p>
 *
 * <p><b>Scope.</b> linux/amd64 (the managed-host target) and windows/amd64. Other platforms
 * use a configured path or PATH.</p>
 */
public final class BundledBinaries {

	private BundledBinaries() {
	}

	/**
	 * A platform we ship binaries for: its jar resource folder, executable suffix, and the
	 * identity of its bundled set. The identity is bumped whenever that platform's binaries
	 * change, so a mod upgrade re-extracts instead of running a stale copy.
	 */
	enum Platform {
		LINUX_AMD64("linux-amd64", "", "ffmpeg-btbn-n9.0.2-27+ytdlp-2026.08.19+jockey-radio"),
		WINDOWS_AMD64("windows-amd64", ".exe", "ffmpeg-btbn-n9.0.2-27+ytdlp-2026.08.19+jockey-radio");

		final String dir;
		final String exeSuffix;
		final String bundleVersion;

		Platform(String dir, String exeSuffix, String bundleVersion) {
			this.dir = dir;
			this.exeSuffix = exeSuffix;
			this.bundleVersion = bundleVersion;
		}

		/** Jar resource path (absolute, from the class loader root) of one tool. */
		String resource(String tool) {
			return "/jockey_radio/bin/" + dir + "/" + tool + exeSuffix;
		}

		/** On-disk file name of one tool. */
		String fileName(String tool) {
			return tool + exeSuffix;
		}
	}

	/** The compiled defaults in {@link RadioConfig}; an unchanged value means "not overridden". */
	static final String FFMPEG_DEFAULT = "ffmpeg";
	static final String YTDLP_DEFAULT = "yt-dlp";

	/**
	 * Extracts the bundled binaries if needed and repoints {@code config} at them. Idempotent
	 * and never throws. Called from {@link RadioConfig#load()} so every load (including
	 * {@code /jockey reload}) resolves consistently.
	 */
	public static void installAndResolve(RadioConfig config, Path configDir) {
		try {
			if (!config.useBundledBinaries) {
				return;
			}
			Platform platform = platform(System.getProperty("os.name"), System.getProperty("os.arch"));
			if (platform == null) {
				return; // no binaries for this platform; the configured path is used
			}
			if (JockeyRadioMod.class.getResource(platform.resource("ffmpeg")) == null) {
				return; // a thin jar (built without .product/ binaries) - nothing to extract
			}
			Path binDir = configDir.resolve("jockey_radio").resolve("bin");
			Path ffmpeg = binDir.resolve(platform.fileName("ffmpeg"));
			Path ytdlp = binDir.resolve(platform.fileName("yt-dlp"));
			Path marker = binDir.resolve(".bundle-version");

			if (!upToDate(platform, marker, ffmpeg, ytdlp)) {
				Files.createDirectories(binDir);
				extract(platform.resource("ffmpeg"), ffmpeg);
				extract(platform.resource("yt-dlp"), ytdlp);
				// Up to 0.1.3 the bundle also carried deno, which yt-dlp needed only for YouTube.
				// YouTube is no longer supported, so reclaim the ~100 MB an older version extracted.
				Files.deleteIfExists(binDir.resolve(platform.fileName("deno")));
				// Marker written LAST, after every binary is in place, so a crash mid-extract
				// leaves it stale and the next boot re-extracts rather than trusting a partial set.
				Files.writeString(marker, platform.bundleVersion);
				JockeyRadioMod.LOGGER.info("[jockey] installed bundled ffmpeg + yt-dlp to {} ({} {})",
						binDir, platform.dir, platform.bundleVersion);
			}

			config.ffmpegBinary = preferBundled("ffmpegBinary", config.ffmpegBinary, FFMPEG_DEFAULT, ffmpeg);
			config.ytdlpBinary = preferBundled("ytdlpBinary", config.ytdlpBinary, YTDLP_DEFAULT, ytdlp);
		} catch (Throwable t) {
			// Never fatal: fall back to the configured value; the startup doctor reports the verdict.
			JockeyRadioMod.LOGGER.warn(
					"[jockey] could not install bundled binaries; using the configured ffmpeg/yt-dlp path: {}",
					t.toString());
		}
	}

	/**
	 * The binary to use for one config key: the bundled copy when the operator left the
	 * default bare name, or when their explicit path no longer exists. Any other explicit
	 * value (an existing path, or a different bare name for PATH lookup) is respected.
	 */
	static String preferBundled(String key, String configured, String defaultName, Path bundled) {
		if (!Files.isExecutable(bundled)) {
			return configured;
		}
		String bundledPath = bundled.toAbsolutePath().toString();
		if (configured == null || configured.isBlank() || defaultName.equals(configured)) {
			return bundledPath;
		}
		if (isMissingPath(configured)) {
			JockeyRadioMod.LOGGER.warn("[jockey] configured {} '{}' does not exist; using the bundled copy at {}",
					key, configured, bundledPath);
			return bundledPath;
		}
		return configured;
	}

	/** True for a value that names a file path (not a bare PATH name) with nothing at it. */
	static boolean isMissingPath(String value) {
		if (value.indexOf('/') < 0 && value.indexOf('\\') < 0) {
			return false; // a bare name is looked up on PATH; nothing to check here
		}
		try {
			return !Files.isRegularFile(Path.of(value));
		} catch (InvalidPathException e) {
			return true;
		}
	}

	/** The platform we ship binaries for on this OS/arch, or {@code null}. Pure, unit-tested. */
	static Platform platform(String osName, String osArch) {
		if (osName == null || osArch == null) {
			return null;
		}
		String os = osName.toLowerCase(Locale.ROOT);
		String arch = osArch.toLowerCase(Locale.ROOT);
		if (!arch.equals("amd64") && !arch.equals("x86_64")) {
			return null;
		}
		if (os.contains("linux")) {
			return Platform.LINUX_AMD64;
		}
		if (os.startsWith("windows")) {
			return Platform.WINDOWS_AMD64;
		}
		return null;
	}

	/** True only where we actually ship binaries. */
	static boolean isSupportedPlatform(String osName, String osArch) {
		return platform(osName, osArch) != null;
	}

	/** Whether the extracted set is present and matches this platform's bundle identity. */
	private static boolean upToDate(Platform platform, Path marker, Path ffmpeg, Path ytdlp) {
		try {
			return Files.isRegularFile(ffmpeg) && Files.isRegularFile(ytdlp)
					&& Files.isRegularFile(marker)
					&& platform.bundleVersion.equals(Files.readString(marker).trim());
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * Copies a jar resource to {@code target} atomically (temp file + move) and sets the execute
	 * bit, so a crash mid-copy never leaves a truncated binary that later checks would trust.
	 */
	private static void extract(String resource, Path target) throws IOException {
		Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
		try (InputStream in = JockeyRadioMod.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IOException("bundled resource missing: " + resource);
			}
			Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
		}
		markExecutable(tmp);
		try {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Sets the owner execute bit, POSIX first, falling back to the portable File API (Windows). */
	private static void markExecutable(Path p) {
		try {
			// Owner-only (0700): the server process is the only thing that runs these; there is no
			// reason to grant group/other read or execute on attacker-reachable extracted binaries.
			Set<PosixFilePermission> perms = EnumSet.of(
					PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
					PosixFilePermission.OWNER_EXECUTE);
			Files.setPosixFilePermissions(p, perms);
		} catch (Exception notPosix) {
			File f = p.toFile();
			f.setReadable(true, false);
			f.setExecutable(true, false);
		}
	}
}
