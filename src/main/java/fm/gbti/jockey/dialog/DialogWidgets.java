package fm.gbti.jockey.dialog;

import fm.gbti.jockey.Lang;
import fm.gbti.jockey.JockeyRadioMod;
import fm.gbti.jockey.disc.DiscDefinition;
import fm.gbti.jockey.disc.DiscRegistry;
import fm.gbti.jockey.disc.NameStyler;
import fm.gbti.jockey.disc.RadioDiscFactory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.action.Action;
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.NumberRangeInput;
import net.minecraft.server.dialog.input.SingleOptionInput;
import net.minecraft.server.dialog.input.TextInput;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The building blocks {@link JockeyRadioDialogs} assembles its dialogs from: the colour and
 * style palettes, body lines, inputs, nav and action buttons, and the disc-appearance cycling.
 * No dialog is built here.
 */
final class DialogWidgets {

	private DialogWidgets() {
	}

	/**
	 * Default RTMP port offered in the create form. Deliberately 1936, NOT the
	 * standard 1935: on a SavePoint host the local SavePoint server already owns
	 * 1935, so a mod RTMP-receive disc bound there can never listen (ffmpeg loops
	 * with "Address already in use"). 1936 is the known-free port.
	 */
	private static final String DEFAULT_RTMP_PORT = "1936";
	/** Brand default disc name colour (matches {@link DiscDefinition}). */
	static final String DEFAULT_COLOR = "#37C871";

	/** Radio Green - the brand accent used on titles the mockup shows branded. */
	static final int BRAND_GREEN = 0x37C871;
	/** Source badge colours from the mockup (mc-hls cyan / mc-rtmp gold / mc-file purple). */
	static final int HLS_COLOR = 0x57E2E2;
	static final int RTMP_COLOR = 0xFFB84D;
	static final int FILE_COLOR = 0xC792EA;
	static final int PLATFORM_COLOR = 0xF07178;
	/** Live/idle status colours from the mockup. */
	static final int LIVE_COLOR = 0x54FB54;
	static final int IDLE_COLOR = 0x9B9B9B;
	/** Result-notice colours (green success / red error). */
	private static final int SUCCESS_COLOR = 0x54FB54;
	static final int ERROR_COLOR = 0xFF5555;
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
	// Building blocks
	// ------------------------------------------------------------------

	static CommonDialogData commonNone(Component title, List<DialogBody> body, List<Input> inputs) {
		// pause=false is mandatory for a server mod; afterAction=NONE keeps the
		// dialog open on submit so the handler can push an updated one in reply -
		// and, crucially, keeps a copy-to-clipboard dialog on screen after a copy.
		return new CommonDialogData(title, Optional.empty(),
				true, false, DialogAction.NONE, body, inputs);
	}

	static CommonDialogData commonClose(Component title, List<DialogBody> body, List<Input> inputs) {
		return new CommonDialogData(title, Optional.empty(),
				true, false, DialogAction.CLOSE, body, inputs);
	}

	static DialogBody body(String text) {
		return body(Component.literal(text));
	}

	static DialogBody body(Component text) {
		return new PlainMessage(text, 320);
	}

	/** A brand-green title component. */
	static MutableComponent brandTitle(MutableComponent title) {
		return title.withStyle(s -> s.withColor(TextColor.fromRgb(BRAND_GREEN)));
	}

	static Component success(String text) {
		return Component.literal(text).withStyle(s -> s.withColor(TextColor.fromRgb(SUCCESS_COLOR)));
	}

	static Component error(String text) {
		return Component.literal(text).withStyle(s -> s.withColor(TextColor.fromRgb(ERROR_COLOR)));
	}

	static Component note(String text) {
		return Component.literal(text).withStyle(s -> s.withColor(TextColor.fromRgb(LABEL_COLOR)));
	}

	/** A connection-info row: a muted label and the real value shown for copying. */
	static DialogBody connRow(String label, String value) {
		return body(Component.literal(label + ":  ").withStyle(s -> s.withColor(TextColor.fromRgb(LABEL_COLOR)))
				.append(Component.literal(value == null ? "" : value)
						.withStyle(s -> s.withColor(TextColor.fromRgb(VALUE_COLOR)))));
	}

	static TextInput text(Component label, String initial, int maxLength) {
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
	static NumberRangeInput radiusInput(int initial) {
		float init = Math.max(1f, Math.min(512f, initial));
		NumberRangeInput.RangeInfo range = new NumberRangeInput.RangeInfo(
				1f, 512f, Optional.of(init), Optional.of(1f));
		return new NumberRangeInput(220,
				Lang.tr("jockey_radio.input.radius", "Audible radius (blocks)"),
				"options.generic_value", range);
	}

	static SingleOptionInput bindInput(String selected) {
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
		entries.add(new SingleOptionInput.Entry("0.0.0.0",
				Optional.of(Component.literal("Remote / managed host (0.0.0.0)")), any));
		entries.add(new SingleOptionInput.Entry("127.0.0.1",
				Optional.of(Component.literal("Local broadcaster only (127.0.0.1)")), loopback));
		return new SingleOptionInput(220, List.copyOf(entries),
				Lang.tr("jockey_radio.input.rtmp_bind", "RTMP bind"), true);
	}

	/**
	 * sow-012: a Repeat On/Off selector for FILE / PLATFORM discs. {@code on} = loop forever (default);
	 * {@code off} = play once then stop like a vanilla disc (finite sources only - a live platform stream
	 * keeps going regardless). Submits its selected entry key ({@code on}/{@code off}) like {@code bindInput}.
	 */
	static SingleOptionInput repeatInput(boolean repeat) {
		List<SingleOptionInput.Entry> entries = List.of(
				new SingleOptionInput.Entry("on",
						Optional.of(Component.literal("Repeat (loop)")), repeat),
				new SingleOptionInput.Entry("off",
						Optional.of(Component.literal("Play once, then stop")), !repeat));
		return new SingleOptionInput(220, entries, Lang.tr("jockey_radio.input.repeat", "Repeat"), true);
	}

	/**
	 * Edit-only Keep/Clear selector for the stored HLS access token, which is never shown
	 * and so cannot be blanked by hand. Submits {@code keep} (default) or {@code clear}.
	 */
	static SingleOptionInput tokenActionInput() {
		List<SingleOptionInput.Entry> entries = List.of(
				new SingleOptionInput.Entry("keep",
						Optional.of(Component.literal("Keep the stored token")), true),
				new SingleOptionInput.Entry("clear",
						Optional.of(Component.literal("Clear the stored token")), false));
		return new SingleOptionInput(220, entries, Lang.tr("jockey_radio.input.token_action", "Stored token"), true);
	}

	static ActionButton navButton(Component label, String to, int width) {
		return navButton(label, null, to, width);
	}

	static ActionButton navButton(Component label, Component tooltip, String to, int width) {
		CompoundTag additions = new CompoundTag();
		additions.putString("to", to);
		Action action = new CustomAll(id("nav"), Optional.of(additions));
		CommonButtonData data = tooltip == null
				? new CommonButtonData(label, width)
				: new CommonButtonData(label, Optional.of(tooltip), width);
		return new ActionButton(data, Optional.of(action));
	}

	static ActionButton navIdButton(Component label, String to, String discId, int width) {
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
	static ActionButton browseAudioButton(String nameColorHex, String baseDisc, String nameStyle,
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
	static ActionButton carryNavButton(Component label, String to, String discId, int width,
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
	static ActionButton testButton(Component label, String discId, int width) {
		CompoundTag additions = new CompoundTag();
		additions.putString("id", discId);
		Action action = new CustomAll(id("test"), Optional.of(additions));
		return new ActionButton(new CommonButtonData(label, width), Optional.of(action));
	}

	/** The monitor notice's "Stop" button - emits {@code jockey_radio:stop_monitor {id}}. */
	static ActionButton stopMonitorButton(Component label, String discId, int width) {
		CompoundTag additions = new CompoundTag();
		additions.putString("id", discId);
		Action action = new CustomAll(id("stop_monitor"), Optional.of(additions));
		return new ActionButton(new CommonButtonData(label, width), Optional.of(action));
	}

	static ActionButton saveButton(Component label, String source, String editId,
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

	static ActionButton cycleColorButton(String currentHex, String currentBaseDisc, String currentNameStyle,
			String source, String editId) {
		Action action = new CustomAll(id("cycle_color"),
				Optional.of(formAdditions(source, editId, currentHex, currentBaseDisc, currentNameStyle)));
		Component label = Component.literal("Name color: ")
				.append(Component.literal(colorDisplayName(currentHex))
						.withStyle(s -> s.withColor(TextColor.fromRgb(parseColorHex(currentHex, BRAND_GREEN)))))
				.append(Component.literal(" ▶"));
		return new ActionButton(new CommonButtonData(label,
				Optional.of(Lang.tr("jockey_radio.button.cycle_color_tip",
						"Change the disc name colour (the preview updates instantly)")), 220),
				Optional.of(action));
	}

	/**
	 * The "Disc style: <name> ▶" cycler, shown right under the colour cycler. Clicking
	 * it submits the current inputs to {@code jockey_radio:cycle_style}, which
	 * advances the base disc to the next {@link #STYLES} entry and re-renders the SAME
	 * form - so the preview icon updates immediately. Both choices ride in the payload
	 * so cycling the style never loses the chosen colour (and vice-versa).
	 */
	static ActionButton cycleStyleButton(String currentBaseDisc, String currentHex, String currentNameStyle,
			String source, String editId) {
		Action action = new CustomAll(id("cycle_style"),
				Optional.of(formAdditions(source, editId, currentHex, currentBaseDisc, currentNameStyle)));
		Component label = Component.literal("Disc style: ")
				.append(Component.literal(styleDisplayName(currentBaseDisc))
						.withStyle(s -> s.withColor(TextColor.fromRgb(BRAND_GREEN))))
				.append(Component.literal(" ▶"));
		return new ActionButton(new CommonButtonData(label,
				Optional.of(Lang.tr("jockey_radio.button.cycle_style_tip",
						"Change the disc's item style (the preview icon updates instantly)")), 220),
				Optional.of(action));
	}

	/**
	 * The "Name style: <name> ▶" cycler (sow-010), shown under the disc-style cycler. Advances the
	 * display-name font style through {@link NameStyler}'s set and re-renders the SAME form, so the
	 * preview caption restyles instantly. All three preview choices (colour, base disc, name style)
	 * ride in the payload so cycling one never loses the others. The stored {@code name} stays plain;
	 * only its rendered form changes.
	 */
	static ActionButton cycleNameStyleButton(String currentNameStyle, String currentHex,
			String currentBaseDisc, String source, String editId) {
		Action action = new CustomAll(id("cycle_name_style"),
				Optional.of(formAdditions(source, editId, currentHex, currentBaseDisc, currentNameStyle)));
		Component label = Component.literal("Name style: ")
				.append(Component.literal(NameStyler.displayName(currentNameStyle))
						.withStyle(s -> s.withColor(TextColor.fromRgb(parseColorHex(currentHex, BRAND_GREEN)))))
				.append(Component.literal(" ▶"));
		return new ActionButton(new CommonButtonData(label,
				Optional.of(Lang.tr("jockey_radio.button.cycle_name_style_tip",
						"Change the display-name font style (the preview updates instantly)")), 220),
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
	static Component colorNote() {
		return note("Click \"Name color\" or \"Item style\" to restyle the disc. The preview above "
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
	static ActionButton copyButton(Component label, String value) {
		Action copy = new StaticAction(new ClickEvent.CopyToClipboard(value == null ? "" : value));
		return new ActionButton(new CommonButtonData(label,
				Optional.of(Lang.tr("jockey_radio.copy.tooltip", "Copy to clipboard")), 220),
				Optional.of(copy));
	}

	static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(JockeyRadioMod.MOD_ID, path);
	}

	static String generateStreamKey() {
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
	static String nextFreeRtmpPort() {
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
	static int parseColorHex(String hex, int fallback) {
		if (hex == null || hex.length() != 7 || hex.charAt(0) != '#') {
			return fallback;
		}
		try {
			return Integer.parseInt(hex.substring(1), 16);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	interface Field {
		String get(DiscDefinition def);
	}

	/** Reads a field off the prefill def, falling back to {@code fallback} when absent/blank. */
	static String pick(Optional<DiscDefinition> prefill, Field field, String fallback) {
		if (prefill.isEmpty()) {
			return fallback;
		}
		String value = field.get(prefill.get());
		return value == null || value.isBlank() ? fallback : value;
	}
}
