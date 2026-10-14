package fm.gbti.jockey.dialog;

import fm.gbti.jockey.disc.DiscDefinition;
import fm.gbti.jockey.disc.RadioDiscFactory;
import fm.gbti.jockey.session.FeedRegistry;
import fm.gbti.jockey.session.RadioManager;
import fm.gbti.jockey.session.RadioSession;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.ItemBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;

import java.util.Optional;

import static fm.gbti.jockey.dialog.DialogWidgets.*;

/**
 * How a disc reads in the dialogs: its source badge, the live/idle and now-playing lines, what
 * idle means for its source, its list-row label and its icon.
 */
final class DiscStatusText {

	private DiscStatusText() {
	}

	/** The coloured "[HLS]" / "[RTMP]" source badge. */
	static Component sourceBadge(DiscDefinition def) {
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
	 * snapshot: {@code ● LIVE · <title> · <artist>} (green) plus a muted
	 * {@code (<segment>, live DJ)} for a relay/master-key takeover or
	 * {@code (scheduled)} for the master schedule. A not-live snapshot renders
	 * {@code ○ idle}. With no snapshot (poller off, an RTMP disc, the disc not
	 * placed in a jukebox, or not yet polled) it falls back to the shared feed-state
	 * badge, unchanged.
	 */
	static Component richStatusComponent(String discId) {
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
			StringBuilder track = new StringBuilder(" · ").append(np.title());
			if (np.artist() != null) {
				track.append(" · ").append(np.artist());
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
			return segment + ", " + tag;
		}
		return segment != null ? segment : tag;
	}

	/**
	 * What "idle" means on the detail page. The badge reads idle whenever no feed is receiving
	 * audio, which most often means no jukebox is playing the disc; only a stream disc can also
	 * be idle because its station is off air.
	 */
	static String idleNote(DiscDefinition.SourceType type) {
		return switch (type) {
			case FILE -> "Idle means no jukebox is playing this disc, or the file could not be reached. "
					+ "Test checks the file.";
			case PLATFORM -> "Idle means no jukebox is playing this disc, or the link has nothing live. "
					+ "Test checks the link.";
			default -> "Idle means the station isn't broadcasting yet, or no jukebox is playing this disc. "
					+ "Any token stays masked and never appears in this URL.";
		};
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
	static Component rowLabel(DiscDefinition d) {
		return Component.empty()
				.append(Component.literal(d.name != null ? d.displayName() : d.discId)
						.withStyle(s -> s.withColor(TextColor.fromRgb(parseColorHex(d.nameColorHex, BRAND_GREEN)))))
				.append(Component.literal("  "))
				.append(sourceBadge(d))
				.append(Component.literal("  "))
				.append(statusComponent(d.discId));
	}

	static Optional<DialogBody> discIconBody(HolderLookup.Provider registries, DiscDefinition def, int size) {
		return discIconBody(registries, def, size, null);
	}

	/**
	 * An {@link ItemBody} rendering the disc's actual item (id + coloured name +
	 * jukebox song, verbatim from {@link RadioDiscFactory}), optionally captioned.
	 * Empty when the {@code jockey_radio:radio} jukebox song is not loaded - the
	 * caller simply omits the icon rather than failing the whole dialog.
	 */
	static Optional<DialogBody> discIconBody(HolderLookup.Provider registries, DiscDefinition def,
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
	static DiscDefinition brandDef() {
		DiscDefinition def = new DiscDefinition();
		def.discId = "savepoint";
		def.name = "Jockey Radio";
		def.nameColorHex = DEFAULT_COLOR;
		def.baseDisc = DiscDefinition.DEFAULT_BASE_DISC;
		return def;
	}
}
