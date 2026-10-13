package fm.gbti.jockey.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline tests for the platform gate that decides which jar-bundled binaries install, and
 * for when the bundled copy replaces the configured one. The extraction itself needs a real
 * jar and is covered by tools/zero-touch-check.sh. The gate matters because a binary for the
 * wrong OS or arch fails exec and would mask the "just use PATH" fallback as a hard error.
 */
class BundledBinariesTest {

	@Test
	@DisplayName("ships linux/amd64 and windows/amd64 binaries")
	void supportedPlatforms() {
		assertEquals(BundledBinaries.Platform.LINUX_AMD64, BundledBinaries.platform("Linux", "amd64"));
		assertEquals(BundledBinaries.Platform.LINUX_AMD64, BundledBinaries.platform("linux", "X86_64"));
		assertEquals(BundledBinaries.Platform.WINDOWS_AMD64, BundledBinaries.platform("Windows 11", "amd64"));
		assertEquals(BundledBinaries.Platform.WINDOWS_AMD64, BundledBinaries.platform("Windows Server 2022", "amd64"));
		assertEquals("/jockey_radio/bin/windows-amd64/ffmpeg.exe",
				BundledBinaries.Platform.WINDOWS_AMD64.resource("ffmpeg"));
		assertEquals("/jockey_radio/bin/linux-amd64/yt-dlp",
				BundledBinaries.Platform.LINUX_AMD64.resource("yt-dlp"));
	}

	@Test
	@DisplayName("skips every platform we do not ship a binary for")
	void unsupportedPlatforms() {
		assertFalse(BundledBinaries.isSupportedPlatform("Mac OS X", "aarch64"));
		assertFalse(BundledBinaries.isSupportedPlatform("Mac OS X", "x86_64"));
		assertFalse(BundledBinaries.isSupportedPlatform("Linux", "aarch64"));
		assertFalse(BundledBinaries.isSupportedPlatform("Windows 11", "aarch64"));
		assertFalse(BundledBinaries.isSupportedPlatform("Linux", "arm"));
		assertNull(BundledBinaries.platform(null, "amd64"));
		assertNull(BundledBinaries.platform("Linux", null));
	}

	@Test
	@DisplayName("the bundled copy replaces the default name and a configured path that no longer exists")
	void preferBundled(@TempDir Path dir) throws IOException {
		Path bundled = executable(dir.resolve("ffmpeg"));
		Path operatorCopy = executable(dir.resolve("my-ffmpeg"));
		String bundledPath = bundled.toAbsolutePath().toString();

		assertEquals(bundledPath, BundledBinaries.preferBundled("ffmpegBinary", "ffmpeg", "ffmpeg", bundled));
		assertEquals(bundledPath, BundledBinaries.preferBundled("ffmpegBinary",
				dir.resolve("moved/ffmpeg.exe").toString(), "ffmpeg", bundled));
		// An explicit path that exists, or another bare name for PATH lookup, is respected.
		assertEquals(operatorCopy.toString(), BundledBinaries.preferBundled("ffmpegBinary",
				operatorCopy.toString(), "ffmpeg", bundled));
		assertEquals("ffmpeg7", BundledBinaries.preferBundled("ffmpegBinary", "ffmpeg7", "ffmpeg", bundled));
		// No bundled copy: the configured value stands, even when it is missing.
		assertEquals("ffmpeg", BundledBinaries.preferBundled("ffmpegBinary", "ffmpeg", "ffmpeg",
				dir.resolve("absent")));
	}

	private static Path executable(Path p) throws IOException {
		Files.writeString(p, "#!/bin/sh\n");
		p.toFile().setExecutable(true);
		return p;
	}
}
