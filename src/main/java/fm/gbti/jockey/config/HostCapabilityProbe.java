package fm.gbti.jockey.config;

import fm.gbti.jockey.JockeyRadioMod;

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
 * <p><b>Why.</b> Jockey Radio drives external binaries (ffmpeg always; yt-dlp for PLATFORM). On a
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

			// Repair the exec bit BEFORE probing. A binary uploaded through a panel arrives
			// mode 644, and an EACCES spawn is otherwise reported as "not found" - sending the
			// operator off to reinstall a binary that is sitting right there. This is also
			// what the startup doctor does later, so the boot log no longer contradicts itself.
			ensureExecutable(RadioConfig.get().ffmpegBinary);
			ensureExecutable(RadioConfig.get().ytdlpBinary);

			// ffmpeg uses -version; yt-dlp uses --version. Passing the wrong one exits non-zero and
			// reads as "broken" when the tool is actually fine.
			Tool ffmpeg = whichVersion(RadioConfig.get().ffmpegBinary, "-version");
			Tool ytdlp = whichVersion(RadioConfig.get().ytdlpBinary, "--version");

			JockeyRadioMod.LOGGER.info("[jockey] ---- host capability probe ----");
			JockeyRadioMod.LOGGER.info("[jockey] host os={} arch={} java={}", os, arch, java);
			JockeyRadioMod.LOGGER.info("[jockey] host spawn-process={} extracted-binary-executable={} ({})",
					canSpawn, canExec, execDetail);
			JockeyRadioMod.LOGGER.info("[jockey] host ffmpeg={}", ffmpeg.detail());
			JockeyRadioMod.LOGGER.info("[jockey] host yt-dlp={}", ytdlp.detail());

			// Branch on the SUCCESS marker, never on "is it the missing string". The old chain
			// tested ffmpeg.startsWith("missing"), so "exit=126 ...", "timed out", "unavailable"
			// and "not configured" all fell through to "fully supported" while the startup
			// doctor logged the opposite and the mod quietly ran on stub feeds.
			if (canSpawn != Verdict.YES) {
				JockeyRadioMod.LOGGER.warn("[jockey] host VERDICT: this host BLOCKS process spawning; "
						+ "no audio source can work here, bundled binaries would not help.");
			} else if (canExec == Verdict.NO) {
				JockeyRadioMod.LOGGER.warn("[jockey] host VERDICT: processes spawn, but a binary written "
						+ "into the server directory CANNOT be executed (noexec or equivalent). Shipping "
						+ "bundled binaries will NOT work on this host; ffmpeg must already be on PATH.");
			} else if (canExec == Verdict.UNKNOWN) {
				JockeyRadioMod.LOGGER.warn("[jockey] host VERDICT: INCONCLUSIVE; the exec probe did not "
						+ "finish ({}), so whether a bundled binary can run here is unknown.", execDetail);
			} else if (ffmpeg.ok()) {
				JockeyRadioMod.LOGGER.info("[jockey] host VERDICT: extracted binaries are executable and "
						+ "ffmpeg is present; this host is fully supported.");
			} else if (ffmpeg.missing()) {
				JockeyRadioMod.LOGGER.info("[jockey] host VERDICT: extracted binaries ARE executable here; "
						+ "bundling ffmpeg/yt-dlp is viable. ffmpeg is not installed, which bundling would fix.");
			} else {
				JockeyRadioMod.LOGGER.warn("[jockey] host VERDICT: extracted binaries are executable, but "
						+ "ffmpeg did not answer -version ({}); feeds will fall back to stubs until this is "
						+ "resolved.", ffmpeg.detail());
			}
			JockeyRadioMod.LOGGER.info("[jockey] --------------------------------");
		} catch (Throwable t) {
			// A diagnostic must never take the server down.
			JockeyRadioMod.LOGGER.warn("[jockey] host capability probe failed: {}", t.toString());
		}
	}

	/**
	 * Can we spawn a process at all? Distinguishes a blocked/sandboxed runtime from a merely missing
	 * binary: we deliberately invoke a name that will not exist, so an {@link IOException} naming the
	 * file means spawning WORKS, while a SecurityException/UnsupportedOperationException means it does not.
	 */
	private static Verdict canSpawnProcess() {
		try {
			Process p = new ProcessBuilder("jockey-radio-probe-nonexistent-binary")
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
			// waitFor before reading, for the same reason as whichVersion: draining the pipe
			// first put the 10 s timeout out of reach, and this runs on the bootstrap thread
			// during onInitialize, so a child holding stdout open would hang server startup.
			if (!p.waitFor(10, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				return new String[] { "UNKNOWN", "probe timed out" };
			}
			String out;
			try (InputStream in = p.getInputStream()) {
				out = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
			}
			if (p.exitValue() == 0 && out.contains("savepoint-exec-ok")) {
				return new String[] { "YES", "wrote, chmod +x and ran a file in the config dir" };
			}
			return new String[] { "NO", "ran but exit=" + p.exitValue() + " out='" + out + "'" };
		} catch (IOException e) {
			String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
			if (m.contains("permission denied") || m.contains("error=13")) {
				return new String[] { "NO", "permission denied on exec; filesystem is likely mounted noexec" };
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

	/**
	 * One tool's probe outcome. {@code ok} is the ONLY success signal; {@code missing} means
	 * the file genuinely is not there (as opposed to present-but-unrunnable), which is the
	 * distinction the bundling verdict turns on.
	 */
	private record Tool(boolean ok, boolean missing, String detail) {
	}

	/** {@code <binary> <versionFlag>} → the outcome, with a short version string or the reason. */
	private static Tool whichVersion(String binary, String versionFlag) {
		if (binary == null || binary.isBlank()) {
			return new Tool(false, false, "not configured");
		}
		Process p = null;
		try {
			p = new ProcessBuilder(binary, versionFlag).redirectErrorStream(true).start();
			// waitFor FIRST, then read. Reading to EOF first meant the 10 s timeout could never
			// fire while the pipe was open, so a wrapper script that backgrounds a helper (the
			// grandchild inherits the pipe) blocked mod init forever. A -version banner is a few
			// KB - far inside the 64 KB pipe buffer - so waiting first cannot deadlock.
			if (!p.waitFor(10, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				return new Tool(false, false, "timed out after 10s");
			}
			String out;
			try (InputStream in = p.getInputStream()) {
				out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
			String first = out.isBlank() ? "(no output)" : out.lines().findFirst().orElse("(no output)").trim();
			return p.exitValue() == 0
					? new Tool(true, false, "OK (" + trim(first) + ")")
					: new Tool(false, false, "exit=" + p.exitValue() + " " + trim(first));
		} catch (IOException e) {
			// EACCES is NOT "missing": the file is right there, it just has no exec bit. Saying
			// "not found" here is what sent operators off to re-upload a binary that was fine.
			String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
			if (m.contains("permission denied") || m.contains("error=13")) {
				return new Tool(false, false, "present but NOT EXECUTABLE ('" + binary
						+ "' is mode 644?); the mod tried to set the execute bit and could not");
			}
			return new Tool(false, true, "missing ('" + binary + "' not found on this host)");
		} catch (Throwable t) {
			return new Tool(false, false, "unavailable: " + t.getClass().getSimpleName());
		} finally {
			if (p != null) {
				p.destroyForcibly();
			}
		}
	}

	/**
	 * If {@code binary} names an existing file that is not executable, try to make it so.
	 *
	 * <p>Managed Minecraft hosts give the operator a file manager but no shell, and uploads
	 * arrive non-executable with no way to chmod them from the panel. Without this, "you
	 * uploaded ffmpeg but it has mode 644" is indistinguishable from "ffmpeg is not
	 * installed" - both surface as {@code error=13, Permission denied}. Deliberately narrow:
	 * a bare command name resolved from PATH is left alone (nothing to chmod, and we must
	 * not touch a system binary), and every failure is non-fatal because the caller's
	 * {@code -version} probe reports the real verdict either way.</p>
	 */
	public static void ensureExecutable(String binary) {
		if (binary == null || binary.isBlank()) {
			return;
		}
		try {
			Path path = Path.of(binary);
			// A bare name like "ffmpeg" is a PATH lookup, not a file we own - leave it alone.
			if (!path.isAbsolute() && path.getNameCount() == 1) {
				return;
			}
			if (!Files.isRegularFile(path) || Files.isExecutable(path)) {
				return;
			}
			File f = path.toFile();
			boolean ok = f.setExecutable(true, true) || f.setExecutable(true, false);
			if (ok && Files.isExecutable(path)) {
				JockeyRadioMod.LOGGER.info(
						"[jockey] set the execute bit on '{}' (uploaded binaries arrive non-executable "
								+ "on managed hosts)", binary);
			} else {
				JockeyRadioMod.LOGGER.warn(
						"[jockey] '{}' exists but is not executable and the execute bit could not be set", binary);
			}
		} catch (Throwable t) {
			// Never fatal - the -version probe decides the outcome.
			JockeyRadioMod.LOGGER.debug("[jockey] ensureExecutable('{}') skipped: {}", binary, t.toString());
		}
	}

	private static String trim(String s) {
		return s.length() > 90 ? s.substring(0, 90) + "…" : s;
	}
}
