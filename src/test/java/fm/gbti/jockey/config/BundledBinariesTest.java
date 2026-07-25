package fm.gbti.jockey.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline tests for the platform gate that decides whether the jar-bundled binaries are
 * eligible to install. The extraction/chmod path needs a real jar + filesystem and is not
 * exercised here; this pins the pure decision so we never ship a linux binary to a Windows or
 * ARM host (which would fail exec and mask a "just use PATH" fallback as a hard error).
 */
class BundledBinariesTest {

	@Test
	@DisplayName("installs only on linux/amd64 (the one platform we ship a binary for)")
	void supportedPlatform() {
		assertTrue(BundledBinaries.isSupportedPlatform("Linux", "amd64"));
		assertTrue(BundledBinaries.isSupportedPlatform("Linux", "x86_64"));
		// case-insensitive on both axes
		assertTrue(BundledBinaries.isSupportedPlatform("linux", "AMD64"));
	}

	@Test
	@DisplayName("skips every platform we do not ship a binary for")
	void unsupportedPlatforms() {
		assertFalse(BundledBinaries.isSupportedPlatform("Windows 11", "amd64"));
		assertFalse(BundledBinaries.isSupportedPlatform("Mac OS X", "aarch64"));
		assertFalse(BundledBinaries.isSupportedPlatform("Linux", "aarch64"));
		assertFalse(BundledBinaries.isSupportedPlatform("Linux", "arm"));
		assertFalse(BundledBinaries.isSupportedPlatform(null, "amd64"));
		assertFalse(BundledBinaries.isSupportedPlatform("Linux", null));
	}
}
