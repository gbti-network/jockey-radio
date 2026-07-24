package fm.savepoint.radio.disc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import fm.savepoint.radio.SavePointRadioMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gson-backed store of admin-authored {@link DiscDefinition}s, persisted at
 * {@code <configDir>/savepoint_radio/discs.json} as a {@code discId -> disc}
 * object.
 *
 * <p>Reads go through a {@link ConcurrentHashMap} (any thread); writes are
 * serialized on {@link #saveLock} and land atomically (temp file +
 * {@code Files.move ATOMIC_MOVE/REPLACE}) so a crash mid-write can never leave
 * a truncated registry behind. A malformed file is logged and treated as empty
 * - the server keeps running and the broken file on disk is left untouched.</p>
 */
public final class DiscRegistry {

	private static final DiscRegistry INSTANCE = new DiscRegistry();

	private static final String DIR_NAME = "savepoint_radio";
	private static final String FILE_NAME = "discs.json";
	private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, DiscDefinition>>() {
	}.getType();

	private static final Gson GSON = new GsonBuilder()
			.setPrettyPrinting()
			.disableHtmlEscaping()
			.create();

	private final Map<String, DiscDefinition> discs = new ConcurrentHashMap<>();
	private final Object saveLock = new Object();

	/**
	 * Optional single change-listener fired after any mutation ({@link #put},
	 * {@link #remove}, {@link #replaceAll}). Used by {@code RtmpKeepAlive} to
	 * reconcile always-on anchors on create/edit/delete/reload. Never throws into
	 * the caller (a listener failure is logged and swallowed).
	 */
	private volatile Runnable changeListener;

	/** Registers (or clears with {@code null}) the post-mutation change-listener. */
	public void setChangeListener(Runnable listener) {
		this.changeListener = listener;
	}

	private void fireChanged() {
		Runnable l = changeListener;
		if (l == null) {
			return;
		}
		try {
			l.run();
		} catch (Throwable t) {
			SavePointRadioMod.LOGGER.error("[radio] disc change-listener failed", t);
		}
	}

	public static DiscRegistry get() {
		return INSTANCE;
	}

	private DiscRegistry() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(DIR_NAME).resolve(FILE_NAME);
	}

	/**
	 * Loads the registry from disk, replacing the in-memory contents. Missing
	 * file is fine (empty registry, nothing written until the first
	 * {@link #put}); a malformed file logs and yields an empty registry without
	 * crashing. Entries that fail {@link DiscDefinition#validate()} or whose map
	 * key disagrees with their {@code discId} are dropped with a warning.
	 */
	public void load() {
		Path path = file();
		Map<String, DiscDefinition> fresh = new LinkedHashMap<>();
		if (Files.notExists(path)) {
			replaceAll(fresh);
			SavePointRadioMod.LOGGER.info("[radio] disc registry empty (no {} yet)", path);
			return;
		}
		try (Reader reader = Files.newBufferedReader(path)) {
			Map<String, DiscDefinition> loaded = GSON.fromJson(reader, MAP_TYPE);
			if (loaded == null) {
				throw new IllegalStateException("discs.json is empty");
			}
			for (Map.Entry<String, DiscDefinition> entry : loaded.entrySet()) {
				String key = entry.getKey();
				DiscDefinition def = entry.getValue();
				if (def == null) {
					SavePointRadioMod.LOGGER.warn("[radio] ignoring disc '{}' in {}: entry is null", key, FILE_NAME);
					continue;
				}
				if (def.discId == null || def.discId.isBlank()) {
					def.discId = key;
				}
				def.applyDefaults();
				if (key != null && !key.equals(def.discId)) {
					SavePointRadioMod.LOGGER.warn(
							"[radio] ignoring disc '{}' in {}: map key does not match discId '{}'",
							key, FILE_NAME, def.discId);
					continue;
				}
				Optional<String> error = def.validate();
				if (error.isPresent()) {
					SavePointRadioMod.LOGGER.warn("[radio] ignoring disc '{}' in {}: {}",
							def.discId, FILE_NAME, error.get());
					continue;
				}
				fresh.put(def.discId, def);
			}
			replaceAll(fresh);
			SavePointRadioMod.LOGGER.info("[radio] loaded {} disc(s) from {}", fresh.size(), path);
		} catch (Exception e) {
			replaceAll(fresh);
			SavePointRadioMod.LOGGER.error(
					"[radio] malformed {} - starting with no discs (file left untouched)", path, e);
		}
	}

	/** Reload = load. Kept as a named entry point for {@code /savepoint reload}. */
	public void reload() {
		load();
	}

	private void replaceAll(Map<String, DiscDefinition> fresh) {
		discs.keySet().retainAll(fresh.keySet());
		discs.putAll(fresh);
		fireChanged();
	}

	/** The disc with this id, or empty. Safe on any thread. */
	public Optional<DiscDefinition> get(String discId) {
		if (discId == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(discs.get(discId));
	}

	/** Immutable snapshot of all discs, ordered by id. Safe on any thread. */
	public List<DiscDefinition> list() {
		List<DiscDefinition> all = new ArrayList<>(discs.values());
		all.sort((a, b) -> a.discId.compareTo(b.discId));
		return all;
	}

	/**
	 * Validates and stores a disc (create or overwrite), then persists. Returns
	 * an error string on validation failure (nothing is stored or written).
	 */
	public Optional<String> put(DiscDefinition def) {
		if (def == null) {
			return Optional.of("null disc definition");
		}
		def.applyDefaults();
		Optional<String> error = def.validate();
		if (error.isPresent()) {
			return error;
		}
		discs.put(def.discId, def);
		save();
		fireChanged();
		return Optional.empty();
	}

	/** Removes a disc and persists. Returns true if one was removed. */
	public boolean remove(String discId) {
		if (discId == null) {
			return false;
		}
		DiscDefinition removed = discs.remove(discId);
		if (removed != null) {
			save();
			fireChanged();
			return true;
		}
		return false;
	}

	public int size() {
		return discs.size();
	}

	/**
	 * Atomically writes the current map to disk under {@link #saveLock}. A
	 * consistent ordered snapshot is taken before serialization so a concurrent
	 * put/remove cannot interleave into the JSON.
	 */
	private void save() {
		Path path = file();
		Map<String, DiscDefinition> snapshot = new LinkedHashMap<>();
		for (DiscDefinition def : list()) {
			snapshot.put(def.discId, def);
		}
		String json = GSON.toJson(snapshot, MAP_TYPE);
		synchronized (saveLock) {
			try {
				Files.createDirectories(path.getParent());
				Path tmp = path.resolveSibling(FILE_NAME + ".tmp");
				Files.writeString(tmp, json);
				try {
					Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				} catch (AtomicMoveNotSupportedException e) {
					Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
				}
			} catch (IOException e) {
				SavePointRadioMod.LOGGER.error("[radio] failed to write disc registry to {}", path, e);
			}
		}
	}
}
