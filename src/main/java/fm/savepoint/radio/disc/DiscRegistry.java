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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gson-backed store of admin-authored {@link DiscDefinition}s, persisted at
 * {@code <configDir>/travelers_jukebox/discs.json} as a {@code discId -> disc}
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

	private static final String DIR_NAME = "travelers_jukebox";
	public static final String FILE_NAME = "discs.json";
	private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, DiscDefinition>>() {
	}.getType();

	private static final Gson GSON = new GsonBuilder()
			.setPrettyPrinting()
			.disableHtmlEscaping()
			.create();

	private final Map<String, DiscDefinition> discs = new ConcurrentHashMap<>();
	private final Object saveLock = new Object();

	/**
	 * Set when {@link #load()} could not read {@code discs.json} OR dropped some of its
	 * entries. While set, the registry is READ-ONLY: a save would atomically overwrite the
	 * operator's file without the definitions that failed to load, destroying tokens and
	 * stream keys that exist nowhere else. Cleared by the next fully successful load, so
	 * fixing the file and running {@code /jukebox reload} restores normal operation.
	 */
	private volatile boolean loadFailed;

	/** Shown to the admin when a mutation is refused because the on-disk registry is unreadable. */
	private static final String READ_ONLY_REASON =
			FILE_NAME + " could not be fully read, so saving is disabled to protect the discs already in it. "
					+ "Fix or remove that file, then run /jukebox reload.";

	/** Sentinel {@link #remove} returns when the disc id does not exist; the caller renders the id safely. */
	public static final String NOT_FOUND_MARKER = "\u0000not-found";

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
			SavePointRadioMod.LOGGER.error("[jukebox] disc change-listener failed", t);
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
	 * Loads the registry from disk, replacing the in-memory contents. A missing file is a
	 * clean empty registry. A whole-file parse failure logs, KEEPS the discs already loaded
	 * (so a bad {@code /jukebox reload} does not silence live playback), and latches the
	 * registry {@linkplain #isReadOnly() read-only} so no save overwrites the unreadable file.
	 * Individually-rejected entries (bad {@code discId}, failing {@link DiscDefinition#validate()},
	 * duplicate id) are dropped with a warning and ALSO latch read-only, because a later save
	 * would otherwise erase them from disk. The latch clears on the next fully clean load.
	 */
	public void load() {
		Path path = file();
		if (Files.notExists(path)) {
			// A missing file is a well-defined EMPTY registry, not an unreadable one - clear the
			// read-only latch so the documented "remove the broken file, then /jukebox reload"
			// recovery actually restores write access instead of latching forever.
			replaceAll(new LinkedHashMap<>());
			loadFailed = false;
			SavePointRadioMod.LOGGER.info("[jukebox] disc registry empty (no {} yet)", path);
			return;
		}
		Map<String, DiscDefinition> fresh = new LinkedHashMap<>();
		boolean dropped;
		try (Reader reader = Files.newBufferedReader(path)) {
			Map<String, DiscDefinition> loaded = GSON.fromJson(reader, MAP_TYPE);
			if (loaded == null) {
				throw new IllegalStateException("discs.json is empty");
			}
			dropped = parseEntries(loaded, fresh);
		} catch (Exception e) {
			// A whole-file parse failure: latch read-only and DO NOT touch the live map. The
			// previous version replaced it with an empty map here, which fired the change
			// listener and turned every currently-playing registry disc inert on a bad
			// /jukebox reload. Keeping the last-good map means playback survives a bad reload,
			// and loadFailed still blocks any save from overwriting the unreadable file.
			loadFailed = true;
			SavePointRadioMod.LOGGER.error(
					"[jukebox] malformed {} - keeping the discs already loaded and DISABLING saves until this "
							+ "file is fixed or removed, so its definitions are not overwritten.", path, e);
			return;
		}
		replaceAll(fresh);
		// A partial drop is as dangerous as a whole-file failure: save() serialises only the
		// surviving map, so the very next create/delete would erase the dropped entries (and
		// their irreplaceable tokens / stream keys) from disk. Latch read-only exactly the same
		// way, so playback of the good discs continues but nothing overwrites the file until the
		// operator fixes it. A clean load clears the latch.
		loadFailed = dropped;
		if (dropped) {
			SavePointRadioMod.LOGGER.error(
					"[jukebox] {} loaded with {} valid disc(s) but some entries were rejected (see warnings above) "
							+ "- saves are DISABLED so the rejected entries are not lost. Fix or remove them, then "
							+ "/jukebox reload.", FILE_NAME, fresh.size());
		} else {
			SavePointRadioMod.LOGGER.info("[jukebox] loaded {} disc(s) from {}", fresh.size(), path);
		}
	}

	/**
	 * Validates each raw entry into {@code fresh}. Returns true if ANY entry was rejected or
	 * silently dropped, so {@link #load} can protect the on-disk file from a destructive save.
	 */
	private boolean parseEntries(Map<String, DiscDefinition> loaded, Map<String, DiscDefinition> fresh) {
		boolean dropped = false;
		for (Map.Entry<String, DiscDefinition> entry : loaded.entrySet()) {
			String key = entry.getKey();
			DiscDefinition def = entry.getValue();
			if (def == null) {
				SavePointRadioMod.LOGGER.warn("[jukebox] ignoring disc '{}' in {}: entry is null", key, FILE_NAME);
				dropped = true;
				continue;
			}
			// applyDefaults() trims and lowercases discId, so the key must be normalised
			// the same way before it is adopted or compared. Otherwise a hand-authored
			// entry keyed "Chillhop" with no discId field adopted "Chillhop", was
			// rewritten to "chillhop", and was then dropped for disagreeing with itself.
			String normalizedKey = key == null ? null : key.trim().toLowerCase(Locale.ROOT);
			if (def.discId == null || def.discId.isBlank()) {
				def.discId = normalizedKey;
			}
			def.applyDefaults();
			if (normalizedKey != null && !normalizedKey.equals(def.discId)) {
				SavePointRadioMod.LOGGER.warn(
						"[jukebox] ignoring disc '{}' in {}: map key does not match discId '{}'",
						key, FILE_NAME, def.discId);
				dropped = true;
				continue;
			}
			Optional<String> error = def.validate();
			if (error.isPresent()) {
				SavePointRadioMod.LOGGER.warn("[jukebox] ignoring disc '{}' in {}: {}",
						def.discId, FILE_NAME, error.get());
				dropped = true;
				continue;
			}
			// Two keys that normalise to the same id (case/whitespace) used to silently collapse,
			// last-in-file winning - so a stale duplicate could replace the canonical disc and its
			// token. First wins, and the duplicate is reported and counted as a drop.
			if (fresh.containsKey(def.discId)) {
				SavePointRadioMod.LOGGER.warn(
						"[jukebox] ignoring disc '{}' in {}: duplicate id '{}' (an earlier entry already claims it)",
						key, FILE_NAME, def.discId);
				dropped = true;
				continue;
			}
			// load() cannot reject an RTMP port collision (dropping a hand-authored disc is too
			// destructive), but it must not be silent: two discs that fight for the same socket
			// mean one listener can never bind. Uses the SAME wildcard-aware rule put() enforces,
			// so a wildcard bind (0.0.0.0/::) vs a concrete IP on one port is caught too - distinct
			// concrete interfaces on one port genuinely do not clash and are not warned.
			if (def.sourceType == DiscDefinition.SourceType.RTMP_RECEIVE && def.rtmpPort > 0) {
				for (DiscDefinition other : fresh.values()) {
					if (other.sourceType == DiscDefinition.SourceType.RTMP_RECEIVE
							&& other.rtmpPort == def.rtmpPort && bindsClash(def.rtmpBind, other.rtmpBind)) {
						SavePointRadioMod.LOGGER.warn(
								"[jukebox] discs '{}' and '{}' both listen on {}:{} - only one can bind it; the other "
										+ "will never receive audio. Give one a different rtmpPort.",
								other.discId, def.discId, def.rtmpBind, def.rtmpPort);
						break;
					}
				}
			}
			fresh.put(def.discId, def);
		}
		return dropped;
	}

	/** Reload = load. Kept as a named entry point for {@code /jukebox reload}. */
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
	 * Validates and stores a disc (create or overwrite), then persists. Returns an error
	 * string when nothing was durably stored - a validation failure, an unreadable registry
	 * file, an RTMP port conflict, or a failed disk write. Empty means "on disk".
	 *
	 * <p>Transactional: a failed write ROLLS BACK the in-memory change and does not fire the
	 * change listener, so "a non-empty error means nothing happened" holds - the admin can
	 * fix the problem and retry the same create without the half-committed disc tripping the
	 * duplicate-id guard, and a mutation is never audited as done when it was not persisted.</p>
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
		if (loadFailed) {
			return Optional.of(READ_ONLY_REASON);
		}
		// Cross-disc uniqueness can only be checked here - validate() sees one disc. Two
		// RTMP discs on one bind:port is not a warning case: the loser can never bind, so it
		// respawns ffmpeg against an in-use port forever and its DJ is refused silently.
		Optional<String> portConflict = rtmpPortConflict(def);
		if (portConflict.isPresent()) {
			return portConflict;
		}
		DiscDefinition previous = discs.put(def.discId, def);
		String writeError = save();
		if (writeError != null) {
			// Roll the map back to exactly its prior state, so the failure is total.
			if (previous != null) {
				discs.put(def.discId, previous);
			} else {
				discs.remove(def.discId);
			}
			return Optional.of("could not write " + FILE_NAME + " (" + writeError
					+ ") - the disc was NOT saved; free space or fix permissions and try again");
		}
		fireChanged();
		return Optional.empty();
	}

	/**
	 * Whether another {@code rtmp_receive} disc already listens on {@code def}'s bind:port.
	 * Returns the error to show the admin, or empty when it is free (or the disc is not an
	 * RTMP receiver, or it is the same disc being edited).
	 *
	 * <p>Keyed on bind AND port, because two discs bound to different concrete interfaces on
	 * the same port do not clash at the OS level - the earlier port-only check refused to let
	 * a multi-homed operator ever edit either such disc again. A wildcard bind ({@code 0.0.0.0}
	 * / {@code ::}) conflicts with everything on that port.</p>
	 */
	private Optional<String> rtmpPortConflict(DiscDefinition def) {
		if (def.sourceType != DiscDefinition.SourceType.RTMP_RECEIVE || def.rtmpPort <= 0) {
			return Optional.empty();
		}
		for (DiscDefinition other : discs.values()) {
			if (other.discId.equals(def.discId)
					|| other.sourceType != DiscDefinition.SourceType.RTMP_RECEIVE
					|| other.rtmpPort != def.rtmpPort) {
				continue;
			}
			if (bindsClash(def.rtmpBind, other.rtmpBind)) {
				return Optional.of("RTMP " + def.rtmpBind + ":" + def.rtmpPort + " is already used by disc '"
						+ other.discId + "' - only one disc can listen on an address. Pick another port.");
			}
		}
		return Optional.empty();
	}

	/** True when two RTMP binds on the same port would fight for the socket. */
	private static boolean bindsClash(String a, String b) {
		return isWildcardBind(a) || isWildcardBind(b) || java.util.Objects.equals(a, b);
	}

	private static boolean isWildcardBind(String bind) {
		return "0.0.0.0".equals(bind) || "::".equals(bind) || bind == null || bind.isBlank();
	}

	/**
	 * Removes a disc and persists. Returns empty on success, else why nothing durable
	 * happened (unreadable registry, no such disc, or a failed write).
	 *
	 * <p>Transactional in the same way as {@link #put}: a failed write restores the disc and
	 * does not fire the listener, so the reported failure is total. The read-only latch is
	 * checked first, so it is reachable even though the map is empty while latched.</p>
	 */
	public Optional<String> remove(String discId) {
		if (loadFailed) {
			return Optional.of(READ_ONLY_REASON);
		}
		if (discId == null || !discs.containsKey(discId)) {
			return Optional.of(NOT_FOUND_MARKER);
		}
		DiscDefinition previous = discs.remove(discId);
		String writeError = save();
		if (writeError != null) {
			discs.put(discId, previous);
			return Optional.of("could not write " + FILE_NAME + " (" + writeError
					+ ") - the disc was NOT deleted; free space or fix permissions and try again");
		}
		fireChanged();
		return Optional.empty();
	}

	/** Whether the registry is read-only because {@code discs.json} could not be read. */
	public boolean isReadOnly() {
		return loadFailed;
	}

	public int size() {
		return discs.size();
	}

	/**
	 * Atomically writes the current map to disk under {@link #saveLock}. A
	 * consistent ordered snapshot is taken before serialization so a concurrent
	 * put/remove cannot interleave into the JSON.
	 *
	 * @return null on success, else a short reason the caller must surface. The failure
	 *         used to be logged and swallowed, so the dialog reported "Saved" for a disc
	 *         that only existed in memory until the next restart.
	 */
	private String save() {
		Path path = file();
		Map<String, DiscDefinition> snapshot = new LinkedHashMap<>();
		for (DiscDefinition def : list()) {
			snapshot.put(def.discId, def);
		}
		String json = GSON.toJson(snapshot, MAP_TYPE);
		synchronized (saveLock) {
			Path tmp = path.resolveSibling(FILE_NAME + ".tmp");
			try {
				Files.createDirectories(path.getParent());
				Files.writeString(tmp, json);
				try {
					Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				} catch (AtomicMoveNotSupportedException e) {
					Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
				}
				return null;
			} catch (IOException e) {
				SavePointRadioMod.LOGGER.error("[jukebox] failed to write disc registry to {}", path, e);
				// Don't leave a half-written temp beside the registry on the failure path.
				try {
					Files.deleteIfExists(tmp);
				} catch (IOException ignored) {
					// best-effort
				}
				return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
			}
		}
	}
}
