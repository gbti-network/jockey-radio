package fm.savepoint.radio.config;

import fm.savepoint.radio.SavePointRadioMod;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * One-shot report of what this host actually LETS US DO — written to the server log at startup.
 *
 * <p><b>Why.</b> SavePoint Radio drives external binaries (ffmpeg always; yt-dlp for PLATFORM). On a
 * managed Minecraft host (Modrinth Servers, Aternos, BisectHosting, …) the operator has a file
 * manager but no shell, so the plan is to SHIP the binaries and extract them at runtime. That plan
 * rests on one assumption that cannot be reasoned about from here — <em>can this container execute a
 * binary we place on disk at all?</em> Some hosts mount the server directory {@code noexec} or block
 * process spawning outright, and if so the whole approach fails.</p>
 *
 * <p>This probe answers that empirically and cheaply: it writes a tiny shell script into the config
 * dir, marks it executable, runs it, and reports the outcome. It also distinguishes the two failure
 * modes that look alike in a log — <em>"process spawning is blocked"</em> vs <em>"the binary simply
 * is not installed"</em> — which is exactly the confusion that makes managed-host support guesswork.</p>
 *
 * <p>Read-only and fail-safe: every branch is caught, nothing here can prevent the server starting.
 * The probe file is deleted afterwards.</p>
 */
public final class HostCapabilityProbe {

	private HostCapabilityProbe() {
	}

	/** Probe outcome for a single capability. */
	private enum Verdict {
		YES, NO, UNKNOWN
	}

	/**
	 * Runs the probe and logs a compact report. Never throws.
	 *
	 * @param configDir the mod's config directory — the same place a bundled binary would be extracted
	 */
	public static void logReport(Path configDir) {
		try {
			String os = System.getProperty("os.name", "?");
			String arch = System.getProperty("os.arch", "?");
			String java = System.getProperty("java.version", "?");

			Verdict canSpawn = canSpawnProcess();
			Verdict canExec = Verdict.UNKNOWN;
			String execDetail = "not attempted (process spawning unavailable)";
			if (canSpawn == Verdict.YES) {
				String[] r = canExecuteExtractedBinary(configDir);
				canExec = Verdict.valueOf(r[0]);
				execDetail = r[1];
			}

			// ffmpeg uses -version; yt-dlp uses --version. Passing the wrong one exits non-zero and
			// reads as "broken" when the tool is actually fine.
			String ffmpeg = whichVersion(RadioConfig.get().ffmpegBinary, "-version");
			String ytdlp = whichVersion(RadioConfig.get().ytdlpBinary, "--version");

			SavePointRadioMod.LOGGER.info("[radio] ---- host capability probe ----");
			SavePointRadioMod.LOGGER.info("[radio] host os={} arch={} java={}", os, arch, java);
			SavePointRadioMod.LOGGER.info("[radio] host spawn-process={} extracted-binary-executable={} ({})",
					canSpawn, canExec, execDetail);
			SavePointRadioMod.LOGGER.info("[radio] host ffmpeg={}", ffmpeg);
			SavePointRadioMod.LOGGER.info("[radio] host yt-dlp={}", ytdlp);

			if (canSpawn != Verdict.YES) {
				SavePointRadioMod.LOGGER.warn("[radio] host VERDICT: this host BLOCKS process spawning - "
						+ "no audio source can work here, bundled binaries would not help.");
			} else if (canExec == Verdict.NO) {
				SavePointRadioMod.LOGGER.warn("[radio] host VERDICT: processes spawn, but a binary written "
						+ "into the server directory CANNOT be executed (noexec or equivalent). Shipping "
						+ "bundled binaries will NOT work on this host; ffmpeg must already be on PATH.");
			} else if (canExec == Verdict.YES && ffmpeg.startsWith("missing")) {
				SavePointRadioMod.LOGGER.info("[radio] host VERDICT: extracted binaries ARE executable here - "
						+ "bundling ffmpeg/yt-dlp is viable. ffmpeg is not installed, which bundling would fix.");
			} else if (canExec == Verdict.YES) {
				SavePointRadioMod.LOGGER.info("[radio] host VERDICT: extracted binaries are executable and "
						+ "ffmpeg is present - this host is fully supported.");
			}
			SavePointRadioMod.LOGGER.info("[radio] --------------------------------");
		} catch (Throwable t) {
			// A diagnostic must never take the server down.
			SavePointRadioMod.LOGGER.warn("[radio] host capability probe failed: {}", t.toString());
		}
	}

	/**
	 * Can we spawn a process at all? Distinguishes a blocked/sandboxed runtime from a merely missing
	 * binary: we deliberately invoke a name that will not exist, so an {@link IOException} naming the
	 * file means spawning WORKS, while a SecurityException/UnsupportedOperationException means it does not.
	 */
	private static Verdict canSpawnProcess() {
		try {
			Process p = new ProcessBuilder("savepoint-radio-probe-nonexistent-binary")
					.redirectErrorStream(true)
					.start();
			p.destroyForcibly();
			return Verdict.YES; // absurd, but it started - spawning definitely works
		} catch (IOException e) {
			// The expected path: "No such file or directory" == the syscall was allowed to run.
			String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
			boolean notFound = m.contains("no such file") || m.contains("cannot find")
					|| m.contains("error=2") || m.contains("not found");
			return notFound ? Verdict.YES : Verdict.NO;
		} catch (Throwable t) {
			// SecurityException, UnsupportedOperationException, container seccomp denial, ...
			return Verdict.NO;
		}
	}

	/**
	 * The decisive test: write a trivial executable into the config dir, chmod +x it, and run it.
	 * Returns {@code [verdict, detail]}. Uses a tiny script rather than a real binary so the probe
	 * stays architecture-independent — {@code noexec} rejects both identically, which is the property
	 * being measured. The script MUST match the platform: a {@code .sh} on Windows fails with
	 * "error=193, not a valid Win32 application", which would be a false NO rather than a real
	 * noexec finding.
	 */
	private static String[] canExecuteExtractedBinary(Path configDir) {
		Path probe = null;
		boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
		try {
			Files.createDirectories(configDir);
			if (windows) {
				probe = configDir.resolve("savepoint-exec-probe.cmd");
				Files.writeString(probe, "@echo off\r\necho savepoint-exec-ok\r\n", StandardCharsets.UTF_8);
			} else {
				probe = configDir.resolve(".savepoint-exec-probe.sh");
				Files.writeString(probe, "#!/bin/sh\necho savepoint-exec-ok\n", StandardCharsets.UTF_8);
			}

			// The exec bit is a POSIX concept; on Windows executability comes from the extension.
			if (!windows && !markExecutable(probe)) {
				return new String[] { "NO", "could not set the executable bit" };
			}

			Process p = new ProcessBuilder(probe.toAbsolutePath().toString())
					.redirectErrorStream(true)
					.start();
			String out;
			try (InputStream in = p.getInputStream()) {
				out = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
			}
			if (!p.waitFor(10, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				return new String[] { "UNKNOWN", "probe timed out" };
			}
			if (p.exitValue() == 0 && out.contains("savepoint-exec-ok")) {
				return new String[] { "YES", "wrote, chmod +x and ran a file in the config dir" };
			}
			return new String[] { "NO", "ran but exit=" + p.exitValue() + " out='" + out + "'" };
		} catch (IOException e) {
			String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
			if (m.contains("permission denied") || m.contains("error=13")) {
				return new String[] { "NO", "permission denied on exec - filesystem is likely mounted noexec" };
			}
			return new String[] { "NO", "IOException: " + e.getMessage() };
		} catch (Throwable t) {
			return new String[] { "NO", t.getClass().getSimpleName() + ": " + t.getMessage() };
		} finally {
			if (probe != null) {
				try {
					Files.deleteIfExists(probe);
				} catch (Exception ignored) {
					// leaving a 30-byte probe file behind is harmless
				}
			}
		}
	}

	/** Sets the exec bit, POSIX first, falling back to the portable File API (Windows). */
	private static boolean markExecutable(Path p) {
		try {
			Set<PosixFilePermission> perms = EnumSet.of(
					PosixFilePermission.OWNER_READ,
					PosixFilePermission.OWNER_WRITE,
					PosixFilePermission.OWNER_EXECUTE);
			Files.setPosixFilePermissions(p, perms);
			return true;
		} catch (UnsupportedOperationException notPosix) {
			File f = p.toFile();
			return f.setExecutable(true) || f.canExecute();
		} catch (Exception e) {
			File f = p.toFile();
			return f.setExecutable(true) || f.canExecute();
		}
	}

	/** {@code <binary> <versionFlag>} → a short version string, or why it could not be run. */
	private static String whichVersion(String binary, String versionFlag) {
		if (binary == null || binary.isBlank()) {
			return "not configured";
		}
		Process p = null;
		try {
			p = new ProcessBuilder(binary, versionFlag).redirectErrorStream(true).start();
			String out;
			try (InputStream in = p.getInputStream()) {
				out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
			if (!p.waitFor(10, TimeUnit.SECONDS)) {
				return "timed out";
			}
			String first = out.isBlank() ? "(no output)" : out.lines().findFirst().orElse("(no output)").trim();
			return p.exitValue() == 0 ? "OK - " + trim(first) : "exit=" + p.exitValue() + " " + trim(first);
		} catch (IOException e) {
			return "missing ('" + binary + "' not found on this host)";
		} catch (Throwable t) {
			return "unavailable: " + t.getClass().getSimpleName();
		} finally {
			if (p != null) {
				p.destroyForcibly();
			}
		}
	}

	private static String trim(String s) {
		return s.length() > 90 ? s.substring(0, 90) + "…" : s;
	}
}
