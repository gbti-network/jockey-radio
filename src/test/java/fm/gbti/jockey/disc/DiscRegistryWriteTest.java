package fm.gbti.jockey.disc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * How discs.json is replaced. A crash cannot be staged in a unit test, so the syncs are recorded
 * together with what is on disk at the moment each one runs.
 */
class DiscRegistryWriteTest {

	/** Records each sync and the target's contents at that moment. */
	private static final class Recorder implements DiscRegistry.Sync {
		final List<String> events = new ArrayList<>();
		final Path target;
		final Path tmp;

		Recorder(Path target) {
			this.target = target;
			this.tmp = target.resolveSibling(target.getFileName() + ".tmp");
		}

		private String read(Path p) {
			try {
				return Files.exists(p) ? Files.readString(p) : "<none>";
			} catch (IOException e) {
				return "<unreadable>";
			}
		}

		@Override
		public void file(FileChannel channel) throws IOException {
			events.add("file size=" + channel.size() + " target=" + read(target));
		}

		@Override
		public void directory(Path dir) {
			events.add("dir " + dir.getFileName() + " target=" + read(target) + " tmp=" + read(tmp));
		}
	}

	@Test
	@DisplayName("the temp file is forced before it replaces discs.json, and the directory after")
	void syncsInOrder(@TempDir Path dir) throws IOException {
		Path target = dir.resolve("jockey_radio").resolve("discs.json");
		Files.createDirectories(target.getParent());
		Files.writeString(target, "{old}");
		Recorder sync = new Recorder(target);

		DiscRegistry.writeDurably(target, "{new}", sync);

		assertEquals(List.of(
				"file size=5 target={old}",
				"dir jockey_radio target={new} tmp=<none>"), sync.events);
		assertEquals("{new}", Files.readString(target));
	}

	@Test
	@DisplayName("a failed write leaves discs.json untouched and no temp file behind")
	void failureLeavesTargetAlone(@TempDir Path dir) throws IOException {
		Path target = dir.resolve("discs.json");
		Files.writeString(target, "{old}");
		DiscRegistry.Sync failing = new DiscRegistry.Sync() {
			@Override
			public void file(FileChannel channel) throws IOException {
				throw new IOException("disk full");
			}

			@Override
			public void directory(Path d) {
			}
		};

		assertThrows(IOException.class, () -> DiscRegistry.writeDurably(target, "{new}", failing));
		assertEquals("{old}", Files.readString(target));
		assertFalse(Files.exists(dir.resolve("discs.json.tmp")), "temp file left behind");
	}

	@Test
	@DisplayName("the real sync works on this platform")
	void realDiskSync(@TempDir Path dir) throws IOException {
		Path target = dir.resolve("discs.json");
		DiscRegistry.writeDurably(target, "{\"a\":1}", DiscRegistry.Sync.DISK);
		assertEquals("{\"a\":1}", Files.readString(target));
	}
}
