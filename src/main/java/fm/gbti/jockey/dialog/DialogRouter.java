package fm.gbti.jockey.dialog;

import fm.gbti.jockey.RadioAudit;
import fm.gbti.jockey.config.RadioConfig;
import fm.gbti.jockey.disc.DiscDefinition;
import fm.gbti.jockey.disc.DiscRegistry;
import fm.gbti.jockey.disc.LocalAudioServer;
import fm.gbti.jockey.disc.NameStyler;
import fm.gbti.jockey.disc.RadioDiscFactory;
import fm.gbti.jockey.session.FeedRegistry;
import fm.gbti.jockey.session.MonitorService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.Optional;

/**
 * Server-side handler for the {@code jockey_radio:*} custom-click actions the
 * {@code /jockey} dialogs emit. Invoked from
 * {@code CustomClickActionMixin} on the main server thread (past
 * {@code ensureRunningOnSameThread}), so every {@link DiscRegistry} write and
 * item give here is already on the server thread.
 *
 * <p>The whole payload is client-controlled and untrusted: it may be absent,
 * not a compound, missing keys, or carry hostile values. Every read is
 * defensive, and {@link DiscRegistry#put} re-runs {@link DiscDefinition#validate}
 * before anything is stored. Permission is re-checked on <em>every</em> submit -
 * a client can forge or replay this packet at will - and an unpermitted submit
 * mutates nothing.</p>
 */
public final class DialogRouter {

	/** Entry point. {@code id} namespace has already been checked by the mixin. */
	public static void handle(ServerPlayer player, Identifier id, Optional<Tag> payload) {
		if (!hasAdminPermission(player)) {
			// Never act on an unpermitted submit; a plain terminal notice.
			DialogScreens.send(player, JockeyRadioDialogs.notice("No permission",
					"You do not have permission to manage radio discs."));
			return;
		}
		CompoundTag tag = asCompound(payload);
		switch (id.getPath()) {
			case "nav" -> handleNav(player, tag);
			case "save" -> handleSave(player, tag);
			case "preview" -> handlePreview(player, tag);
			case "cycle_color" -> handleCycleColor(player, tag);
			case "cycle_style" -> handleCycleStyle(player, tag);
			case "cycle_name_style" -> handleCycleNameStyle(player, tag);
			case "delete" -> handleDelete(player, tag);
			case "give" -> handleGive(player, tag);
			case "test" -> handleTest(player, tag);
			case "stop_monitor" -> handleStopMonitor(player, tag);
			default -> {
				// Unknown action id path - ignore (do not touch the registry).
			}
		}
	}

	// ------------------------------------------------------------------
	// nav
	// ------------------------------------------------------------------

	private static void handleNav(ServerPlayer player, CompoundTag tag) {
		String to = tag.getStringOr("to", "");
		switch (to) {
			// Compatibility alias for dialog packets from the former hub flow.
			case "root" -> DialogScreens.send(player,
					JockeyRadioDialogs.manageList(player.registryAccess(), null));
			case "new_disc" -> DialogScreens.send(player, JockeyRadioDialogs.sourceChooser());
			case "create_pull" -> DialogScreens.send(player,
					JockeyRadioDialogs.createPullForm(player.registryAccess(), Optional.empty(), false));
			case "create_rtmp" -> DialogScreens.send(player,
					JockeyRadioDialogs.createRtmpForm(player.registryAccess(), Optional.empty(), false));
			// A fresh nav from the source chooser carries nothing -> a blank form. Coming BACK from
			// the audio browser it carries the in-progress values, so the form is restored as typed
			// - including whether this was an EDIT, so cancelling a browse mid-edit stays an edit.
			case "create_file" -> sendFileForm(player, tag, null, null);
			// sow-013: the chooser hides this entry when the gate is closed, but the dialog payload is
			// client-supplied and can be forged or replayed - so refuse here too rather than trusting
			// that the button was not rendered.
			case "create_platform" -> {
				if (!RadioConfig.get().enablePlatformLinks) {
					DialogScreens.send(player, JockeyRadioDialogs.errorThen("Platform links disabled",
							"Platform links are turned off on this server. An operator can enable them by "
									+ "setting enablePlatformLinks to true in jockey_radio.json.",
							"new_disc", ""));
				} else {
					DialogScreens.send(player,
							JockeyRadioDialogs.createPlatformForm(player.registryAccess(), Optional.empty(), false));
				}
			}
			// Capture whatever the admin has already typed so the browse trip does not blank it.
			case "browse_audio" -> DialogScreens.send(player, JockeyRadioDialogs.browseAudio(fileFormCarry(tag)));
			// Re-open the create/edit form the admin was filling in, from the payload a
			// save-error notice carried back. Same lenient rebuild the cyclers use, so
			// nothing typed is lost and an RTMP stream key is never regenerated.
			case "form_back" -> rerenderForm(player, tag, false, false, false);
			case "pick_audio" -> handlePickAudio(player, tag);
			case "manage" -> DialogScreens.send(player, JockeyRadioDialogs.manageList(player.registryAccess(), null));
			case "detail" -> handleDetail(player, tag);
			// sow-014: the hub-level Give menu and Reload button are gone. Give lives on each
			// disc's detail page (give_disc), and registry reload is the /jockey reload command.
			case "give_disc" -> handleGiveForm(player, tag);
			case "edit" -> handleEdit(player, tag);
			case "delete_confirm" -> handleDeleteConfirm(player, tag);
			case "close" -> DialogScreens.clear(player);
			default -> DialogScreens.send(player,
					JockeyRadioDialogs.manageList(player.registryAccess(), null));
		}
	}

	private static void handleDetail(ServerPlayer player, CompoundTag tag) {
		String discId = tag.getStringOr("id", "");
		Optional<DiscDefinition> def = DiscRegistry.get().get(discId);
		if (def.isEmpty()) {
			DialogScreens.send(player, JockeyRadioDialogs.manageList(player.registryAccess(),
					"No disc '" + safe(discId) + "'.", true));
			return;
		}
		DialogScreens.send(player, JockeyRadioDialogs.discDetail(player.registryAccess(), def.get(), null));
	}

	private static void handleGiveForm(ServerPlayer player, CompoundTag tag) {
		String discId = tag.getStringOr("id", "");
		Optional<DiscDefinition> def = DiscRegistry.get().get(discId);
		if (def.isEmpty()) {
			DialogScreens.send(player, JockeyRadioDialogs.manageList(player.registryAccess(),
					"No disc '" + safe(discId) + "'.", true));
			return;
		}
		DialogScreens.send(player, JockeyRadioDialogs.giveForm(player.registryAccess(), def.get()));
	}

	/**
	 * sow-009: an audio-folder file was picked - reopen the FILE create form with that file's
	 * internal loopback URL filled in.
	 *
	 * <p>Everything the admin had already typed (disc id, name, radius, repeat, colour/style/name
	 * style) is carried through the browse round-trip and restored here. Only the audio URL is
	 * replaced by the picked file; the display name falls back to the file's base name ONLY when the
	 * admin has not typed one, so picking a file no longer blanks the id or overwrites the title.</p>
	 */
	private static void handlePickAudio(ServerPlayer player, CompoundTag tag) {
		String file = tag.getStringOr("id", "");
		String url = LocalAudioServer.get().urlFor(file);
		if (file.isEmpty() || url == null) {
			DialogScreens.send(player, JockeyRadioDialogs.errorThen("Audio unavailable",
					"The audio server is off or that file is gone.", "browse_audio", ""));
			return;
		}
		int dot = file.lastIndexOf('.');
		String derivedName = dot > 0 ? file.substring(0, dot) : file;
		sendFileForm(player, tag, url, derivedName);
	}

	/**
	 * Re-opens the FILE form from a carry tag, preserving whether this is an EDIT.
	 *
	 * <p>Both browse exits (picking a file, and Back) route through here. The {@code edit}
	 * key rides in the carry tag; when it is set the form re-opens in edit mode against
	 * that disc id, so a browse during an edit no longer turns into a create.</p>
	 */
	private static void sendFileForm(ServerPlayer player, CompoundTag tag, String streamUrlOverride,
			String nameFallback) {
		String editId = tag.getStringOr("edit", "");
		boolean edit = !editId.isBlank();
		Optional<DiscDefinition> prefill = fileCarryPrefill(tag, streamUrlOverride, nameFallback);
		if (edit) {
			// The edit form omits the id input, so the id must come from the carried edit key
			// rather than from a field the admin could not have filled in.
			prefill.ifPresent(d -> d.discId = editId);
		}
		DialogScreens.send(player, JockeyRadioDialogs.createFileForm(player.registryAccess(), prefill, edit));
	}

	/**
	 * sow-009 fix: snapshot the FILE form's in-progress values out of a {@link CustomAll} submit so
	 * they survive the audio-browse round-trip (that dialog has no inputs of its own). {@code
	 * radius_blocks} is a FloatTag from the slider, so it is carried as a float; the marker key
	 * distinguishes "came from the form" from a fresh nav that should open a blank form.
	 */
	private static CompoundTag fileFormCarry(CompoundTag tag) {
		CompoundTag c = new CompoundTag();
		c.putString("carry", "1");
		// Carried so an EDIT stays an edit across the round trip (the browse dialog and its
		// Back button re-emit this whole tag). Without it the trip degraded into a create.
		c.putString("edit", tag.getStringOr("edit", ""));
		c.putString("disc_id", tag.getStringOr("disc_id", ""));
		c.putString("name", tag.getStringOr("name", ""));
		c.putString("stream_url", tag.getStringOr("stream_url", ""));
		c.putString("repeat", tag.getStringOr("repeat", "on"));
		c.putFloat("radius_blocks",
				tag.getFloatOr("radius_blocks", DiscDefinition.DEFAULT_RADIUS_BLOCKS));
		c.putString("name_color", tag.getStringOr("name_color", ""));
		c.putString("base_disc", tag.getStringOr("base_disc", ""));
		c.putString("name_style", tag.getStringOr("name_style", ""));
		return c;
	}

	/**
	 * Rebuilds the in-progress FILE disc from a carry tag. Returns empty (a blank form) when nothing
	 * was carried and no URL is being applied - i.e. a fresh "New file disc" nav. {@code
	 * streamUrlOverride} (a picked audio file) wins over any typed URL; {@code nameFallback} is used
	 * only when the admin typed no name.
	 */
	private static Optional<DiscDefinition> fileCarryPrefill(CompoundTag tag, String streamUrlOverride,
			String nameFallback) {
		boolean carried = "1".equals(tag.getStringOr("carry", ""));
		if (!carried && streamUrlOverride == null) {
			return Optional.empty();
		}
		DiscDefinition d = new DiscDefinition();
		d.sourceType = DiscDefinition.SourceType.FILE;
		d.discId = tag.getStringOr("disc_id", "");
		String typedName = tag.getStringOr("name", "");
		d.name = !typedName.isBlank() ? typedName : (nameFallback == null ? "" : nameFallback);
		d.streamUrl = streamUrlOverride != null ? streamUrlOverride : tag.getStringOr("stream_url", "");
		d.repeat = !"off".equals(tag.getStringOr("repeat", "on"));
		d.radiusBlocks = Math.round(tag.getFloatOr("radius_blocks", DiscDefinition.DEFAULT_RADIUS_BLOCKS));
		// Blank -> leave null so the form falls back to its defaults rather than an empty style id.
		String color = tag.getStringOr("name_color", "");
		if (!color.isBlank()) {
			d.nameColorHex = color;
		}
		String baseDisc = tag.getStringOr("base_disc", "");
		if (!baseDisc.isBlank()) {
			d.baseDisc = baseDisc;
		}
		String nameStyle = tag.getStringOr("name_style", "");
		if (!nameStyle.isBlank()) {
			d.nameStyle = nameStyle;
		}
		return Optional.of(d);
	}

	private static void handleEdit(ServerPlayer player, CompoundTag tag) {
		String discId = tag.getStringOr("id", "");
		Optional<DiscDefinition> def = DiscRegistry.get().get(discId);
		if (def.isEmpty()) {
			DialogScreens.send(player, JockeyRadioDialogs.errorThen("Unknown disc",
					"No disc '" + safe(discId) + "'.", "manage", ""));
			return;
		}
		DiscDefinition d = editView(def.get());
		switch (d.sourceType) {
			case RTMP_RECEIVE -> DialogScreens.send(player,
					JockeyRadioDialogs.createRtmpForm(player.registryAccess(), Optional.of(d), true));
			case FILE -> DialogScreens.send(player,
					JockeyRadioDialogs.createFileForm(player.registryAccess(), Optional.of(d), true));
			case PLATFORM -> DialogScreens.send(player,
					JockeyRadioDialogs.createPlatformForm(player.registryAccess(), Optional.of(d), true));
			case HLS_PULL -> DialogScreens.send(player,
					JockeyRadioDialogs.createPullForm(player.registryAccess(), Optional.of(d), true));
		}
	}

	/**
	 * A form-facing copy of a stored disc with the secret fields ({@code token},
	 * {@code streamKey}, {@code nowPlayingToken}) blanked, so a fresh Edit shows
	 * the same empty token/stream-key inputs W4 always has ("blank keeps current")
	 * and never pushes a stored secret into the form - without mutating the live
	 * registry object ({@link DiscRegistry#get} returns the shared instance).
	 */
	private static DiscDefinition editView(DiscDefinition stored) {
		DiscDefinition view = new DiscDefinition();
		view.discId = stored.discId;
		view.name = stored.name;
		view.nameColorHex = stored.nameColorHex;
		view.nameStyle = stored.nameStyle;
		view.repeat = stored.repeat;
		view.baseDisc = stored.baseDisc;
		view.sourceType = stored.sourceType;
		view.streamUrl = stored.streamUrl;
		view.host = stored.host;
		view.stationId = stored.stationId;
		view.nowPlayingUrl = stored.nowPlayingUrl;
		view.nowPlayingTemplate = stored.nowPlayingTemplate;
		view.rtmpPort = stored.rtmpPort;
		view.rtmpBind = stored.rtmpBind;
		view.radiusBlocks = stored.radiusBlocks;
		// nowPlayingUrl is NOT a secret (no token in it), so it prefills the form.
		// token / streamKey / nowPlayingToken intentionally left null (secrets).
		return view;
	}

	private static void handleDeleteConfirm(ServerPlayer player, CompoundTag tag) {
		String discId = tag.getStringOr("id", "");
		Optional<DiscDefinition> def = DiscRegistry.get().get(discId);
		if (def.isEmpty()) {
			DialogScreens.send(player, JockeyRadioDialogs.manageList(player.registryAccess(),
					"No disc '" + safe(discId) + "' to delete.", true));
			return;
		}
		DialogScreens.send(player, JockeyRadioDialogs.deleteConfirm(def.get().discId, def.get().name));
	}

	// ------------------------------------------------------------------
	// save
	// ------------------------------------------------------------------

	private static void handleSave(ServerPlayer player, CompoundTag tag) {
		String source = tag.getStringOr("source", "");
		String editId = tag.getStringOr("edit", "");
		boolean edit = !editId.isEmpty();

		// sow-013: never persist a platform disc while the gate is closed. handleSave is reachable
		// from any forged submit, so this is the authoritative check on the write path.
		if ("platform".equals(source) && !RadioConfig.get().enablePlatformLinks) {
			DialogScreens.send(player, JockeyRadioDialogs.errorThen("Platform links disabled",
					"Platform links are turned off on this server, so this disc was not saved. An operator "
							+ "can enable them by setting enablePlatformLinks to true in jockey_radio.json.",
					"manage", ""));
			return;
		}

		DiscDefinition existing = null;
		if (edit) {
			existing = DiscRegistry.get().get(editId).orElse(null);
			if (existing == null) {
				DialogScreens.send(player, JockeyRadioDialogs.errorThen("Unknown disc",
						"No disc '" + safe(editId) + "' to edit.", "manage", ""));
				return;
			}
		}

		DiscDefinition def = new DiscDefinition();
		def.discId = edit ? editId : tag.getStringOr("disc_id", "");
		def.name = tag.getStringOr("name", "");
		// The name colour rides in the Save button payload (not a form input), so it
		// is exactly the colour the form's preview was showing when Save was clicked.
		def.nameColorHex = tag.getStringOr("name_color", "#37C871");
		// The base disc (style) rides in the Save button payload (the style cycler
		// advances it), so it is exactly what the form's preview was showing. Blank
		// (a forged/absent payload) preserves a stored disc's base on edit - it may be
		// a hand-authored custom base - else falls back to the default. put() -> validate()
		// re-checks the id shape, and resolveBaseDisc falls back safely on an unknown item.
		String baseDisc = tag.getStringOr("base_disc", "");
		def.baseDisc = !baseDisc.isBlank() ? baseDisc
				: (edit ? existing.baseDisc : DiscDefinition.DEFAULT_BASE_DISC);
		// The name style (sow-010) also rides in the Save payload (the name-style cycler advances
		// it). applyDefaults() normalizes an unknown/"plain" value to null (= no styling); the
		// stored name stays plain, so validation/length are unaffected.
		String nameStyle = tag.getStringOr("name_style", "");
		def.nameStyle = nameStyle.isBlank() ? (edit ? existing.nameStyle : null) : nameStyle;
		if (edit) {
			// The now-playing token is a hand-set secret (discs.json only) not
			// exposed in the form, so it must survive an edit.
			def.nowPlayingToken = existing.nowPlayingToken;
		}
		// Audible radius comes from the NumberRangeInput slider as a FloatTag;
		// a forged/absent payload falls back to the stored (edit) or default value,
		// and put() re-validates 1..512 regardless.
		def.radiusBlocks = Math.round(tag.getFloatOr("radius_blocks",
				edit ? existing.radiusBlocks : DiscDefinition.DEFAULT_RADIUS_BLOCKS));

		if ("rtmp_receive".equals(source)) {
			def.sourceType = DiscDefinition.SourceType.RTMP_RECEIVE;
			def.rtmpPort = parseInt(tag.getStringOr("rtmp_port", ""), -1);
			String key = tag.getStringOr("stream_key", "");
			// Blank on edit keeps the stored secret; never echoed back to the client.
			def.streamKey = (edit && key.isBlank()) ? existing.streamKey : key;
			String bindFallback = edit
					? DiscRegistry.get().get(editId)
							.map(existingDisc -> existingDisc.rtmpBind)
							.orElse(DiscDefinition.DEFAULT_RTMP_BIND)
					: DiscDefinition.NEW_RTMP_BIND;
			def.rtmpBind = tag.getStringOr("rtmp_bind", bindFallback);
		} else if ("file".equals(source)) {
			// sow-009: a FILE disc loops an http/https audio-file URL. No token/rtmp fields;
			// validate() requires the streamUrl.
			def.sourceType = DiscDefinition.SourceType.FILE;
			String streamUrl = tag.getStringOr("stream_url", "");
			def.streamUrl = streamUrl.isBlank() ? null : streamUrl;
		} else if ("platform".equals(source)) {
			// sow-007: a PLATFORM disc's streamUrl is a platform PAGE url resolved by yt-dlp.
			def.sourceType = DiscDefinition.SourceType.PLATFORM;
			String streamUrl = tag.getStringOr("stream_url", "");
			def.streamUrl = streamUrl.isBlank() ? null : streamUrl;
		} else if ("hls_pull".equals(source)) {
			def.sourceType = DiscDefinition.SourceType.HLS_PULL;
			String streamUrl = tag.getStringOr("stream_url", "");
			def.streamUrl = streamUrl.isBlank() ? null : streamUrl;
			// host/stationId are no longer form inputs but remain the optional
			// now-playing identity; preserve the stored pair on edit (and keep the
			// legacy validation fallback usable when no streamUrl is entered).
			if (edit) {
				def.host = existing.host;
				def.stationId = existing.stationId;
			}
			String token = tag.getStringOr("token", "");
			if (edit && token.isBlank()) {
				def.token = existing.token;
			} else {
				def.token = token.isBlank() ? null : token;
			}
		} else {
			DialogScreens.send(player, JockeyRadioDialogs.errorThen("Invalid submission",
					"Unknown source type.", "manage", ""));
			return;
		}

		// Optional now-playing URL. Not a secret, so it is read straight from the form
		// (prefilled on edit) - blank clears it. validate() re-checks it (http/https, no
		// whitespace, <=512) before anything is stored. The PLATFORM and FILE forms have no
		// now-playing URL field (a platform page link has no SavePoint now-playing endpoint;
		// a finite file is not a live station), so never read it from the form for those:
		// preserve a hand-set discs.json value on edit, else none. Live sources (HLS/RTMP)
		// read it from the form (prefilled on edit; blank clears it).
		if ("platform".equals(source) || "file".equals(source)) {
			def.nowPlayingUrl = edit ? existing.nowPlayingUrl : null;
		} else {
			String nowPlayingUrl = tag.getStringOr("now_playing_url", "");
			def.nowPlayingUrl = nowPlayingUrl.isBlank() ? null : nowPlayingUrl;
		}

		// Optional per-disc marquee template. The HUD shows a templated marquee only for a
		// truly-live source, so only the live HLS/RTMP forms carry the field; the PLATFORM and
		// FILE forms omit it. For those, preserve a hand-set discs.json value on edit, else none.
		// Live sources read it from the form (prefilled on edit); blank clears it. validate()
		// re-checks length (<=128) and rejects control chars before anything is stored.
		if ("platform".equals(source) || "file".equals(source)) {
			def.nowPlayingTemplate = edit ? existing.nowPlayingTemplate : null;
		} else {
			String marqueeTemplate = tag.getStringOr("marquee_template", "");
			def.nowPlayingTemplate = marqueeTemplate.isBlank() ? null : marqueeTemplate;
		}

		// sow-012: Repeat. Only the FILE/PLATFORM forms submit it ("on"/"off"); other sources omit it →
		// default "on" = loop (which they ignore anyway). "off" → a finite source plays once and stops.
		def.repeat = !"off".equals(tag.getStringOr("repeat", "on"));

		// On create, refuse to clobber an existing disc. put() is create-or-overwrite,
		// so a reused id would silently destroy the stored disc (and its token/stream
		// key). Normalize the id exactly as applyDefaults() will so the lookup matches
		// the stored (trimmed, lowercased) key; overwrite stays reachable only via Edit.
		if (!edit) {
			String normalizedId = def.discId == null ? "" : def.discId.trim().toLowerCase(Locale.ROOT);
			if (DiscRegistry.get().get(normalizedId).isPresent()) {
				// Back to the SAME form with everything still filled in, not a blank one.
				DialogScreens.send(player, JockeyRadioDialogs.errorThenForm("Disc already exists",
						"A disc '" + safe(normalizedId) + "' already exists - use Manage to edit it.", tag.copy()));
				return;
			}
		}

		// put() re-applies defaults and re-validates before storing; a bad payload
		// yields an error string and nothing is written.
		Optional<String> error = DiscRegistry.get().put(def);
		if (error.isPresent()) {
			DialogScreens.send(player,
					JockeyRadioDialogs.errorThenForm("Could not save disc", error.get(), tag.copy()));
			return;
		}
		// Audit the mutation (never the token/streamKey - only the disc id + actor).
		RadioAudit.log(edit ? "edit" : "create", def.discId, RadioAudit.actor(player));
		// Land on the saved disc's detail page: its copyable connection info is
		// exactly the "result you can act on immediately" the redesign wants.
		DiscDefinition saved = DiscRegistry.get().get(def.discId).orElse(def);
		DialogScreens.send(player, JockeyRadioDialogs.discDetail(player.registryAccess(), saved,
				(edit ? "Updated '" : "Saved '") + def.discId + "'."));
	}

	// ------------------------------------------------------------------
	// preview (re-render the form with the disc-item preview refreshed)
	// ------------------------------------------------------------------

	/**
	 * Handles the create/edit form's "Preview" button. Parses the submitted
	 * inputs into a TRANSIENT {@link DiscDefinition} the same way {@link #handleSave}
	 * does, but leniently: nothing is validated, nothing is written, blank/invalid
	 * fields simply fall back to defaults so the preview can never error. The same
	 * form is re-sent with that transient def so its preview {@link ItemStack}
	 * refreshes and every input stays filled. The admin's typed token / stream key
	 * ride back in the transient def so they are not lost - into this admin's own
	 * client only (the W4 copy-button pattern), never logged and never in item NBT.
	 * Permission was already re-checked in {@link #handle} before dispatch.
	 */
	private static void handlePreview(ServerPlayer player, CompoundTag tag) {
		rerenderForm(player, tag, false, false, false);
	}

	/**
	 * Handles the "Name color: <name> ▶" cycler. Re-parses every current input
	 * exactly as {@link #handlePreview} does (so nothing typed is lost), then
	 * advances the colour - carried in the button payload - to the next palette
	 * entry and re-sends the same form. The advanced colour rides back into the
	 * form's Save/Preview/cycle button payloads, so Save persists exactly the colour
	 * the refreshed preview shows.
	 */
	private static void handleCycleColor(ServerPlayer player, CompoundTag tag) {
		rerenderForm(player, tag, true, false, false);
	}

	/**
	 * Handles the "Disc style: <name> ▶" cycler. Re-parses every current input exactly
	 * as {@link #handlePreview} does (so nothing typed is lost), then advances the base
	 * disc - carried in the button payload - to the next {@link JockeyRadioDialogs#STYLES}
	 * entry and re-sends the same form, refreshing the preview icon. The advanced style
	 * rides back into the form's Save/Preview/cycle payloads, so Save persists exactly
	 * the style the refreshed preview shows.
	 */
	private static void handleCycleStyle(ServerPlayer player, CompoundTag tag) {
		rerenderForm(player, tag, false, true, false);
	}

	/**
	 * Handles the "Name style: <name> ▶" cycler (sow-010). Re-parses every input like
	 * {@link #handlePreview}, then advances the display-name font style (carried in the payload)
	 * one {@link NameStyler} step and re-sends the same form so the preview caption restyles. The
	 * stored {@code name} stays plain; only the rendered form changes.
	 */
	private static void handleCycleNameStyle(ServerPlayer player, CompoundTag tag) {
		rerenderForm(player, tag, false, false, true);
	}

	/**
	 * Shared re-render for the Preview and Name-color-cycle buttons: builds a
	 * TRANSIENT {@link DiscDefinition} from the submitted inputs leniently (nothing
	 * validated, nothing written) and re-sends the same create/edit form with the
	 * preview refreshed and every input kept filled. When {@code advanceColor} is
	 * true the name colour (carried in the button payload) is advanced one palette
	 * step first, so the cycler visibly recolours the preview.
	 */
	private static void rerenderForm(ServerPlayer player, CompoundTag tag, boolean advanceColor, boolean advanceStyle,
			boolean advanceNameStyle) {
		String source = tag.getStringOr("source", "");
		String editId = tag.getStringOr("edit", "");
		boolean edit = !editId.isEmpty();

		DiscDefinition def = new DiscDefinition();
		def.discId = edit ? editId : tag.getStringOr("disc_id", "");
		def.name = tag.getStringOr("name", "");
		String color = tag.getStringOr("name_color", "#37C871");
		def.nameColorHex = advanceColor ? JockeyRadioDialogs.nextColorHex(color) : color;
		// The base disc (style) rides in the button payload, exactly like the colour.
		// Advance it one STYLES step when the style cycler was clicked, else keep the
		// current value so Preview / colour-cycle never lose the chosen style.
		String baseDisc = tag.getStringOr("base_disc", "");
		def.baseDisc = advanceStyle ? JockeyRadioDialogs.nextBaseDisc(baseDisc)
				: (baseDisc.isBlank() ? DiscDefinition.DEFAULT_BASE_DISC : baseDisc);
		// The name style (sow-010) rides in the payload too; advance it one step on the
		// name-style cycler, else keep it so the other buttons never lose it.
		String nameStyle = tag.getStringOr("name_style", "plain");
		def.nameStyle = advanceNameStyle ? NameStyler.nextStyle(nameStyle) : nameStyle;
		def.radiusBlocks = Math.round(tag.getFloatOr("radius_blocks", DiscDefinition.DEFAULT_RADIUS_BLOCKS));
		// Carry the entered now-playing URL + marquee template back so the round-trip
		// (Preview / colour-cycle / style-cycle) keeps them (all forms).
		def.nowPlayingUrl = tag.getStringOr("now_playing_url", "");
		def.nowPlayingTemplate = tag.getStringOr("marquee_template", "");
		// sow-012: carry Repeat back so a Preview/cycler click on the FILE/PLATFORM form keeps it.
		def.repeat = !"off".equals(tag.getStringOr("repeat", "on"));

		if ("rtmp_receive".equals(source)) {
			def.sourceType = DiscDefinition.SourceType.RTMP_RECEIVE;
			def.rtmpPort = parseInt(tag.getStringOr("rtmp_port", ""), -1);
			// Carry the entered key back so the round-trip does not lose / regenerate it.
			def.streamKey = tag.getStringOr("stream_key", "");
			String bindFallback = edit
					? DiscRegistry.get().get(editId)
							.map(existingDisc -> existingDisc.rtmpBind)
							.orElse(DiscDefinition.DEFAULT_RTMP_BIND)
					: DiscDefinition.NEW_RTMP_BIND;
			def.rtmpBind = tag.getStringOr("rtmp_bind", bindFallback);
			DialogScreens.send(player, JockeyRadioDialogs.createRtmpForm(player.registryAccess(), Optional.of(def), edit));
		} else if ("file".equals(source)) {
			def.sourceType = DiscDefinition.SourceType.FILE;
			// Carry the entered file URL back so the Preview / colour round-trip keeps it.
			def.streamUrl = tag.getStringOr("stream_url", "");
			DialogScreens.send(player, JockeyRadioDialogs.createFileForm(player.registryAccess(), Optional.of(def), edit));
		} else if ("platform".equals(source)) {
			def.sourceType = DiscDefinition.SourceType.PLATFORM;
			def.streamUrl = tag.getStringOr("stream_url", "");
			DialogScreens.send(player, JockeyRadioDialogs.createPlatformForm(player.registryAccess(), Optional.of(def), edit));
		} else if ("hls_pull".equals(source)) {
			def.sourceType = DiscDefinition.SourceType.HLS_PULL;
			// Carry the entered stream URL / token back so the round-trip keeps them.
			def.streamUrl = tag.getStringOr("stream_url", "");
			def.token = tag.getStringOr("token", "");
			DialogScreens.send(player, JockeyRadioDialogs.createPullForm(player.registryAccess(), Optional.of(def), edit));
		} else {
			// Unknown source: reopen the source chooser rather than erroring.
			DialogScreens.send(player, JockeyRadioDialogs.sourceChooser());
		}
	}

	// ------------------------------------------------------------------
	// delete / give / reload
	// ------------------------------------------------------------------

	private static void handleDelete(ServerPlayer player, CompoundTag tag) {
		String discId = tag.getStringOr("id", "");
		// Empty = actually removed AND persisted. A failed write or an unreadable registry
		// now reports why instead of claiming success for a disc that comes back on restart.
		Optional<String> failure = DiscRegistry.get().remove(discId);
		boolean removed = failure.isEmpty();
		if (removed) {
			RadioAudit.log("delete", discId, RadioAudit.actor(player));
		}
		String status;
		if (removed) {
			status = "Deleted '" + safe(discId) + "'.";
		} else if (DiscRegistry.NOT_FOUND_MARKER.equals(failure.get())) {
			// The only failure whose text embeds the client-supplied id; build it here so the id
			// is sanitised while the server-authored reasons below pass through in full.
			status = "No disc '" + safe(discId) + "' to delete.";
		} else {
			// Server-generated reason (read-only latch, write failure): do NOT run it through
			// safe(), whose 48-char cap for disc ids would cut off the recovery instruction.
			status = failure.get();
		}
		DialogScreens.send(player, JockeyRadioDialogs.manageList(player.registryAccess(), status, !removed));
	}

	// ------------------------------------------------------------------
	// test (audible monitor) / stop_monitor
	// ------------------------------------------------------------------

	/**
	 * Starts an on-demand AUDIBLE MONITOR for one saved disc: the admin hears the
	 * disc's feed privately (no jukebox) with a boss-bar level meter, via
	 * {@link MonitorService}. Everything here is on the server thread (past the
	 * mixin's {@code ensureRunningOnSameThread}); {@code MonitorService} keeps all
	 * feed/SVC work on daemon/sink threads and only touches the boss bar on the
	 * server thread. When voice chat is absent or the admin isn't connected to it,
	 * a clear notice is shown instead of starting - the monitor plays into voice
	 * chat, so there is nothing to hear without it. A cheap, non-blocking feed-state
	 * hint rides along as the reachability bonus.
	 */
	private static void handleTest(ServerPlayer player, CompoundTag tag) {
		String discId = tag.getStringOr("id", "");
		Optional<DiscDefinition> def = DiscRegistry.get().get(discId);
		if (def.isEmpty()) {
			DialogScreens.send(player, JockeyRadioDialogs.errorThen("Unknown disc",
					"No disc '" + safe(discId) + "'.", "manage", ""));
			return;
		}
		MonitorService.StartResult result = MonitorService.get().start(player, discId);
		switch (result) {
			case STARTED -> DialogScreens.send(player,
					JockeyRadioDialogs.monitorNotice(player.registryAccess(), def.get(), monitorHint(def.get())));
			case NO_VOICECHAT -> DialogScreens.send(player, JockeyRadioDialogs.noticeThenDetail(
					"Voice chat not available",
					"Simple Voice Chat isn't available on this server, so the feed can't be monitored here.",
					def.get().discId));
			case NO_CONNECTION -> DialogScreens.send(player, JockeyRadioDialogs.noticeThenDetail(
					"Voice chat not connected",
					"Connect to Simple Voice Chat first - the monitor plays into your voice chat, "
							+ "so you must be connected to hear it.",
					def.get().discId));
			case NO_DISC -> DialogScreens.send(player, JockeyRadioDialogs.errorThen("Unknown disc",
					"No disc '" + safe(discId) + "'.", "manage", ""));
			default -> DialogScreens.send(player, JockeyRadioDialogs.noticeThenDetail(
					"Monitor failed", "The monitor could not start - see the server log.", def.get().discId));
		}
	}

	/** Stops this admin's audible monitor and returns to the disc detail page. */
	private static void handleStopMonitor(ServerPlayer player, CompoundTag tag) {
		MonitorService.get().stop(player);
		String discId = tag.getStringOr("id", "");
		if (!discId.isEmpty() && DiscRegistry.get().get(discId).isPresent()) {
			DialogScreens.send(player, JockeyRadioDialogs.noticeThenDetail(
					"Monitor stopped", "Stopped monitoring - the level meter is gone.", discId));
		} else {
			DialogScreens.send(player, JockeyRadioDialogs.noticeThen(
					"Monitor stopped", "Stopped monitoring.", "manage"));
		}
	}

	/**
	 * A short, non-blocking reachability hint for the monitor notice, read from the
	 * shared feed state ({@link FeedRegistry}) - never a network probe. The stream
	 * key / token are never involved.
	 */
	private static String monitorHint(DiscDefinition def) {
		String key = "disc:" + def.discId;
		FeedRegistry registry = FeedRegistry.get();
		if (registry.feedIsStreaming(key) && registry.feedFramesRead(key) > 0) {
			return "Feed is receiving audio now.";
		}
		if (def.sourceType == DiscDefinition.SourceType.RTMP_RECEIVE) {
			return "Waiting for a broadcaster to publish to the RTMP listener - you'll hear silence until then.";
		}
		return "Waiting for the stream - if the station is idle you'll hear silence until it goes live.";
	}

	private static void handleGive(ServerPlayer player, CompoundTag tag) {
		String discId = tag.getStringOr("disc_id", "");
		// ret drives where the result notice returns: "detail" (from a disc page)
		// reopens that disc; anything else returns to the hub. sow-014: the hub give
		// menu is gone, so a non-detail error path lands on the manage list instead.
		boolean toDetail = "detail".equals(tag.getStringOr("ret", "root"));
		String errBack = toDetail ? "give_disc" : "manage";
		String errBackId = toDetail ? discId : "";

		Optional<DiscDefinition> def = DiscRegistry.get().get(discId);
		if (def.isEmpty()) {
			DialogScreens.send(player, JockeyRadioDialogs.errorThen("Unknown disc",
					"No disc '" + safe(discId) + "'.", errBack, errBackId));
			return;
		}

		// Optional player name gives to another ONLINE player; blank gives to self.
		ServerPlayer target = player;
		String requested = tag.getStringOr("player", "").trim();
		if (!requested.isEmpty()) {
			MinecraftServer server = player.level().getServer();
			ServerPlayer named = server == null ? null : server.getPlayerList().getPlayerByName(requested);
			if (named == null) {
				DialogScreens.send(player, JockeyRadioDialogs.errorThen("Player not online",
						"No online player '" + safe(requested) + "'.", errBack, errBackId));
				return;
			}
			target = named;
		}

		Optional<ItemStack> disc = RadioDiscFactory.createFromDefinition(target.registryAccess(), def.get());
		if (disc.isEmpty()) {
			DialogScreens.send(player, JockeyRadioDialogs.errorThen("Could not create disc",
					"The 'jockey_radio:radio' jukebox song is not loaded.", errBack, errBackId));
			return;
		}
		if (!target.addItem(disc.get())) {
			String whose = target == player ? "Your inventory is full."
					: safe(target.getName().getString()) + "'s inventory is full.";
			DialogScreens.send(player, JockeyRadioDialogs.errorThen("Inventory full", whose, errBack, errBackId));
			return;
		}
		RadioAudit.logGive(def.get().discId, RadioAudit.actor(player), RadioAudit.actor(target));
		String name = def.get().name != null ? def.get().displayName() : def.get().discId;
		String who = target == player ? "you" : safe(target.getName().getString());
		// Non-detail is only reachable from a stale/forged payload (giveForm always sets
		// ret=detail); land it on Manage to match the error path above.
		DialogScreens.send(player, JockeyRadioDialogs.successThen("Disc given",
				"Gave the '" + name + "' radio disc to " + who + ".",
				toDetail ? "detail" : "manage", toDetail ? discId : ""));
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	/** Re-checks the same op gate as {@code /jockey} against this player. */
	private static boolean hasAdminPermission(ServerPlayer player) {
		int level = RadioConfig.get().adminPermissionLevel;
		PermissionCheck check = new PermissionCheck.Require(
				new Permission.HasCommandLevel(PermissionLevel.byId(level)));
		return check.check(player.permissions());
	}

	/** The payload as a compound, or an empty one when absent/not a compound. */
	private static CompoundTag asCompound(Optional<Tag> payload) {
		if (payload.isPresent() && payload.get() instanceof CompoundTag compound) {
			return compound;
		}
		return new CompoundTag();
	}

	private static int parseInt(String value, int fallback) {
		try {
			return Integer.parseInt(value.trim());
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	/**
	 * Sanitises a client-supplied string before it is echoed into a dialog body:
	 * drops control characters and section signs and caps the length, so a
	 * hostile id can never inject apparent output into a notice.
	 */
	private static String safe(String value) {
		if (value == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < value.length() && sb.length() < 48; i++) {
			char c = value.charAt(i);
			if (c >= 0x20 && c != 0x7F && c != '§') {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	private DialogRouter() {
	}
}
