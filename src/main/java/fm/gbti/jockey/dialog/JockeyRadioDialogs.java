package fm.gbti.jockey.dialog;

import fm.gbti.jockey.Lang;
import fm.gbti.jockey.config.RadioConfig;
import fm.gbti.jockey.disc.DiscDefinition;
import fm.gbti.jockey.disc.DiscRegistry;
import fm.gbti.jockey.disc.LocalAudioServer;
import fm.gbti.jockey.disc.RadioDiscFactory;
import fm.gbti.jockey.session.RtmpEndpoints;
import fm.gbti.jockey.session.StationEndpoints;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.NoticeDialog;
import net.minecraft.server.dialog.action.Action;
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.ItemBody;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static fm.gbti.jockey.dialog.DialogWidgets.*;
import static fm.gbti.jockey.dialog.DiscStatusText.*;

/**
 * Pure factory of vanilla {@link Dialog} objects for the {@code /jockey}
 * admin UI (the sow-002 W4 redesign). No side effects, no registry writes -
 * every builder reads the live {@link DiscRegistry} snapshot and returns an
 * inline dialog for {@link DialogScreens#send}. Routing of the click actions
 * these dialogs emit lives in {@link DialogRouter}.
 *
 * <p>The flow the mockup specifies: a hub ({@link #rootMenu}) leads to a source
 * chooser ({@link #sourceChooser}) then a guided form; a browse list
 * ({@link #manageList}) drills into a per-disc page ({@link #discDetail}) that
 * is the only place Edit/Test/Give/Delete and the copyable connection info
 * live. Create is reachable ONLY from the hub or the browse list's "+ New disc"
 * button - never from a per-disc page.</p>
 *
 * <p>Every button carries a {@link CustomAll} nav/action whose {@code id}
 * namespace is {@code jockey_radio}; the {@link DialogRouter} dispatches on
 * the id path. The connection-info copy buttons are the one exception: they
 * carry a {@link StaticAction} wrapping a {@link ClickEvent.CopyToClipboard}
 * (handled client-side, no server round trip) - the real ingest URL / stream
 * key is serialized only into that one admin's ShowDialog packet, never logged
 * and never in item NBT.</p>
 *
 * <p>Stable chrome flows through {@link Lang#tr} (translation key + literal
 * English fallback, since this is a server mod pushing to vanilla clients).
 * Lines that interpolate a disc's untrusted {@code name} use
 * {@link Component#literal} concatenation, never a {@code %s} fallback format,
 * so a name containing {@code %} can never break formatting.</p>
 */
public final class JockeyRadioDialogs {

	// ------------------------------------------------------------------
	// Top-level menus
	// ------------------------------------------------------------------

	/** The hub: brand-green title, a disc icon, and five tooltip'd entry points. */
	public static Dialog rootMenu(HolderLookup.Provider registries) {
		List<DialogBody> body = new ArrayList<>();
		discIconBody(registries, brandDef(), 24).ifPresent(body::add);
		body.add(body(Lang.tr("jockey_radio.menu.body",
				"Create and manage streaming radio discs.")));

		// sow-014: a 3-item hub. Give lives inside Manage (each disc's detail page has a Give
		// button), and registry reload stays available as the /jockey reload console command -
		// neither earns a top-level slot.
		List<ActionButton> actions = List.of(
				navButton(Lang.tr("jockey_radio.menu.new", "+ New disc"),
						Lang.tr("jockey_radio.menu.new_tip", "Stream from a URL, or receive a broadcast"),
						"new_disc", 220),
				navButton(Lang.tr("jockey_radio.menu.manage", "Manage discs"),
						Lang.tr("jockey_radio.menu.manage_tip", "Browse, edit, connect, give"),
						"manage", 220),
				navButton(Lang.tr("jockey_radio.menu.close", "Close"),
						Lang.tr("jockey_radio.menu.close_tip", "Or press Esc"),
						"close", 220));
		CommonDialogData common = commonNone(brandTitle(
				Lang.tr("jockey_radio.menu.title", "Jockey Radio")), body, List.of());
		return new MultiActionDialog(common, actions, Optional.empty(), 1);
	}

	/** The "where does this disc's audio come from?" source chooser (drops "Pull"). */
	public static Dialog sourceChooser() {
		Component hls = Component.literal("").append(Lang.tr("jockey_radio.source.hls", "Stream from a URL"))
				.append(Component.literal(" "))
				.append(Component.literal("[HLS]").withStyle(s -> s.withColor(TextColor.fromRgb(HLS_COLOR))));
		Component rtmp = Component.literal("").append(Lang.tr("jockey_radio.source.rtmp", "Receive a broadcast"))
				.append(Component.literal(" "))
				.append(Component.literal("[RTMP]").withStyle(s -> s.withColor(TextColor.fromRgb(RTMP_COLOR))));
		Component file = Component.literal("").append(Lang.tr("jockey_radio.source.file", "Play an audio file"))
				.append(Component.literal(" "))
				.append(Component.literal("[FILE]").withStyle(s -> s.withColor(TextColor.fromRgb(FILE_COLOR))));
		Component platform = Component.literal("").append(Lang.tr("jockey_radio.source.platform", "Play a platform link"))
				.append(Component.literal(" "))
				.append(Component.literal("[LINK]").withStyle(s -> s.withColor(TextColor.fromRgb(PLATFORM_COLOR))));

		List<ActionButton> actions = new ArrayList<>();
		actions.add(navButton(hls, Lang.tr("jockey_radio.source.hls_tip",
				"The server plays a SavePoint station / any HLS stream"), "create_pull", 260));
		actions.add(navButton(rtmp, Lang.tr("jockey_radio.source.rtmp_tip",
				"A DJ / OBS / relay pushes audio into the server"), "create_rtmp", 260));
		actions.add(navButton(file, Lang.tr("jockey_radio.source.file_tip",
				"Loop a local audio file (a direct http link, or one from the audio folder)"), "create_file", 260));
		// sow-013: platform links are gated OFF by default. When disabled the source type is simply
		// not offered - the operator opts in by editing the config, which is the point of the gate.
		boolean platformEnabled = RadioConfig.get().enablePlatformLinks;
		if (platformEnabled) {
			actions.add(navButton(platform, Lang.tr("jockey_radio.source.platform_tip",
					"Paste a page link (resolved via yt-dlp)"),
					"create_platform", 260));
		}
		// sow-014: top-level surfaces close rather than bounce back to the hub.
		actions.add(navButton(Lang.tr("jockey_radio.button.close", "Close"), "close", 150));

		List<DialogBody> body = new ArrayList<>();
		body.add(body(Lang.tr("jockey_radio.source.body", "Where does this disc's audio come from?")));
		CommonDialogData common = commonNone(Lang.tr("jockey_radio.source.title", "New disc"),
				body, List.of());
		return new MultiActionDialog(common, actions, Optional.empty(), 1);
	}

	/**
	 * The HLS pull create/edit form. On edit the {@code disc_id} field is
	 * omitted (the id is immutable) and travels back in the submit action's
	 * {@code edit} addition; the access token is never pre-filled (blank on
	 * submit keeps the stored value).
	 */
	public static Dialog createPullForm(HolderLookup.Provider registries, Optional<DiscDefinition> values, boolean edit) {
		String editId = edit ? values.map(d -> d.discId).orElse("") : "";

		List<DialogBody> body = new ArrayList<>();
		// Preview of the disc item (base disc + RGB display name) reflecting the
		// entered values; on a fresh open it renders a default green "New disc".
		// A vanilla dialog is a static snapshot, so this refreshes on the Preview
		// round-trip, not as the admin types.
		String currentColor = pick(values, d -> d.nameColorHex, DEFAULT_COLOR);
		discIconBody(registries, previewDef(values), 24, previewCaption(previewDef(values))).ifPresent(body::add);
		body.add(edit
				? body(Component.literal("Editing this HLS stream disc."))
				: body(Lang.tr("jockey_radio.form.pull.body_create",
						"New stream disc. The server plays any stream URL: a SavePoint station, an m3u8 playlist or an Icecast/HTTP stream.")));
		body.add(body(colorNote()));
		// Token hint for the marquee field below (its label is kept short so the box centers).
		body.add(body(note("Marquee tokens: {station} {title} {artist} {segment}")));

		List<Input> inputs = new ArrayList<>();
		if (!edit) {
			inputs.add(new Input("disc_id",
					text(Lang.tr("jockey_radio.input.disc_id", "Disc ID (a-z0-9_-, 1..32)"),
							pick(values, d -> d.discId, ""), 32)));
		}
		inputs.add(new Input("name",
				text(Lang.tr("jockey_radio.input.name", "Display name"), pick(values, d -> d.name, ""), 48)));
		inputs.add(new Input("radius_blocks",
				radiusInput(values.map(d -> d.radiusBlocks).orElse(DiscDefinition.DEFAULT_RADIUS_BLOCKS))));
		inputs.add(new Input("stream_url",
				text(Lang.tr("jockey_radio.input.stream_url", "Stream URL (m3u8 or any stream)"),
						pick(values, d -> d.streamUrl, ""), 512)));
		// Blank on a fresh open (never pre-fills a stored token - the HLS token is
		// masked everywhere); a Preview round-trip carries the admin's typed token
		// back into this field, to that admin's own client only.
		inputs.add(new Input("token", text(edit
				? Lang.tr("jockey_radio.input.token_keep", "Access token (blank keeps current)")
				: Lang.tr("jockey_radio.input.token_optional", "Access token (optional)"),
				pick(values, d -> d.token, ""), 512)));
		if (edit) {
			inputs.add(new Input("token_action", tokenActionInput()));
		}
		// Optional override: now-playing is normally derived from the stream URL, but
		// a non-SavePoint stream (or a moved now-playing endpoint) can set it here.
		inputs.add(new Input("now_playing_url",
				text(Lang.tr("jockey_radio.input.now_playing_url", "Now Playing URL (optional)"),
						pick(values, d -> d.nowPlayingUrl, ""), 512)));
		// Per-disc on-air marquee layout (sow-006 review-feedback). Blank = the built-in
		// "♪ {station} — LIVE — {title} · {artist}". Placeholders: {station} {title}
		// {artist} {segment}; a missing field collapses its adjacent separator. Short
		// label on purpose - see the RTMP form's alignment note; tokens live in the body.
		inputs.add(new Input("marquee_template",
				text(Lang.tr("jockey_radio.input.marquee_template", "On-air marquee (blank = default)"),
						pick(values, d -> d.nowPlayingTemplate, ""), 128)));

		String currentStyle = pick(values, d -> d.baseDisc, DiscDefinition.DEFAULT_BASE_DISC);
		String currentNameStyle = pick(values, d -> d.nameStyle, "plain");
		ActionButton cycle = cycleColorButton(currentColor, currentStyle, currentNameStyle, "hls_pull", editId);
		ActionButton style = cycleStyleButton(currentStyle, currentColor, currentNameStyle, "hls_pull", editId);
		ActionButton nameStyle = cycleNameStyleButton(currentNameStyle, currentColor, currentStyle, "hls_pull", editId);
		ActionButton submit = saveButton(Lang.tr("jockey_radio.button.save", "Save disc"), "hls_pull", editId, currentColor, currentStyle, currentNameStyle);
		// sow-006 review-feedback: no Preview button - the preview refreshes on every form round-trip
		// (any cycler / Save-validation error re-render), so a dedicated Preview action is redundant.
		List<ActionButton> actions = List.of(cycle, style, nameStyle, submit, navButton(Lang.tr("jockey_radio.button.back", "Back"),
				edit ? "manage" : "new_disc", 150));
		CommonDialogData common = commonNone(edit
				? brandTitle(Lang.tr("jockey_radio.form.pull.title_edit", "Edit stream disc"))
				: Lang.tr("jockey_radio.form.pull.title_create", "New stream disc"), body, inputs);
		return new MultiActionDialog(common, actions, Optional.empty(), 1);
	}

	/**
	 * The FILE create/edit form (sow-009). Loops a finite audio file reached over
	 * HTTP - a direct link the admin pastes, or (P3) one from the audio folder.
	 * Mirrors the stream form minus the access token; {@code stream_url} carries the
	 * http/https file URL.
	 */
	public static Dialog createFileForm(HolderLookup.Provider registries, Optional<DiscDefinition> values, boolean edit) {
		String editId = edit ? values.map(d -> d.discId).orElse("") : "";

		List<DialogBody> body = new ArrayList<>();
		String currentColor = pick(values, d -> d.nameColorHex, DEFAULT_COLOR);
		discIconBody(registries, previewDef(values), 24, previewCaption(previewDef(values))).ifPresent(body::add);
		body.add(edit
				? body(Component.literal("Editing this file disc."))
				: body(Lang.tr("jockey_radio.form.file.body_create",
						"New file disc. The server loops an audio file reached over HTTP (a direct link, or a file in the audio folder).")));
		body.add(body(colorNote()));

		List<Input> inputs = new ArrayList<>();
		if (!edit) {
			inputs.add(new Input("disc_id",
					text(Lang.tr("jockey_radio.input.disc_id", "Disc ID (a-z0-9_-, 1..32)"),
							pick(values, d -> d.discId, ""), 32)));
		}
		inputs.add(new Input("name",
				text(Lang.tr("jockey_radio.input.name", "Display name"), pick(values, d -> d.name, ""), 48)));
		inputs.add(new Input("radius_blocks",
				radiusInput(values.map(d -> d.radiusBlocks).orElse(DiscDefinition.DEFAULT_RADIUS_BLOCKS))));
		// sow-012: loop the file (default) or play it once and stop like a vanilla disc.
		inputs.add(new Input("repeat", repeatInput(values.map(d -> d.repeat).orElse(true))));
		// A file disc has no marquee / now-playing URL (sow-006 review-feedback): a finite file has no
		// live now-playing endpoint and the HUD shows just the disc name for a non-live source, so those
		// fields are removed here. The audio URL is the LAST input so the "Browse audio folder" action
		// button renders directly beneath it.
		inputs.add(new Input("stream_url",
				text(Lang.tr("jockey_radio.input.file_url", "Audio file URL (http/https)"),
						pick(values, d -> d.streamUrl, ""), 512)));

		String currentStyle = pick(values, d -> d.baseDisc, DiscDefinition.DEFAULT_BASE_DISC);
		String currentNameStyle = pick(values, d -> d.nameStyle, "plain");
		ActionButton cycle = cycleColorButton(currentColor, currentStyle, currentNameStyle, "file", editId);
		ActionButton style = cycleStyleButton(currentStyle, currentColor, currentNameStyle, "file", editId);
		ActionButton nameStyle = cycleNameStyleButton(currentNameStyle, currentColor, currentStyle, "file", editId);
		ActionButton submit = saveButton(Lang.tr("jockey_radio.button.save", "Save disc"), "file", editId, currentColor, currentStyle, currentNameStyle);
		// "Browse audio folder" leads the actions so it sits right under the audio-file URL input.
		// It is a CustomAll, so it submits every typed input; the colour/style/name-style ride in
		// its payload too (they are button state, not form inputs), so the whole in-progress disc
		// survives the browse round-trip instead of coming back blank.
		List<ActionButton> actions = List.of(
				browseAudioButton(currentColor, currentStyle, currentNameStyle, editId),
				cycle, style, nameStyle, submit,
				navButton(Lang.tr("jockey_radio.button.back", "Back"), edit ? "manage" : "new_disc", 150));
		CommonDialogData common = commonNone(edit
				? brandTitle(Lang.tr("jockey_radio.form.file.title_edit", "Edit file disc"))
				: Lang.tr("jockey_radio.form.file.title_create", "New file disc"), body, inputs);
		return new MultiActionDialog(common, actions, Optional.empty(), 1);
	}

	/**
	 * sow-009 (P3): browse the audio folder ({@code config/jockey_radio/audio/}) served
	 * by {@link LocalAudioServer}. Each file opens a pre-filled {@link #createFileForm} whose
	 * {@code streamUrl} is the file's internal loopback URL. Empty state points the admin at
	 * the folder to drop files in.
	 */
	public static Dialog browseAudio(CompoundTag carry) {
		List<String> files = LocalAudioServer.get().listAudioFiles();
		int serverPort = LocalAudioServer.get().port();
		List<ActionButton> actions = new ArrayList<>();
		if (serverPort > 0) {
			for (String f : files) {
				// Each file button re-carries the in-progress form values (this dialog has no inputs
				// of its own, so without this the pick would land on a blank create form).
				actions.add(carryNavButton(
						Component.literal(f).withStyle(s -> s.withColor(TextColor.fromRgb(FILE_COLOR))),
						"pick_audio", f, 300, carry));
			}
		}
		// Back carries too, so cancelling the browse returns to the form still filled in.
		actions.add(carryNavButton(Lang.tr("jockey_radio.button.back", "Back"), "create_file", null, 150, carry));

		String bodyText;
		if (serverPort <= 0) {
			bodyText = "The audio server is off (localAudioServerPort). Enable it in jockey_radio.json "
					+ "or use a direct http link instead.";
		} else if (files.isEmpty()) {
			bodyText = "No audio files yet. Drop .mp3 / .ogg / .flac / .wav / .m4a into:\n"
					+ LocalAudioServer.audioDir();
		} else {
			bodyText = "Pick a file to loop (" + files.size() + " in "
					+ LocalAudioServer.audioDir() + "):";
		}
		CommonDialogData common = commonNone(brandTitle(Lang.tr("jockey_radio.form.file.browse_title", "Audio folder")),
				List.of(body(Component.literal(bodyText))), List.of());
		return new MultiActionDialog(common, actions, Optional.empty(), 1);
	}

	/**
	 * The PLATFORM create/edit form (sow-007). Plays a SoundCloud / Mixcloud / Bandcamp /
	 * Twitch / Kick page URL, resolved to audio by yt-dlp. Mirrors the stream form; the
	 * {@code stream_url} input holds the platform page link.
	 */
	public static Dialog createPlatformForm(HolderLookup.Provider registries, Optional<DiscDefinition> values, boolean edit) {
		String editId = edit ? values.map(d -> d.discId).orElse("") : "";

		List<DialogBody> body = new ArrayList<>();
		String currentColor = pick(values, d -> d.nameColorHex, DEFAULT_COLOR);
		discIconBody(registries, previewDef(values), 24, previewCaption(previewDef(values))).ifPresent(body::add);
		body.add(edit
				? body(Component.literal("Editing this platform-link disc."))
				: body(Lang.tr("jockey_radio.form.platform.body_create",
						"New platform disc. Paste a page link and yt-dlp resolves it to audio.")));
		body.add(body(colorNote()));

		List<Input> inputs = new ArrayList<>();
		if (!edit) {
			inputs.add(new Input("disc_id",
					text(Lang.tr("jockey_radio.input.disc_id", "Disc ID (a-z0-9_-, 1..32)"),
							pick(values, d -> d.discId, ""), 32)));
		}
		inputs.add(new Input("name",
				text(Lang.tr("jockey_radio.input.name", "Display name"), pick(values, d -> d.name, ""), 48)));
		inputs.add(new Input("radius_blocks",
				radiusInput(values.map(d -> d.radiusBlocks).orElse(DiscDefinition.DEFAULT_RADIUS_BLOCKS))));
		inputs.add(new Input("stream_url",
				text(Lang.tr("jockey_radio.input.platform_url", "Platform link (page URL)"),
						pick(values, d -> d.streamUrl, ""), 512)));
		// sow-012: a non-live VOD (e.g. a SoundCloud track) with repeat off plays once and stops; a live
		// stream (Twitch/Kick) keeps going regardless of this.
		inputs.add(new Input("repeat", repeatInput(values.map(d -> d.repeat).orElse(true))));
		// No Now-Playing URL or marquee field on platform discs (sow-006 review-feedback): a
		// platform page URL has no SavePoint now-playing endpoint (resolveNowPlayingUrl
		// can't derive one), and the HUD shows only the disc name unless the source is truly live, so a
		// marquee template would never render. Both fields stay on DiscDefinition for the rare
		// hand-edited discs.json case; handleSave preserves any stored value on a platform-disc edit.

		String currentStyle = pick(values, d -> d.baseDisc, DiscDefinition.DEFAULT_BASE_DISC);
		String currentNameStyle = pick(values, d -> d.nameStyle, "plain");
		ActionButton cycle = cycleColorButton(currentColor, currentStyle, currentNameStyle, "platform", editId);
		ActionButton style = cycleStyleButton(currentStyle, currentColor, currentNameStyle, "platform", editId);
		ActionButton nameStyle = cycleNameStyleButton(currentNameStyle, currentColor, currentStyle, "platform", editId);
		ActionButton submit = saveButton(Lang.tr("jockey_radio.button.save", "Save disc"), "platform", editId, currentColor, currentStyle, currentNameStyle);
		List<ActionButton> actions = List.of(cycle, style, nameStyle, submit, navButton(Lang.tr("jockey_radio.button.back", "Back"),
				edit ? "manage" : "new_disc", 150));
		CommonDialogData common = commonNone(edit
				? brandTitle(Lang.tr("jockey_radio.form.platform.title_edit", "Edit platform disc"))
				: Lang.tr("jockey_radio.form.platform.title_create", "New platform disc"), body, inputs);
		return new MultiActionDialog(common, actions, Optional.empty(), 1);
	}

	/**
	 * The RTMP receive create/edit form. On create the {@code stream_key} field
	 * is seeded with a fresh random key; on edit it is left blank (blank on
	 * submit keeps the stored key). {@code disc_id} is omitted on edit.
	 */
	public static Dialog createRtmpForm(HolderLookup.Provider registries, Optional<DiscDefinition> values, boolean edit) {
		String editId = edit ? values.map(d -> d.discId).orElse("") : "";

		List<DialogBody> body = new ArrayList<>();
		// Preview of the disc item (base disc + RGB display name) reflecting the
		// entered values; refreshed on the Preview round-trip (a vanilla dialog is
		// a static snapshot and cannot live-update as the admin types).
		String currentColor = pick(values, d -> d.nameColorHex, DEFAULT_COLOR);
		discIconBody(registries, previewDef(values), 24, previewCaption(previewDef(values))).ifPresent(body::add);
		body.add(edit
				? body(Component.literal("Editing this RTMP receive disc."))
				: body(Lang.tr("jockey_radio.form.rtmp.body_create",
						"New RTMP receive disc. A DJ, OBS or a relay publishes into the server.")));
		// The admin configuring SavePoint/OBS needs the ingest shape BEFORE saving; the exact
		// copyable URLs (with the real host and key) appear on the disc's detail page after save.
		if (!edit) {
			body.add(body(note("Broadcaster ingest will be rtmp://<this server>:<port below>/live "
					+ "plus the stream key (paste as SavePoint/OBS's RTMP URL + key). Exact copyable "
					+ "URLs appear on the disc page after saving.")));
		}
		body.add(body(colorNote()));
		// Token hint for the marquee field below (its label is kept short so the box centers).
		body.add(body(note("Marquee tokens: {station} {title} {artist} {segment}")));

		List<Input> inputs = new ArrayList<>();
		if (!edit) {
			inputs.add(new Input("disc_id",
					text(Lang.tr("jockey_radio.input.disc_id", "Disc ID (a-z0-9_-, 1..32)"),
							pick(values, d -> d.discId, ""), 32)));
		}
		inputs.add(new Input("name",
				text(Lang.tr("jockey_radio.input.name", "Display name"), pick(values, d -> d.name, ""), 48)));
		inputs.add(new Input("radius_blocks",
				radiusInput(values.map(d -> d.radiusBlocks).orElse(DiscDefinition.DEFAULT_RADIUS_BLOCKS))));
		// Seed the next FREE port, not a constant. Seeding 1936 every time meant a second
		// RTMP disc created through the UI silently collided with the first: it saved fine,
		// showed a copyable ingest URL, and then never bound (the keep-alive respawned it
		// against an in-use port), so the DJ's OBS was refused with nothing explaining why.
		String portInitial = values.map(d -> d.rtmpPort).filter(p -> p > 0).map(String::valueOf)
				.orElseGet(DialogWidgets::nextFreeRtmpPort);
		inputs.add(new Input("rtmp_port",
				text(Lang.tr("jockey_radio.input.rtmp_port", "RTMP port (1024..65535)"), portInitial, 5)));
		// Fresh create seeds a random key; a Preview round-trip carries the admin's
		// key back so it is not regenerated. Fresh edit is blank (blank keeps
		// current); a Preview round-trip on edit refills the typed key.
		inputs.add(new Input("stream_key", text(edit
				? Lang.tr("jockey_radio.input.stream_key_keep", "Stream key (blank keeps current)")
				: Lang.tr("jockey_radio.input.stream_key", "Stream key"),
				pick(values, d -> d.streamKey, edit ? "" : generateStreamKey()), 64)));
		// Managed hosting is the primary deployment: a new disc must accept the remote
		// SavePoint/OBS publisher. Edits preserve the stored bind, including legacy loopback.
		inputs.add(new Input("rtmp_bind",
				bindInput(pick(values, d -> d.rtmpBind, DiscDefinition.NEW_RTMP_BIND))));
		// Optional: an RTMP ingest carries no station identity, so paste the station's
		// now-playing URL here to show real track info on the bar (e.g.
		// http://127.0.0.1:7101/api/public/stations/2/now-playing - use 127.0.0.1, not
		// "localhost", which can resolve to IPv6 [::1] and time out). Not a secret.
		inputs.add(new Input("now_playing_url",
				text(Lang.tr("jockey_radio.input.now_playing_url", "Now Playing URL (optional)"),
						pick(values, d -> d.nowPlayingUrl, ""), 512)));
		// Per-disc on-air marquee layout (sow-006 review-feedback). Blank = the built-in
		// "♪ {station} — LIVE — {title} · {artist}". Placeholders: {station} {title}
		// {artist} {segment}; a missing field collapses its adjacent separator. The label
		// must stay narrower than the 220px input box or the field renders off-center
		// (the widget sizes to the label); the token list lives in the body note above.
		inputs.add(new Input("marquee_template",
				text(Lang.tr("jockey_radio.input.marquee_template", "On-air marquee (blank = default)"),
						pick(values, d -> d.nowPlayingTemplate, ""), 128)));

		String currentStyle = pick(values, d -> d.baseDisc, DiscDefinition.DEFAULT_BASE_DISC);
		String currentNameStyle = pick(values, d -> d.nameStyle, "plain");
		ActionButton cycle = cycleColorButton(currentColor, currentStyle, currentNameStyle, "rtmp_receive", editId);
		ActionButton style = cycleStyleButton(currentStyle, currentColor, currentNameStyle, "rtmp_receive", editId);
		ActionButton nameStyle = cycleNameStyleButton(currentNameStyle, currentColor, currentStyle, "rtmp_receive", editId);
		ActionButton submit = saveButton(Lang.tr("jockey_radio.button.save", "Save disc"), "rtmp_receive", editId, currentColor, currentStyle, currentNameStyle);
		List<ActionButton> actions = List.of(cycle, style, nameStyle, submit, navButton(Lang.tr("jockey_radio.button.back", "Back"),
				edit ? "manage" : "new_disc", 150));
		CommonDialogData common = commonNone(edit
				? brandTitle(Lang.tr("jockey_radio.form.rtmp.title_edit", "Edit RTMP disc"))
				: Lang.tr("jockey_radio.form.rtmp.title_create", "New RTMP disc"), body, inputs);
		return new MultiActionDialog(common, actions, Optional.empty(), 1);
	}

	/**
	 * The {@link DiscDefinition} rendered in a create/edit form's preview
	 * {@link ItemBody}: the entered values when present, else a default green
	 * "New disc". Only the fields {@link RadioDiscFactory#createFromDefinition}
	 * reads (id, name, colour, base disc) matter; blanks fall back so the preview
	 * always renders and never carries a secret (secrets are irrelevant to it).
	 */
	private static DiscDefinition previewDef(Optional<DiscDefinition> values) {
		DiscDefinition def = new DiscDefinition();
		def.baseDisc = pick(values, d -> d.baseDisc, DiscDefinition.DEFAULT_BASE_DISC);
		def.nameColorHex = pick(values, d -> d.nameColorHex, DEFAULT_COLOR);
		def.nameStyle = pick(values, d -> d.nameStyle, null); // sow-010: so the preview shows the style

		String enteredId = values.map(d -> d.discId).filter(s -> s != null && !s.isBlank()).orElse(null);
		String enteredName = values.map(d -> d.name).filter(s -> s != null && !s.isBlank()).orElse(null);
		def.discId = enteredId != null ? enteredId : "new_disc";
		def.name = enteredName != null ? enteredName : (enteredId != null ? enteredId : "New disc");
		return def;
	}

	/**
	 * The preview caption: the disc's display name rendered in its chosen
	 * {@code nameColorHex}. Vanilla music-disc icons are fixed textures and cannot
	 * be tinted, so the icon never changes colour; showing the NAME in the chosen
	 * colour is what makes "Name color" visible (and it updates live each time the
	 * colour is cycled, since the caption is rebuilt from the same value).
	 */
	private static Component previewCaption(DiscDefinition def) {
		int color = parseColorHex(def.nameColorHex, BRAND_GREEN);
		// sow-010: render the name in its chosen Unicode style so the preview reflects "Name style".
		return Component.literal(def.displayName()).withStyle(s -> s.withColor(TextColor.fromRgb(color)));
	}

	/**
	 * The browse list: one row per disc (icon + coloured name + source badge +
	 * live/idle status in the body, an "Open" button per disc in the actions),
	 * plus the single "+ New disc" create entry point and Back. {@code status}
	 * (may be {@code null}) renders as a leading body line after a save/delete.
	 */
	public static Dialog manageList(HolderLookup.Provider registries, String status) {
		return manageList(registries, status, false);
	}

	/**
	 * Browse list with an explicit status kind: {@code error=true} renders the
	 * leading status line in red (a "no such disc" / failed-delete message the
	 * router routes here), otherwise green (a completed save/delete). The spec
	 * requires errors red and successes green, so the router picks the kind.
	 */
	public static Dialog manageList(HolderLookup.Provider registries, String status, boolean error) {
		List<DiscDefinition> discs = DiscRegistry.get().list();

		List<DialogBody> body = new ArrayList<>();
		if (status != null && !status.isBlank()) {
			body.add(body(error ? error(status) : success(status)));
		}
		if (discs.isEmpty()) {
			body.add(body(Lang.tr("jockey_radio.manage.empty",
					"No discs yet. Use + New disc to add one.")));
		} else {
			body.add(body(Component.literal(discs.size()
					+ " disc(s). Click one to edit, test, connect, or give.")));
		}

		// sow-006 review-feedback: each disc is a single clickable ROW that opens its detail page -
		// the old separate icon-preview body line + "Open:" button were redundant (a vanilla dialog
		// body item is not clickable, so the row must be the button). The button label carries the
		// styled name + source badge + live/idle status that the preview line used to show.
		List<ActionButton> actions = new ArrayList<>();
		for (DiscDefinition d : discs) {
			actions.add(navIdButton(rowLabel(d), "detail", d.discId, 260));
		}
		actions.add(navButton(Lang.tr("jockey_radio.button.new_disc", "+ New disc"),
				Lang.tr("jockey_radio.menu.new_tip", "Stream from a URL, or receive a broadcast"),
				"new_disc", 220));
		// sow-014: top-level surfaces close rather than bounce back to the hub.
		actions.add(navButton(Lang.tr("jockey_radio.button.close", "Close"), "close", 220));

		CommonDialogData common = commonNone(brandTitle(
				Lang.tr("jockey_radio.manage.title", "Discs")), body, List.of());
		return new MultiActionDialog(common, actions, Optional.empty(), 1);
	}

	/**
	 * The per-disc detail page - the drill-down that replaces the old parallel
	 * buttons. Shows the disc icon, its source type + live/idle status, the
	 * copyable connection info (RTMP ingest URLs / stream key, or the resolved
	 * HLS source URL), and the Edit / Test / Give / Delete / Back actions. There
	 * is deliberately NO create button here.
	 */
	public static Dialog discDetail(HolderLookup.Provider registries, DiscDefinition def, String status) {
		return discDetail(registries, def, status, false);
	}

	/**
	 * Detail page with an explicit status kind: {@code error=true} renders the
	 * leading status line in red, otherwise green. Save success lands here green;
	 * the flag keeps the surface honest if an error status is ever routed here.
	 */
	public static Dialog discDetail(HolderLookup.Provider registries, DiscDefinition def, String status, boolean error) {
		boolean rtmp = def.sourceType == DiscDefinition.SourceType.RTMP_RECEIVE;
		int color = parseColorHex(def.nameColorHex, BRAND_GREEN);
		Component title = Component.literal(def.name != null ? def.displayName() : def.discId)
				.withStyle(s -> s.withColor(TextColor.fromRgb(color)));

		List<DialogBody> body = new ArrayList<>();
		if (status != null && !status.isBlank()) {
			body.add(body(error ? error(status) : success(status)));
		}
		discIconBody(registries, def, 28).ifPresent(body::add);
		body.add(body(Component.empty()
				.append(sourceBadge(def))
				.append(Component.literal("   "))
				.append(richStatusComponent(def.discId))));

		List<ActionButton> actions = new ArrayList<>();

		if (rtmp) {
			RtmpEndpoints.Ingest local = RtmpEndpoints.local(def);
			body.add(body(Component.literal("▸ Point your broadcaster (OBS / DJ / relay) here:")));
			body.add(connRow("Server", local.server()));
			body.add(connRow("Stream key", def.streamKey));
			body.add(connRow("Full URL", local.fullUrl()));
			actions.add(copyButton(Lang.tr("jockey_radio.copy.server", "Copy server"), local.server()));
			actions.add(copyButton(Lang.tr("jockey_radio.copy.key", "Copy stream key"), def.streamKey));
			actions.add(copyButton(Lang.tr("jockey_radio.copy.full", "Copy full URL"), local.fullUrl()));

			RadioConfig config = RadioConfig.get();
			if (RtmpEndpoints.bindsAllInterfaces(def)) {
				Optional<RtmpEndpoints.Ingest> ext = RtmpEndpoints.external(def, config);
				if (ext.isPresent()) {
					body.add(body(Component.literal("▸ External broadcasters (bind 0.0.0.0):")));
					body.add(connRow("Server", ext.get().server()));
					body.add(connRow("Full URL", ext.get().fullUrl()));
					actions.add(copyButton(Lang.tr("jockey_radio.copy.ext_server", "Copy external server"),
							ext.get().server()));
					actions.add(copyButton(Lang.tr("jockey_radio.copy.ext_full", "Copy external full URL"),
							ext.get().fullUrl()));
				} else {
					body.add(body(note("Binds 0.0.0.0 but no rtmpPublicHost is set. Set rtmpPublicHost "
							+ "in jockey_radio.json to also show an external ingest URL.")));
				}
			} else {
				// Not 0.0.0.0: the listener is bound to a specific interface (the
				// UI offers 127.0.0.1, but a hand-authored disc may bind e.g. a LAN
				// interface). Name the real bind so the shown ingest host matches.
				String bind = RtmpEndpoints.displayHost(def);
				body.add(body(note("Bind is " + bind + " (this interface only). For an external DJ, "
						+ "set bind 0.0.0.0, open the port, and set rtmpPublicHost.")));
			}
		} else {
			StationEndpoints.Endpoint endpoint = StationEndpoints.forDiscResolved(def, RadioConfig.get());
			// displayUrl has any token stripped - the ONLY form that may be shown.
			String shown = endpoint.displayUrl();
			body.add(body(Component.literal("▸ Streams audio from:")));
			body.add(connRow("Source", shown));
			actions.add(copyButton(Lang.tr("jockey_radio.copy.source", "Copy source URL"), shown));
			body.add(body(note(idleNote(def.sourceType))));
		}

		actions.add(navIdButton(Lang.tr("jockey_radio.button.edit", "Edit"), "edit", def.discId, 220));
		actions.add(testButton(Lang.tr("jockey_radio.button.test", "Test"), def.discId, 220));
		actions.add(navIdButton(Lang.tr("jockey_radio.button.give", "Give"), "give_disc", def.discId, 220));
		actions.add(navIdButton(Lang.tr("jockey_radio.button.delete", "Delete"), "delete_confirm", def.discId, 220));
		actions.add(navButton(Lang.tr("jockey_radio.button.back_to_discs", "Back to discs"), "manage", 220));

		CommonDialogData common = commonNone(title, body, List.of());
		return new MultiActionDialog(common, actions, Optional.empty(), 1);
	}

	/**
	 * The audible-monitor notice shown once the "Test" button has started a
	 * monitor: the disc icon, a "listen now" line, a cheap (non-blocking) feed-state
	 * hint, and the auto-stop window, with a Stop button
	 * ({@code jockey_radio:stop_monitor}) and a Back to the disc's detail page.
	 * Pressing Back leaves the monitor running (it still auto-stops on timeout /
	 * disconnect); only Stop tears it down immediately. Uses {@link DialogWidgets#commonNone} so
	 * the reply pushed after Stop stays on screen.
	 */
	public static Dialog monitorNotice(HolderLookup.Provider registries, DiscDefinition def, String hint) {
		int color = parseColorHex(def.nameColorHex, BRAND_GREEN);
		Component title = Component.literal("Monitoring ")
				.append(Component.literal(def.name != null ? def.displayName() : def.discId)
						.withStyle(s -> s.withColor(TextColor.fromRgb(color))));

		List<DialogBody> body = new ArrayList<>();
		discIconBody(registries, def, 24).ifPresent(body::add);
		body.add(body(Component.literal(
				"Listen now. The feed is playing into your voice chat, and a level meter shows on your boss bar.")));
		if (hint != null && !hint.isBlank()) {
			body.add(body(note(hint)));
		}
		body.add(body(note("Auto-stops after " + RadioConfig.get().monitorSeconds
				+ "s, when you disconnect, or when you press Stop.")));

		List<ActionButton> actions = List.of(
				stopMonitorButton(Lang.tr("jockey_radio.button.stop_monitor", "Stop monitoring"), def.discId, 220),
				navIdButton(Lang.tr("jockey_radio.button.back_to_disc", "Back to disc"), "detail", def.discId, 220));
		CommonDialogData common = commonNone(title, body, List.of());
		return new MultiActionDialog(common, actions, Optional.empty(), 1);
	}

	/**
	 * Confirmation before a destructive delete. Cancel is listed FIRST (the safe default the eye and
	 * a quick second click land on) and the red Delete action LAST, so a fast double-click coming off
	 * the detail page's "Delete" button can't fall straight through onto the destructive action
	 * (sow-006 review-feedback: "a follow-up confirm to prevent quick deletions").
	 */
	public static Dialog deleteConfirm(String discId, String name) {
		CompoundTag additions = new CompoundTag();
		additions.putString("id", discId);
		Action delete = new CustomAll(id("delete"), Optional.of(additions));
		ActionButton cancel = navIdButton(Lang.tr("jockey_radio.button.cancel", "Cancel"), "detail", discId, 220);
		ActionButton confirm = new ActionButton(
				new CommonButtonData(Lang.tr("jockey_radio.button.delete_confirm", "Delete permanently")
						.copy().withStyle(s -> s.withColor(TextColor.fromRgb(ERROR_COLOR))), 220),
				Optional.of(delete));
		CommonDialogData common = commonNone(Component.literal("Delete disc?")
						.withStyle(s -> s.withColor(TextColor.fromRgb(ERROR_COLOR))),
				List.of(body(Component.literal("Delete \"" + name + "\" (" + discId + ")? This cannot be undone."))),
				List.of());
		return new MultiActionDialog(common, List.of(cancel, confirm), Optional.empty(), 1);
	}

	/**
	 * The per-disc give form reached from a disc's detail page: gives THIS disc
	 * to the requesting player, or to an online player named in the optional
	 * text field. The disc id is baked into the submit action; the player name
	 * merges in from the input.
	 */
	public static Dialog giveForm(HolderLookup.Provider registries, DiscDefinition def) {
		int color = parseColorHex(def.nameColorHex, BRAND_GREEN);
		Component title = Component.literal("Give ")
				.append(Component.literal(def.name != null ? def.displayName() : def.discId)
						.withStyle(s -> s.withColor(TextColor.fromRgb(color))));

		List<DialogBody> body = new ArrayList<>();
		discIconBody(registries, def, 20).ifPresent(body::add);
		body.add(body(Component.literal("Give this disc to yourself, or type an online player's name.")));

		Input player = new Input("player",
				text(Lang.tr("jockey_radio.input.player", "Player (blank = you)"), "", 32));

		CompoundTag additions = new CompoundTag();
		additions.putString("disc_id", def.discId);
		additions.putString("ret", "detail");
		ActionButton give = new ActionButton(
				new CommonButtonData(Lang.tr("jockey_radio.button.give_disc", "Give disc"),
						Optional.of(Lang.tr("jockey_radio.give.tip", "Give to yourself, or the named player")), 220),
				Optional.of(new CustomAll(id("give"), Optional.of(additions))));
		ActionButton back = navIdButton(Lang.tr("jockey_radio.button.back", "Back"), "detail", def.discId, 220);
		CommonDialogData common = commonNone(title, body, List.of(player));
		return new MultiActionDialog(common, List.of(give, back), Optional.empty(), 1);
	}

	// ------------------------------------------------------------------
	// Notices
	// ------------------------------------------------------------------

	/** A terminal notice whose OK button simply closes the dialog. */
	public static Dialog notice(String title, String message) {
		return notice(Component.literal(title), Component.literal(message));
	}

	/** A terminal notice whose OK button simply closes the dialog. */
	public static Dialog notice(Component title, Component message) {
		CommonDialogData common = commonClose(title, List.of(body(message)), List.of());
		return new NoticeDialog(common, NoticeDialog.DEFAULT_ACTION);
	}

	/** A notice whose OK button navigates to {@code navTo} (a {@code nav} target). */
	public static Dialog noticeThen(String title, String message, String navTo) {
		return noticeThen(Component.literal(title), Component.literal(message), navTo);
	}

	/** A notice whose OK button navigates to {@code navTo} (a {@code nav} target). */
	public static Dialog noticeThen(Component title, Component message, String navTo) {
		return noticeThenNav(title, message, navTo, "");
	}

	/**
	 * A notice whose OK button navigates to {@code navTo}, carrying {@code navId}
	 * (a disc id) when non-empty so it can reopen e.g. a specific detail page.
	 */
	public static Dialog noticeThenNav(Component title, Component message, String navTo, String navId) {
		CommonDialogData common = commonNone(title, List.of(body(message)), List.of());
		ActionButton ok = navId == null || navId.isEmpty()
				? navButton(Lang.tr("jockey_radio.button.ok", "OK"), navTo, 150)
				: navIdButton(Lang.tr("jockey_radio.button.ok", "OK"), navTo, navId, 150);
		return new NoticeDialog(common, ok);
	}

	/** A green success notice returning to the given nav target (+ optional disc id). */
	public static Dialog successThen(String title, String message, String navTo, String navId) {
		return noticeThenNav(success(title), Component.literal(message), navTo, navId);
	}

	/** A red error notice returning to the given nav target (+ optional disc id). */
	public static Dialog errorThen(String title, String message, String navTo, String navId) {
		return noticeThenNav(error(title), Component.literal(message), navTo, navId);
	}

	/**
	 * A red error notice whose OK button REBUILDS the form the admin was filling in, with
	 * every submitted value intact.
	 *
	 * <p>A save-validation error used to send the admin to a bare {@code create_*} nav, so
	 * a single bad character in the disc id blanked the entire form - and on an RTMP form
	 * that also generated a NEW stream key, invalidating the one already handed to the
	 * broadcaster. The notice has no inputs of its own, so the submitted payload rides in
	 * the OK button exactly as the audio-browse round trip carries its values.</p>
	 */
	public static Dialog errorThenForm(String title, String message, CompoundTag submitted) {
		CommonDialogData common = commonNone(error(title), List.of(body(Component.literal(message))), List.of());
		return new NoticeDialog(common, carryNavButton(
				Lang.tr("jockey_radio.button.back_to_form", "Back to form"), "form_back", null, 150, submitted));
	}

	/** A test-result notice returning to the disc's detail page. */
	public static Dialog noticeThenDetail(String title, String message, String discId) {
		return noticeThenNav(Component.literal(title), Component.literal(message), "detail", discId);
	}

	private JockeyRadioDialogs() {
	}
}
