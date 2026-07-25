package fm.gbti.jockey.config;

import fm.gbti.jockey.JockeyRadioMod;

import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Ships ffmpeg and yt-dlp INSIDE the jar and installs them on first run, so a
 * managed-host operator can drop the mod into {@code mods/} and have working audio with no
 * shell, no PATH, and no manual binary upload.
 *
 * <p><b>Why.</b> The proven deployment target (Modrinth Servers, Pterodactyl panels) gives a
 * file-upload panel and nothing else. A bare {@code ffmpeg}/{@code yt-dlp} on PATH does not
 * exist there. The {@link HostCapabilityProbe} already confirmed such hosts CAN execute a
 * binary we place on disk (verdict: {@code extracted-binary-executable=YES}); this class is
 * what places it.</p>
 *
 * <p><b>What it does.</b> On {@link RadioConfig#load()} it extracts the platform binary from
 * the jar to {@code <configDir>/jockey_radio/bin/}, sets the execute bit (uploads and
 * extractions both land mode 644 on these hosts), and points {@link RadioConfig#ffmpegBinary}
 * / {@link RadioConfig#ytdlpBinary} at the extracted files - but ONLY when the operator left
 * them at their default bare names. An explicit path in the config is always respected, and a
 * host with a real system ffmpeg is unaffected because the config still reads {@code "ffmpeg"}
 * on disk (the override is in-memory only, so the file stays portable across machines).</p>
 *
 * <p><b>Fail-soft.</b> A thin jar (dev/CI build with no bundled binary), a non-linux/amd64
 * platform, or an extraction failure all fall back to the configured value, so nothing here
 * can prevent the server starting - the startup doctor reports the real verdict either way.</p>
 *
 * <p><b>Scope.</b> linux/amd64 only, matching the single platform proven on the managed host.
 * Other platforms fall back to a configured path or PATH.</p>
 */
public final class BundledBinaries {

	private BundledBinaries() {
	}

	/**
	 * Identity of the bundled binary set. Bumped whenever the shipped binaries change, so a mod
	 * upgrade re-extracts instead of running a stale copy left by a previous version.
	 */
	static final String BUNDLE_VERSION = "ffmpeg-btbn-N125748+ytdlp-2026.07.04+deno-2.9.4+jockey-radio";

	/** Jar resource paths (absolute, from the class loader root). */
	private static final String FFMPEG_RESOURCE = "/jockey_radio/bin/linux-amd64/ffmpeg";
	private static final String YTDLP_RESOURCE = "/jockey_radio/bin/linux-amd64/yt-dlp";
	/** JavaScript runtime yt-dlp uses to solve some platforms' signature challenge (optional). */
	private static final String DENO_RESOURCE = "/jockey_radio/bin/linux-amd64/deno";

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
			if (!isSupportedPlatform(System.getProperty("os.name"), System.getProperty("os.arch"))) {
				return; // we ship only the linux/amd64 binary; anything else uses the configured path
			}
			if (JockeyRadioMod.class.getResource(FFMPEG_RESOURCE) == null) {
				return; // a thin jar (built without .product/ binaries) - nothing to extract
			}
			Path binDir = configDir.resolve("jockey_radio").resolve("bin");
			Path ffmpeg = binDir.resolve("ffmpeg");
			Path ytdlp = binDir.resolve("yt-dlp");
			Path marker = binDir.resolve(".bundle-version");

			if (!upToDate(marker, ffmpeg, ytdlp)) {
				Files.createDirectories(binDir);
				extract(FFMPEG_RESOURCE, ffmpeg);
				extract(YTDLP_RESOURCE, ytdlp);
				// deno is optional (a thin/older bundle may lack it); yt-dlp uses it for the platforms
				// that need JS. It sits in the same bin dir, which LinkResolvedFeed puts on yt-dlp's PATH.
				boolean deno = JockeyRadioMod.class.getResource(DENO_RESOURCE) != null;
				if (deno) {
					extract(DENO_RESOURCE, binDir.resolve("deno"));
				}
				// Marker written LAST, after every binary is in place, so a crash mid-extract
				// leaves it stale and the next boot re-extracts rather than trusting a partial set.
				Files.writeString(marker, BUNDLE_VERSION);
				JockeyRadioMod.LOGGER.info(
						"[jockey] installed bundled ffmpeg + yt-dlp{} to {} ({})",
						deno ? " + deno" : "", binDir, BUNDLE_VERSION);
			}

			// Only override the DEFAULT bare names - an explicit operator path always wins.
			if (FFMPEG_DEFAULT.equals(config.ffmpegBinary) && Files.isExecutable(ffmpeg)) {
				config.ffmpegBinary = ffmpeg.toAbsolutePath().toString();
			}
			if (YTDLP_DEFAULT.equals(config.ytdlpBinary) && Files.isExecutable(ytdlp)) {
				config.ytdlpBinary = ytdlp.toAbsolutePath().toString();
			}
		} catch (Throwable t) {
			// Never fatal: fall back to the configured value; the startup doctor reports the verdict.
			JockeyRadioMod.LOGGER.warn(
					"[jockey] could not install bundled binaries - using the configured ffmpeg/yt-dlp path: {}",
					t.toString());
		}
	}

	/** True only where we actually ship a binary: linux on amd64/x86_64. Pure, unit-tested. */
	static boolean isSupportedPlatform(String osName, String osArch) {
		if (osName == null || osArch == null) {
			return false;
		}
		String os = osName.toLowerCase(Locale.ROOT);
		String arch = osArch.toLowerCase(Locale.ROOT);
		return os.contains("linux") && (arch.equals("amd64") || arch.equals("x86_64"));
	}

	/** Whether the extracted set is present and matches {@link #BUNDLE_VERSION}. */
	private static boolean upToDate(Path marker, Path ffmpeg, Path ytdlp) {
		try {
			return Files.isRegularFile(ffmpeg) && Files.isRegularFile(ytdlp)
					&& Files.isRegularFile(marker)
					&& BUNDLE_VERSION.equals(Files.readString(marker).trim());
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

	/** Sets the owner execute bit, POSIX first, falling back to the portable File API. */
	private static void markExecutable(Path p) {
		try {
			Set<PosixFilePermission> perms = EnumSet.of(
					PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
					PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
					PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
					PosixFilePermission.OTHERS_EXECUTE);
			Files.setPosixFilePermissions(p, perms);
		} catch (Exception notPosix) {
			File f = p.toFile();
			f.setReadable(true, false);
			f.setExecutable(true, false);
		}
	}
}
