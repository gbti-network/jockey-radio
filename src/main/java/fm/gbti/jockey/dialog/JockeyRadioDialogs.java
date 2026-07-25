package fm.gbti.jockey.dialog;

import fm.gbti.jockey.Lang;
import fm.gbti.jockey.JockeyRadioMod;
import fm.gbti.jockey.config.RadioConfig;
import fm.gbti.jockey.disc.DiscDefinition;
import fm.gbti.jockey.disc.DiscRegistry;
import fm.gbti.jockey.disc.LocalAudioServer;
import fm.gbti.jockey.disc.NameStyler;
import fm.gbti.jockey.disc.RadioDiscFactory;
import fm.gbti.jockey.session.FeedRegistry;
import fm.gbti.jockey.session.RadioManager;
import fm.gbti.jockey.session.RadioSession;
import fm.gbti.jockey.session.RtmpEndpoints;
import fm.gbti.jockey.session.StationEndpoints;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.NoticeDialog;
import net.minecraft.server.dialog.action.Action;
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.ItemBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.NumberRangeInput;
import net.minecraft.server.dialog.input.SingleOptionInput;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

	/**
	 * Default RTMP port offered in the create form. Deliberately 1936, NOT the
	 * standard 1935: on a SavePoint host the local SavePoint server already owns
	 * 1935, so a mod RTMP-receive disc bound there can never listen (ffmpeg loops
	 * with "Address already in use"). 1936 is the known-free port.
	 */
	private static final String DEFAULT_RTMP_PORT = "1936";
	/** Brand default disc name colour (matches {@link DiscDefinition}). */
	private static final String DEFAULT_COLOR = "#37C871";

	/** Radio Green - the brand accent used on titles the mockup shows branded. */
	private static final int BRAND_GREEN = 0x37C871;
	/** Source badge colours from the mockup (mc-hls cyan / mc-rtmp gold / mc-file purple). */
	private static final int HLS_COLOR = 0x57E2E2;
	private static final int RTMP_COLOR = 0xFFB84D;
	private static final int FILE_COLOR = 0xC792EA;
	private static final int PLATFORM_COLOR = 0xF07178;
	/** Live/idle status colours from the mockup. */
	private static final int LIVE_COLOR = 0x54FB54;
	private static final int IDLE_COLOR = 0x9B9B9B;
	/** Result-notice colours (green success / red error). */
	private static final int SUCCESS_COLOR = 0x54FB54;
	private static final int ERROR_COLOR = 0xFF5555;
	/** Muted label / value colours for the connection-info rows. */
	private static final int LABEL_COLOR = 0xB9B9B9;
	private static final int VALUE_COLOR = 0xEAFFF2;

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final char[] KEY_ALPHABET =
			"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

	/**
	 * {@code {displayName, #RRGGBB}} - Radio Green is first (the brand
	 * default), the 16 vanilla text colours follow, and the legacy Radio Pink
	 * stays selectable so older discs round-trip on edit.
	 */
	private static final String[][] COLORS = {
			{"Radio Green", "#37C871"},
			{"White", "#FFFFFF"},
			{"Yellow", "#FFFF55"},
			{"Gold", "#FFAA00"},
			{"Red", "#FF5555"},
			{"Radio Pink", "#FF6BD5"},
			{"Light Purple", "#FF55FF"},
			{"Aqua", "#55FFFF"},
			{"Green", "#55FF55"},
			{"Blue", "#5555FF"},
			{"Gray", "#AAAAAA"},
			{"Dark Gray", "#555555"},
			{"Dark Red", "#AA0000"},
			{"Dark Purple", "#AA00AA"},
			{"Gold Dark", "#AA5500"},
			{"Dark Aqua", "#00AAAA"},
			{"Dark Green", "#00AA00"},
			{"Dark Blue", "#0000AA"},
			{"Black", "#000000"},
	};

	/**
	 * {@code {itemId, displayName}} - the disc "style" (the vanilla music-disc item
	 * the custom disc is based on, which sets its icon texture). {@code music_disc_13}
	 * is first (the {@link DiscDefinition#DEFAULT_BASE_DISC default}). Every entry is a
	 * stock 1.21-family disc; a server missing one still renders safely because
	 * {@link RadioDiscFactory#resolveBaseDisc} falls back to {@code music_disc_13}. A
	 * hand-authored off-list base disc stays selectable through edit round-trips (the
	 * cycler treats an unknown current value as "before the first entry").
	 */
	private static final String[][] STYLES = {
			{"minecraft:music_disc_13", "13"},
			{"minecraft:music_disc_cat", "Cat"},
			{"minecraft:music_disc_blocks", "Blocks"},
			{"minecraft:music_disc_chirp", "Chirp"},
			{"minecraft:music_disc_far", "Far"},
			{"minecraft:music_disc_mall", "Mall"},
			{"minecraft:music_disc_mellohi", "Mellohi"},
			{"minecraft:music_disc_stal", "Stal"},
			{"minecraft:music_disc_strad", "Strad"},
			{"minecraft:music_disc_ward", "Ward"},
			{"minecraft:music_disc_11", "11"},
			{"minecraft:music_disc_wait", "Wait"},
			{"minecraft:music_disc_otherside", "Otherside"},
			{"minecraft:music_disc_5", "5"},
			{"minecraft:music_disc_pigstep", "Pigstep"},
			{"minecraft:music_disc_relic", "Relic"},
			{"minecraft:music_disc_creator", "Creator"},
			{"minecraft:music_disc_creator_music_box", "Creator (Box)"},
			{"minecraft:music_disc_precipice", "Precipice"},
	};

	// ------------------------------------------------------------------
	// Top-level menus
	// ------------------------------------------------------------------

	/** The hub: brand-green title, a disc icon, and five tooltip'd entry points. */
	public static Dialog rootMenu(HolderLookup.Provider registries) {
		List<DialogBody> body = new ArrayList<>();
		discIconBody(registries, brandDef(), 24).ifPresent(body::add);
		body.add(body(Lang.tr("jockey_radio.menu.body",
				"Admin console - create and manage streaming radio discs.")));

		List<ActionButton> actions = List.of(
				navButton(Lang.tr("jockey_radio.menu.new", "+ New disc"),
						Lang.tr("jockey_radio.menu.new_tip", "Stream from a URL, or receive a broadcast"),
						"new_disc", 220),
				navButton(Lang.tr("jockey_radio.menu.manage", "Manage discs"),
						Lang.tr("jockey_radio.menu.manage_tip", "Browse, edit, connect, give"),
						"manage", 220),
				navButton(Lang.tr("jockey_radio.menu.give", "Give disc"),
						Lang.tr("jockey_radio.menu.give_tip", "Hand a disc to a player"),
						"give", 220),
				navButton(Lang.tr("jockey_radio.menu.reload", "Reload registry"),
						Lang.tr("jockey_radio.menu.reload_tip", "Re-read discs.json from disk"),
						"reload", 220),
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
					"Paste a SoundCloud / Mixcloud / Bandcamp / Twitch / Kick link (resolved via yt-dlp)"),
					"create_platform", 260));
		}
		actions.add(navButton(Lang.tr("jockey_radio.button.back", "Back"), "root", 150));

		List<DialogBody> body = new ArrayList<>();
		body.add(body(Lang.tr("jockey_radio.source.body", "Where does this disc's audio come from?")));
		if (!platformEnabled) {
			body.add(body(note("Platform links (SoundCloud / Mixcloud / Bandcamp / Twitch / …) are disabled. "
					+ "Enable enablePlatformLinks in jockey_radio.json only if you have the rights "
					+ "to rebroadcast that content on your server.")));
		}
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
						"New stream disc - the server plays any stream URL (a SavePoint station, an m3u8 playlist, an icecast/http stream, ...).")));
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
				text(Lang.tr("jockey_radio.input.stream_url", "Stream URL (m3u8 or any stream)"),
						pick(values, d -> d.streamUrl, ""), 512)));
		// Blank on a fresh open (never pre-fills a stored token - the HLS token is
		// masked everywhere); a Preview round-trip carries the admin's typed token
		// back into this field, to that admin's own client only.
		inputs.add(new Input("token", text(edit
				? Lang.tr("jockey_radio.input.token_keep", "Access token (blank keeps current)")
				: Lang.tr("jockey_radio.input.token_optional", "Access token (optional)"),
				pick(values, d -> d.token, ""), 512)));
		// Optional override: now-playing is normally derived from the stream URL, but
		// a non-SavePoint stream (or a moved now-playing endpoint) can set it here.
		inputs.add(new Input("now_playing_url",
				text(Lang.tr("jockey_radio.input.now_playing_url", "Now Playing URL (optional)"),
						pick(values, d -> d.nowPlayingUrl, ""), 512)));
		// Per-disc on-air marquee layout (sow-006 review-feedback). Blank = the built-in
		// "♪ {station} — LIVE — {title} · {artist}". Placeholders: {station} {title}
		// {artist} {segment}; a missing field collapses its adjacent separator.
		inputs.add(new Input("marquee_template",
				text(Lang.tr("jockey_radio.input.marquee_template",
						"Marquee: {station}/{title}/{artist}/{segment} (blank=default)"),
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
				: Lang.tr("jockey_radio.form.pull.title_create", "New HLS disc"), body, inputs);
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
						"New file disc - the server loops a finite audio file reached over HTTP (a direct link, or a file in the audio folder).")));
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
						"New platform disc - paste a SoundCloud / Mixcloud / Bandcamp / Twitch / Kick link; yt-dlp resolves it to audio.")));
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
				text(Lang.tr("jockey_radio.input.platform_url", "Platform link (SoundCloud / Mixcloud / Twitch / ...)"),
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
						"New RTMP receive disc - a DJ/OBS/relay publishes into the server.")));
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
		// Seed the next FREE port, not a constant. Seeding 1936 every time meant a second
		// RTMP disc created through the UI silently collided with the first: it saved fine,
		// showed a copyable ingest URL, and then never bound (the keep-alive respawned it
		// against an in-use port), so the DJ's OBS was refused with nothing explaining why.
		String portInitial = values.map(d -> d.rtmpPort).filter(p -> p > 0).map(String::valueOf)
				.orElseGet(JockeyRadioDialogs::nextFreeRtmpPort);
		inputs.add(new Input("rtmp_port",
				text(Lang.tr("jockey_radio.input.rtmp_port", "RTMP port (1024..65535)"), portInitial, 5)));
		// Fresh create seeds a random key; a Preview round-trip carries the admin's
		// key back so it is not regenerated. Fresh edit is blank (blank keeps
		// current); a Preview round-trip on edit refills the typed key.
		inputs.add(new Input("stream_key", text(edit
				? Lang.tr("jockey_radio.input.stream_key_keep", "Stream key (blank keeps current)")
				: Lang.tr("jockey_radio.input.stream_key", "Stream key"),
				pick(values, d -> d.streamKey, edit ? "" : generateStreamKey()), 64)));
		inputs.add(new Input("rtmp_bind", bindInput(pick(values, d -> d.rtmpBind, DiscDefinition.DEFAULT_RTMP_BIND))));
		// Optional: an RTMP ingest carries no station identity, so paste the station's
		// now-playing URL here to show real track info on the bar (e.g.
		// http://127.0.0.1:7101/api/public/stations/2/now-playing - use 127.0.0.1, not
		// "localhost", which can resolve to IPv6 [::1] and time out). Not a secret.
		inputs.add(new Input("now_playing_url",
				text(Lang.tr("jockey_radio.input.now_playing_url", "Now Playing URL (optional)"),
						pick(values, d -> d.nowPlayingUrl, ""), 512)));
		// Per-disc on-air marquee layout (sow-006 review-feedback). Blank = the built-in
		// "♪ {station} — LIVE — {title} · {artist}". Placeholders: {station} {title}
		// {artist} {segment}; a missing field collapses its adjacent separator.
		inputs.add(new Input("marquee_template",
				text(Lang.tr("jockey_radio.input.marquee_template",
						"Marquee: {station}/{title}/{artist}/{segment} (blank=default)"),
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
					"No discs yet - use + New disc to add one.")));
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
		actions.add(navButton(Lang.tr("jockey_radio.button.back", "Back"), "root", 220));

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
					body.add(body(note("Binds 0.0.0.0 but no rtmpPublicHost is set - set rtmpPublicHost "
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
			body.add(body(note("Idle means the station isn't broadcasting yet (a status, not an error). "
					+ "Any token stays masked and never appears in this URL.")));
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
	 * disconnect); only Stop tears it down immediately. Uses {@link #commonNone} so
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
				"Listen now - the feed is playing into your voice chat. A level meter shows on your boss bar.")));
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

	/** Give menu (from the hub): choose a disc, optionally name a player, receive a copy. */
	public static Dialog giveMenu(HolderLookup.Provider registries) {
		List<DiscDefinition> discs = DiscRegistry.get().list();
		if (discs.isEmpty()) {
			return noticeThen(Lang.tr("jockey_radio.give.title", "Give disc"),
					Lang.tr("jockey_radio.give.empty", "No discs to give yet - create one first."), "root");
		}
		List<SingleOptionInput.Entry> entries = new ArrayList<>();
		boolean first = true;
		for (DiscDefinition d : discs) {
			entries.add(new SingleOptionInput.Entry(d.discId,
					Optional.of(Component.literal(d.discId + " - " + d.name)), first));
			first = false;
		}
		Input choice = new Input("disc_id",
				new SingleOptionInput(220, entries, Lang.tr("jockey_radio.give.choice", "Disc"), true));
		Input player = new Input("player",
				text(Lang.tr("jockey_radio.input.player", "Player (blank = you)"), "", 32));

		CompoundTag additions = new CompoundTag();
		additions.putString("ret", "root");
		ActionButton give = new ActionButton(
				new CommonButtonData(Lang.tr("jockey_radio.button.give", "Give disc"),
						Optional.of(Lang.tr("jockey_radio.give.tip", "Give to yourself, or the named player")), 150),
				Optional.of(new CustomAll(id("give"), Optional.of(additions))));
		List<ActionButton> actions = List.of(give, navButton(Lang.tr("jockey_radio.button.back", "Back"), "root", 150));
		CommonDialogData common = commonNone(brandTitle(Lang.tr("jockey_radio.give.title", "Give disc")),
				List.of(body(Lang.tr("jockey_radio.give.body", "Give a radio disc to a player."))),
				List.of(choice, player));
		return new MultiActionDialog(common, actions, Optional.empty(), 1);
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
				new CommonButtonData(Lang.tr("jockey_radio.button.give", "Give disc"),
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

	// ------------------------------------------------------------------
	// Building blocks
	// ------------------------------------------------------------------

	private static CommonDialogData commonNone(Component title, List<DialogBody> body, List<Input> inputs) {
		// pause=false is mandatory for a server mod; afterAction=NONE keeps the
		// dialog open on submit so the handler can push an updated one in reply -
		// and, crucially, keeps a copy-to-clipboard dialog on screen after a copy.
		return new CommonDialogData(title, Optional.empty(),
				true, false, DialogAction.NONE, body, inputs);
	}

	private static CommonDialogData commonClose(Component title, List<DialogBody> body, List<Input> inputs) {
		return new CommonDialogData(title, Optional.empty(),
				true, false, DialogAction.CLOSE, body, inputs);
	}

	private static DialogBody body(String text) {
		return body(Component.literal(text));
	}

	private static DialogBody body(Component text) {
		return new PlainMessage(text, 320);
	}

	/** A brand-green title component. */
	private static MutableComponent brandTitle(MutableComponent title) {
		return title.withStyle(s -> s.withColor(TextColor.fromRgb(BRAND_GREEN)));
	}

	private static Component success(String text) {
		return Component.literal(text).withStyle(s -> s.withColor(TextColor.fromRgb(SUCCESS_COLOR)));
	}

	private static Component error(String text) {
		return Component.literal(text).withStyle(s -> s.withColor(TextColor.fromRgb(ERROR_COLOR)));
	}

	private static Component note(String text) {
		return Component.literal(text).withStyle(s -> s.withColor(TextColor.fromRgb(LABEL_COLOR)));
	}

	/** A connection-info row: a muted label and the real value shown for copying. */
	private static DialogBody connRow(String label, String value) {
		return body(Component.literal(label + ":  ").withStyle(s -> s.withColor(TextColor.fromRgb(LABEL_COLOR)))
				.append(Component.literal(value == null ? "" : value)
						.withStyle(s -> s.withColor(TextColor.fromRgb(VALUE_COLOR)))));
	}

	/** The coloured "[HLS]" / "[RTMP]" source badge. */
	private static Component sourceBadge(DiscDefinition def) {
		// One badge per source type - FILE/PLATFORM were added after the original two-way check, which
		// left every non-RTMP disc reading "[HLS]". Map all four.
		String label = switch (def.sourceType) {
			case RTMP_RECEIVE -> "[RTMP]";
			case FILE -> "[FILE]";
			case PLATFORM -> "[LINK]";
			default -> "[HLS]"; // HLS_PULL
		};
		int color = switch (def.sourceType) {
			case RTMP_RECEIVE -> RTMP_COLOR;
			case FILE -> FILE_COLOR;
			case PLATFORM -> PLATFORM_COLOR;
			default -> HLS_COLOR;
		};
		return Component.literal(label).withStyle(s -> s.withColor(TextColor.fromRgb(color)));
	}

	/**
	 * Compact live/idle badge for a browse row. Prefers the now-playing snapshot
	 * when the (opt-in) poller has one for this disc - live green, idle gray -
	 * else falls back to the shared feed state (the original, poller-off behavior).
	 */
	private static Component statusComponent(String discId) {
		RadioSession.NowPlaying np = nowPlayingFor(discId);
		if (np == null) {
			return feedStatusComponent(discId);
		}
		return np.live()
				? Component.literal("● live").withStyle(s -> s.withColor(TextColor.fromRgb(LIVE_COLOR)))
				: Component.literal("○ idle").withStyle(s -> s.withColor(TextColor.fromRgb(IDLE_COLOR)));
	}

	/**
	 * The rich now-playing line for the disc detail page. With a live now-playing
	 * snapshot: {@code ● LIVE - <title> - <artist>} (green) plus a muted
	 * {@code (<segment> - live DJ)} for a relay/master-key takeover or
	 * {@code (scheduled)} for the master schedule. A not-live snapshot renders
	 * {@code ○ idle}. With no snapshot (poller off, an RTMP disc, the disc not
	 * placed in a jukebox, or not yet polled) it falls back to the shared feed-state
	 * badge, unchanged.
	 */
	private static Component richStatusComponent(String discId) {
		RadioSession.NowPlaying np = nowPlayingFor(discId);
		if (np == null) {
			return feedStatusComponent(discId);
		}
		if (!np.live()) {
			return Component.literal("○ idle").withStyle(s -> s.withColor(TextColor.fromRgb(IDLE_COLOR)));
		}
		MutableComponent line = Component.literal("● LIVE")
				.withStyle(s -> s.withColor(TextColor.fromRgb(LIVE_COLOR)));
		if (np.title() != null) {
			StringBuilder track = new StringBuilder(" - ").append(np.title());
			if (np.artist() != null) {
				track.append(" - ").append(np.artist());
			}
			// Literal concatenation (never a %s format): an untrusted title/artist
			// containing '%' can never break formatting.
			line.append(Component.literal(track.toString())
					.withStyle(s -> s.withColor(TextColor.fromRgb(LIVE_COLOR))));
		}
		String segment = segmentSuffix(np);
		if (segment != null) {
			line.append(Component.literal("  ")).append(note("(" + segment + ")"));
		}
		return line;
	}

	/**
	 * The muted parenthetical after a live now-playing line: the segment name (when
	 * present) tagged {@code live DJ} for a relay/master-key broadcast source or
	 * {@code scheduled} for the {@code master} source. Null when there is nothing to
	 * say (no segment and an unknown/absent source).
	 */
	private static String segmentSuffix(RadioSession.NowPlaying np) {
		String source = np.source();
		String tag;
		if ("relay".equalsIgnoreCase(source) || "master-key".equalsIgnoreCase(source)) {
			tag = "live DJ";
		} else if ("master".equalsIgnoreCase(source)) {
			tag = "scheduled";
		} else {
			tag = null;
		}
		String segment = np.segment();
		if (segment != null && tag != null) {
			return segment + " - " + tag;
		}
		return segment != null ? segment : tag;
	}

	/** Live/idle status from the shared feed state (live = a real feed is receiving audio). */
	private static Component feedStatusComponent(String discId) {
		FeedRegistry registry = FeedRegistry.get();
		String key = "disc:" + discId;
		boolean live = registry.feedIsStreaming(key) && registry.feedFramesRead(key) > 0;
		return live
				? Component.literal("● live").withStyle(s -> s.withColor(TextColor.fromRgb(LIVE_COLOR)))
				: Component.literal("● idle").withStyle(s -> s.withColor(TextColor.fromRgb(IDLE_COLOR)));
	}

	/**
	 * The now-playing snapshot for the session currently playing this admin disc,
	 * or null when there is no such session or the (opt-in) poller has never
	 * reported on it. A server-thread lookup done at dialog-build time; never blocks
	 * (reads only volatiles) and never exposes any token.
	 */
	private static RadioSession.NowPlaying nowPlayingFor(String discId) {
		return RadioManager.get().sessionForDisc(discId)
				.map(RadioSession::nowPlaying)
				.orElse(null);
	}

	/** The browse-row caption: coloured (styled) name + source badge + status. */
	private static Component rowLabel(DiscDefinition d) {
		return Component.empty()
				.append(Component.literal(d.name != null ? d.displayName() : d.discId)
						.withStyle(s -> s.withColor(TextColor.fromRgb(parseColorHex(d.nameColorHex, BRAND_GREEN)))))
				.append(Component.literal("  "))
				.append(sourceBadge(d))
				.append(Component.literal("  "))
				.append(statusComponent(d.discId));
	}

	private static Optional<DialogBody> discIconBody(HolderLookup.Provider registries, DiscDefinition def, int size) {
		return discIconBody(registries, def, size, null);
	}

	/**
	 * An {@link ItemBody} rendering the disc's actual item (id + coloured name +
	 * jukebox song, verbatim from {@link RadioDiscFactory}), optionally captioned.
	 * Empty when the {@code jockey_radio:radio} jukebox song is not loaded - the
	 * caller simply omits the icon rather than failing the whole dialog.
	 */
	private static Optional<DialogBody> discIconBody(HolderLookup.Provider registries, DiscDefinition def,
			int size, Component caption) {
		Optional<ItemStack> disc = RadioDiscFactory.createFromDefinition(registries, def);
		if (disc.isEmpty()) {
			return Optional.empty();
		}
		ItemStackTemplate template = ItemStackTemplate.fromNonEmptyStack(disc.get());
		Optional<PlainMessage> description = caption == null
				? Optional.empty()
				: Optional.of(new PlainMessage(caption, 260));
		return Optional.of(new ItemBody(template, description, true, true, size, size));
	}

	/** A synthetic brand disc used only for the hub header icon. */
	private static DiscDefinition brandDef() {
		DiscDefinition def = new DiscDefinition();
		def.discId = "savepoint";
		def.name = "Jockey Radio";
		def.nameColorHex = DEFAULT_COLOR;
		def.baseDisc = DiscDefinition.DEFAULT_BASE_DISC;
		return def;
	}

	private static TextInput text(Component label, String initial, int maxLength) {
		String safe = initial == null ? "" : initial;
		// The TextInput codec rejects initial longer than maxLength on serialize;
		// registry values already fit, but truncate defensively so a bad value can
		// never turn a dialog send into a crash.
		if (safe.length() > maxLength) {
			safe = safe.substring(0, maxLength);
		}
		return new TextInput(220, label, true, safe, maxLength, Optional.empty());
	}

	/**
	 * A radius slider (NumberRangeInput) over 1..512 blocks, step 1. The range
	 * matches {@link DiscDefinition#validate}'s accepted bound so an edit of a
	 * hand-authored disc with a large radius round-trips instead of being clamped
	 * down on save.
	 */
	private static NumberRangeInput radiusInput(int initial) {
		float init = Math.max(1f, Math.min(512f, initial));
		NumberRangeInput.RangeInfo range = new NumberRangeInput.RangeInfo(
				1f, 512f, Optional.of(init), Optional.of(1f));
		return new NumberRangeInput(220,
				Lang.tr("jockey_radio.input.radius", "Audible radius (blocks)"),
				"options.generic_value", range);
	}

	private static SingleOptionInput bindInput(String selected) {
		boolean any = "0.0.0.0".equals(selected);
		boolean loopback = selected == null || selected.isBlank() || "127.0.0.1".equals(selected);
		// A hand-authored disc may bind a concrete interface (e.g. 10.0.0.5), which the rest
		// of the mod fully supports. Offering only the two well-known binds meant such a value
		// was not shown, could not be re-selected, and was silently rewritten to loopback by
		// the next edit - killing remote ingest with no warning. So an off-list bind is added
		// as a third entry and pre-selected, exactly as an off-palette colour and an off-list
		// base disc already round-trip on these forms.
		List<SingleOptionInput.Entry> entries = new ArrayList<>();
		if (!any && !loopback) {
			entries.add(new SingleOptionInput.Entry(selected,
					Optional.of(Component.literal(selected + " (custom)")), true));
		}
		entries.add(new SingleOptionInput.Entry("127.0.0.1",
				Optional.of(Component.literal("127.0.0.1 (local only)")), loopback));
		entries.add(new SingleOptionInput.Entry("0.0.0.0",
				Optional.of(Component.literal("0.0.0.0 (all interfaces)")), any));
		return new SingleOptionInput(220, List.copyOf(entries),
				Lang.tr("jockey_radio.input.rtmp_bind", "RTMP bind"), true);
	}

	/**
	 * sow-012: a Repeat On/Off selector for FILE / PLATFORM discs. {@code on} = loop forever (default);
	 * {@code off} = play once then stop like a vanilla disc (finite sources only - a live platform stream
	 * keeps going regardless). Submits its selected entry key ({@code on}/{@code off}) like {@code bindInput}.
	 */
	private static SingleOptionInput repeatInput(boolean repeat) {
		List<SingleOptionInput.Entry> entries = List.of(
				new SingleOptionInput.Entry("on",
						Optional.of(Component.literal("Repeat (loop)")), repeat),
				new SingleOptionInput.Entry("off",
						Optional.of(Component.literal("Play once, then stop")), !repeat));
		return new SingleOptionInput(220, entries, Lang.tr("jockey_radio.input.repeat", "Repeat"), true);
	}

	private static ActionButton navButton(Component label, String to, int width) {
		return navButton(label, null, to, width);
	}

	private static ActionButton navButton(Component label, Component tooltip, String to, int width) {
		CompoundTag additions = new CompoundTag();
		additions.putString("to", to);
		Action action = new CustomAll(id("nav"), Optional.of(additions));
		CommonButtonData data = tooltip == null
				? new CommonButtonData(label, width)
				: new CommonButtonData(label, Optional.of(tooltip), width);
		return new ActionButton(data, Optional.of(action));
	}

	private static ActionButton navIdButton(Component label, String to, String discId, int width) {
		CompoundTag additions = new CompoundTag();
		additions.putString("to", to);
		additions.putString("id", discId);
		Action action = new CustomAll(id("nav"), Optional.of(additions));
		return new ActionButton(new CommonButtonData(label, width), Optional.of(action));
	}

	/**
	 * sow-009 fix: the FILE form's "Browse audio folder" action. A {@link CustomAll} (so every typed
	 * input is submitted) whose payload also carries the colour / base disc / name style - those are
	 * button state rather than form inputs, so they would otherwise be lost across the browse trip.
	 */
	private static ActionButton browseAudioButton(String nameColorHex, String baseDisc, String nameStyle,
			String editId) {
		CompoundTag additions = new CompoundTag();
		additions.putString("to", "browse_audio");
		// The edit id has to ride along like every other form button carries it. Without it,
		// browsing (or cancelling the browse) while EDITING a file disc came back as a blank
		// CREATE form: the id input reappeared empty, and saving either collided with the
		// existing disc or silently created a second one while the original kept its old URL.
		additions.putString("edit", editId == null ? "" : editId);
		additions.putString("name_color", nameColorHex == null || nameColorHex.isBlank() ? DEFAULT_COLOR : nameColorHex);
		additions.putString("base_disc",
				baseDisc == null || baseDisc.isBlank() ? DiscDefinition.DEFAULT_BASE_DISC : baseDisc);
		additions.putString("name_style", nameStyle == null || nameStyle.isBlank() ? "plain" : nameStyle);
		Action action = new CustomAll(id("nav"), Optional.of(additions));
		return new ActionButton(new CommonButtonData(
				Lang.tr("jockey_radio.button.browse_audio", "Browse audio folder"), 220), Optional.of(action));
	}

	/**
	 * A nav button that re-emits an in-progress form's carried values (sow-009 browse round-trip).
	 * The audio-browse dialog has no inputs of its own, so the values must ride in each button's
	 * payload to survive back to the create form.
	 */
	private static ActionButton carryNavButton(Component label, String to, String discId, int width,
			CompoundTag carry) {
		CompoundTag additions = carry == null ? new CompoundTag() : carry.copy();
		additions.putString("to", to);
		if (discId != null) {
			additions.putString("id", discId);
		}
		Action action = new CustomAll(id("nav"), Optional.of(additions));
		return new ActionButton(new CommonButtonData(label, width), Optional.of(action));
	}

	/** A per-disc "Test feed" button - emits {@code jockey_radio:test {id}}. */
	private static ActionButton testButton(Component label, String discId, int width) {
		CompoundTag additions = new CompoundTag();
		additions.putString("id", discId);
		Action action = new CustomAll(id("test"), Optional.of(additions));
		return new ActionButton(new CommonButtonData(label, width), Optional.of(action));
	}

	/** The monitor notice's "Stop" button - emits {@code jockey_radio:stop_monitor {id}}. */
	private static ActionButton stopMonitorButton(Component label, String discId, int width) {
		CompoundTag additions = new CompoundTag();
		additions.putString("id", discId);
		Action action = new CustomAll(id("stop_monitor"), Optional.of(additions));
		return new ActionButton(new CommonButtonData(label, width), Optional.of(action));
	}

	private static ActionButton saveButton(Component label, String source, String editId,
			String nameColorHex, String baseDisc, String nameStyle) {
		// The name colour, base disc AND name style are carried in the button payload (not
		// form inputs), so Save persists exactly what the preview above is currently showing -
		// the same values the three cycle buttons advance and re-render.
		Action action = new CustomAll(id("save"),
				Optional.of(formAdditions(source, editId, nameColorHex, baseDisc, nameStyle)));
		return new ActionButton(new CommonButtonData(label, 220), Optional.of(action));
	}

	/**
	 * The "Name color: <name> ▶" cycler. Clicking it submits the current inputs to
	 * {@code jockey_radio:cycle_color}, which advances the colour to the next
	 * {@link #COLORS} entry and re-renders the SAME form - so the preview item and
	 * this label update immediately (the only automatic refresh vanilla dialogs
	 * allow). The current colour rides in the payload so nothing is lost.
	 */
	/**
	 * The common payload every create/edit-form button carries: the source type, the
	 * edit id, AND both preview-only choices (name colour, base disc) that live in the
	 * button payload rather than in form inputs. Carrying BOTH on every button is what
	 * lets the colour cycler, the style cycler, Preview and Save each round-trip
	 * without losing the other's choice.
	 */
	private static CompoundTag formAdditions(String source, String editId, String nameColorHex, String baseDisc,
			String nameStyle) {
		CompoundTag additions = new CompoundTag();
		additions.putString("source", source);
		additions.putString("edit", editId == null ? "" : editId);
		additions.putString("name_color", nameColorHex == null || nameColorHex.isBlank() ? DEFAULT_COLOR : nameColorHex);
		additions.putString("base_disc",
				baseDisc == null || baseDisc.isBlank() ? DiscDefinition.DEFAULT_BASE_DISC : baseDisc);
		additions.putString("name_style", nameStyle == null || nameStyle.isBlank() ? "plain" : nameStyle);
		return additions;
	}

	private static ActionButton cycleColorButton(String currentHex, String currentBaseDisc, String currentNameStyle,
			String source, String editId) {
		Action action = new CustomAll(id("cycle_color"),
				Optional.of(formAdditions(source, editId, currentHex, currentBaseDisc, currentNameStyle)));
		Component label = Component.literal("Name color: ")
				.append(Component.literal(colorDisplayName(currentHex))
						.withStyle(s -> s.withColor(TextColor.fromRgb(parseColorHex(currentHex, BRAND_GREEN)))))
				.append(Component.literal(" ▶"));
		return new ActionButton(new CommonButtonData(label,
				Optional.of(Lang.tr("jockey_radio.button.cycle_color_tip",
						"Change the disc name colour - the preview updates instantly")), 220),
				Optional.of(action));
	}

	/**
	 * The "Disc style: <name> ▶" cycler, shown right under the colour cycler. Clicking
	 * it submits the current inputs to {@code jockey_radio:cycle_style}, which
	 * advances the base disc to the next {@link #STYLES} entry and re-renders the SAME
	 * form - so the preview icon updates immediately. Both choices ride in the payload
	 * so cycling the style never loses the chosen colour (and vice-versa).
	 */
	private static ActionButton cycleStyleButton(String currentBaseDisc, String currentHex, String currentNameStyle,
			String source, String editId) {
		Action action = new CustomAll(id("cycle_style"),
				Optional.of(formAdditions(source, editId, currentHex, currentBaseDisc, currentNameStyle)));
		Component label = Component.literal("Disc style: ")
				.append(Component.literal(styleDisplayName(currentBaseDisc))
						.withStyle(s -> s.withColor(TextColor.fromRgb(BRAND_GREEN))))
				.append(Component.literal(" ▶"));
		return new ActionButton(new CommonButtonData(label,
				Optional.of(Lang.tr("jockey_radio.button.cycle_style_tip",
						"Change the disc's item style - the preview icon updates instantly")), 220),
				Optional.of(action));
	}

	/**
	 * The "Name style: <name> ▶" cycler (sow-010), shown under the disc-style cycler. Advances the
	 * display-name font style through {@link NameStyler}'s set and re-renders the SAME form, so the
	 * preview caption restyles instantly. All three preview choices (colour, base disc, name style)
	 * ride in the payload so cycling one never loses the others. The stored {@code name} stays plain;
	 * only its rendered form changes.
	 */
	private static ActionButton cycleNameStyleButton(String currentNameStyle, String currentHex,
			String currentBaseDisc, String source, String editId) {
		Action action = new CustomAll(id("cycle_name_style"),
				Optional.of(formAdditions(source, editId, currentHex, currentBaseDisc, currentNameStyle)));
		Component label = Component.literal("Name style: ")
				.append(Component.literal(NameStyler.displayName(currentNameStyle))
						.withStyle(s -> s.withColor(TextColor.fromRgb(parseColorHex(currentHex, BRAND_GREEN)))))
				.append(Component.literal(" ▶"));
		return new ActionButton(new CommonButtonData(label,
				Optional.of(Lang.tr("jockey_radio.button.cycle_name_style_tip",
						"Change the display-name font style - the preview updates instantly")), 220),
				Optional.of(action));
	}

	/** The STYLES display name for a base-disc id, or the bare id when off-list (custom). */
	private static String styleDisplayName(String baseDisc) {
		if (baseDisc != null) {
			for (String[] s : STYLES) {
				if (s[0].equalsIgnoreCase(baseDisc)) {
					return s[1];
				}
			}
			if (!baseDisc.isBlank()) {
				// A hand-authored off-list base disc: show its short path so it is legible.
				int slash = baseDisc.indexOf(':');
				return slash >= 0 && slash + 1 < baseDisc.length() ? baseDisc.substring(slash + 1) : baseDisc;
			}
		}
		return STYLES[0][1];
	}

	/**
	 * Advances a base-disc id to the next {@link #STYLES} entry, wrapping at the end.
	 * An off-list / null current base disc advances to the first entry (the default
	 * {@code music_disc_13}), so the cycler is always usable.
	 */
	static String nextBaseDisc(String currentBaseDisc) {
		int idx = -1;
		if (currentBaseDisc != null) {
			for (int i = 0; i < STYLES.length; i++) {
				if (STYLES[i][0].equalsIgnoreCase(currentBaseDisc)) {
					idx = i;
					break;
				}
			}
		}
		return STYLES[(idx + 1) % STYLES.length][0];
	}

	/** The COLORS display name for a hex, or "Custom" when off-palette. */
	private static String colorDisplayName(String hex) {
		if (hex != null) {
			for (String[] c : COLORS) {
				if (c[1].equalsIgnoreCase(hex)) {
					return c[0];
				}
			}
		}
		return "Custom";
	}

	/**
	 * Advances a colour hex to the next {@link #COLORS} entry, wrapping at the end.
	 * An off-palette / null current colour advances to the first entry (the brand
	 * green), so the cycler is always usable.
	 */
	static String nextColorHex(String currentHex) {
		int idx = -1;
		if (currentHex != null) {
			for (int i = 0; i < COLORS.length; i++) {
				if (COLORS[i][1].equalsIgnoreCase(currentHex)) {
					idx = i;
					break;
				}
			}
		}
		return COLORS[(idx + 1) % COLORS.length][1];
	}

	/** The muted body note explaining how the preview refreshes on this form. */
	private static Component colorNote() {
		return note("Click \"Name color\" or \"Item style\" to restyle the disc - the preview above "
				+ "updates instantly. Typed fields (name, URL) refresh in the preview on any button press.");
	}

	/**
	 * A copy-to-clipboard button. Uses a {@link StaticAction} wrapping a
	 * {@link ClickEvent.CopyToClipboard} - the client places {@code value} on the
	 * OS clipboard locally, with no server round trip. The value (which may be a
	 * secret stream key or ingest URL) is serialized only into the requesting
	 * admin's ShowDialog packet; it is never logged and never in item NBT. The
	 * enclosing dialog uses {@link DialogAction#NONE} ({@link #commonNone}) so it
	 * stays open after the copy fires.
	 */
	private static ActionButton copyButton(Component label, String value) {
		Action copy = new StaticAction(new ClickEvent.CopyToClipboard(value == null ? "" : value));
		return new ActionButton(new CommonButtonData(label,
				Optional.of(Lang.tr("jockey_radio.copy.tooltip", "Copy to clipboard")), 220),
				Optional.of(copy));
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(JockeyRadioMod.MOD_ID, path);
	}

	private static String generateStreamKey() {
		StringBuilder sb = new StringBuilder(24);
		for (int i = 0; i < 24; i++) {
			sb.append(KEY_ALPHABET[RANDOM.nextInt(KEY_ALPHABET.length)]);
		}
		return sb.toString();
	}

	/**
	 * The lowest RTMP port at or above {@link #DEFAULT_RTMP_PORT} that no saved
	 * {@code rtmp_receive} disc already claims, so the create form never pre-fills a port
	 * that is guaranteed to collide. Only a suggestion - {@link DiscRegistry#put} is what
	 * actually enforces uniqueness, since the admin can still type any port.
	 */
	private static String nextFreeRtmpPort() {
		Set<Integer> taken = new HashSet<>();
		for (DiscDefinition d : DiscRegistry.get().list()) {
			if (d.sourceType == DiscDefinition.SourceType.RTMP_RECEIVE && d.rtmpPort > 0) {
				taken.add(d.rtmpPort);
			}
		}
		int port = Integer.parseInt(DEFAULT_RTMP_PORT);
		while (port < 65535 && taken.contains(port)) {
			port++;
		}
		return String.valueOf(port);
	}

	/** Parses {@code #RRGGBB} to a packed RGB int, or returns {@code fallback}. */
	private static int parseColorHex(String hex, int fallback) {
		if (hex == null || hex.length() != 7 || hex.charAt(0) != '#') {
			return fallback;
		}
		try {
			return Integer.parseInt(hex.substring(1), 16);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private interface Field {
		String get(DiscDefinition def);
	}

	/** Reads a field off the prefill def, falling back to {@code fallback} when absent/blank. */
	private static String pick(Optional<DiscDefinition> prefill, Field field, String fallback) {
		if (prefill.isEmpty()) {
			return fallback;
		}
		String value = field.get(prefill.get());
		return value == null || value.isBlank() ? fallback : value;
	}

	private JockeyRadioDialogs() {
	}
}
