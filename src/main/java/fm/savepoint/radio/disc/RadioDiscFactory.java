package fm.savepoint.radio.disc;

import fm.savepoint.radio.SavePointRadioMod;
import fm.savepoint.radio.config.RadioConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.JukeboxPlayable;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.Optional;

/**
 * Builds Traveler's Jukebox disc item stacks.
 *
 * <p>The disc is a vanilla {@code music_disc_13} whose {@code jukebox_playable}
 * component points at the bundled {@code travelers_jukebox:radio} jukebox song.
 * That song's sound event ({@code travelers_jukebox:radio_stream}) deliberately
 * has no {@code sounds.json} entry anywhere, so vanilla clients resolve nothing
 * and play silence - the actual audio arrives via voice chat in later phases.
 * The target station key travels in the {@code custom_data} component as
 * {@code {"travelers_jukebox": {"station": "<key>"}}}.</p>
 */
public final class RadioDiscFactory {

	/** Resource key of the bundled data-driven jukebox song. */
	public static final ResourceKey<JukeboxSong> RADIO_SONG_KEY = ResourceKey.create(
			Registries.JUKEBOX_SONG,
			Identifier.fromNamespaceAndPath(SavePointRadioMod.MOD_ID, "radio"));

	private static final int NAME_COLOR = 0x37C871;
	private static final int LORE_COLOR = 0x8888AA;

	/**
	 * Creates a radio disc for the given station.
	 *
	 * @param registries registry access used to resolve the jukebox song holder
	 *                   (e.g. {@code source.registryAccess()})
	 * @param stationKey config key of the station
	 * @param station    the station definition
	 * @return the disc, or empty if the {@code travelers_jukebox:radio} jukebox
	 *         song is not present in the datapack registry
	 */
	public static Optional<ItemStack> createDisc(HolderLookup.Provider registries, String stationKey,
			RadioConfig.Station station) {
		Optional<? extends Holder<JukeboxSong>> song =
				registries.lookupOrThrow(Registries.JUKEBOX_SONG).get(RADIO_SONG_KEY);
		if (song.isEmpty()) {
			SavePointRadioMod.LOGGER.error("Jukebox song {} is missing from the registry - is the mod datapack loaded?",
					RADIO_SONG_KEY.identifier());
			return Optional.empty();
		}

		String displayName = station.displayName != null ? station.displayName : stationKey;

		ItemStack stack = new ItemStack(Items.MUSIC_DISC_13);
		stack.set(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(song.get()));

		MutableComponent name = Component.literal(displayName)
				.withStyle(style -> style.withColor(TextColor.fromRgb(NAME_COLOR)).withItalic(false))
				.append(Component.literal(" — Traveler's Jukebox")
						.withStyle(style -> style.withColor(TextColor.fromRgb(LORE_COLOR)).withItalic(false)));
		stack.set(DataComponents.ITEM_NAME, name);

		stack.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("Drop into a jukebox to tune in")
						.withStyle(style -> style.withColor(TextColor.fromRgb(LORE_COLOR)).withItalic(false)))));

		CompoundTag inner = new CompoundTag();
		inner.putString("station", stationKey);
		CompoundTag root = new CompoundTag();
		root.put(SavePointRadioMod.MOD_ID, inner);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));

		return Optional.of(stack);
	}

	/**
	 * Builds an admin disc from a {@link DiscDefinition}: a chosen base vanilla
	 * disc, an RGB-colored {@code item_name} from the definition's name/color,
	 * the bundled silent {@code travelers_jukebox:radio} jukebox song, and
	 * {@code custom_data} carrying ONLY the stable {@code disc_id}. Feed details
	 * (host, token, stream key, ...) stay server-side in the registry and never
	 * enter item NBT.
	 *
	 * @return the disc, or empty if the {@code travelers_jukebox:radio} jukebox
	 *         song is not present in the datapack registry
	 */
	public static Optional<ItemStack> createFromDefinition(HolderLookup.Provider registries, DiscDefinition def) {
		Optional<? extends Holder<JukeboxSong>> song =
				registries.lookupOrThrow(Registries.JUKEBOX_SONG).get(RADIO_SONG_KEY);
		if (song.isEmpty()) {
			SavePointRadioMod.LOGGER.error("Jukebox song {} is missing from the registry - is the mod datapack loaded?",
					RADIO_SONG_KEY.identifier());
			return Optional.empty();
		}

		ItemStack stack = new ItemStack(resolveBaseDisc(def.baseDisc));
		stack.set(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(song.get()));

		int color = parseColor(def.nameColorHex, NAME_COLOR);
		// sow-010: displayName() applies the chosen Unicode name style; falls back to the id when unnamed.
		MutableComponent name = Component.literal(def.name != null ? def.displayName() : def.discId)
				.withStyle(style -> style.withColor(TextColor.fromRgb(color)).withItalic(false));
		stack.set(DataComponents.ITEM_NAME, name);

		// Source-type badge line (mockup colours: HLS cyan / RTMP gold). Feed
		// details (host/token/key) stay server-side and NEVER enter item NBT -
		// only the non-secret source KIND is shown here.
		boolean rtmp = def.sourceType == DiscDefinition.SourceType.RTMP_RECEIVE;
		Component sourceLine = Component.literal(rtmp ? "RTMP receive" : "SavePoint stream")
				.withStyle(style -> style.withColor(TextColor.fromRgb(rtmp ? 0xFFB84D : 0x57E2E2)).withItalic(false));
		stack.set(DataComponents.LORE, new ItemLore(List.of(
				sourceLine,
				Component.literal("Drop into a jukebox to tune in")
						.withStyle(style -> style.withColor(TextColor.fromRgb(LORE_COLOR)).withItalic(false)))));

		CompoundTag inner = new CompoundTag();
		inner.putString("disc", def.discId);
		CompoundTag root = new CompoundTag();
		root.put(SavePointRadioMod.MOD_ID, inner);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));

		return Optional.of(stack);
	}

	/** Resolves the configured base disc item id, falling back to {@code music_disc_13}. */
	private static Item resolveBaseDisc(String baseDisc) {
		if (baseDisc == null || baseDisc.isBlank()) {
			return Items.MUSIC_DISC_13;
		}
		Identifier id = Identifier.tryParse(baseDisc);
		if (id == null) {
			SavePointRadioMod.LOGGER.warn("Disc baseDisc '{}' is not a valid item id - using music_disc_13", baseDisc);
			return Items.MUSIC_DISC_13;
		}
		return BuiltInRegistries.ITEM.getOptional(id).orElseGet(() -> {
			SavePointRadioMod.LOGGER.warn("Disc baseDisc '{}' is not a known item - using music_disc_13", baseDisc);
			return Items.MUSIC_DISC_13;
		});
	}

	/** Parses {@code #RRGGBB} to a packed RGB int, or returns {@code fallback}. */
	private static int parseColor(String hex, int fallback) {
		if (hex == null || hex.length() != 7 || hex.charAt(0) != '#') {
			return fallback;
		}
		try {
			return Integer.parseInt(hex.substring(1), 16);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/**
	 * Reads the station key back out of a disc's {@code custom_data}, if present.
	 */
	public static Optional<String> readStationKey(ItemStack stack) {
		return readInner(stack, "station");
	}

	/**
	 * Reads the admin {@code disc_id} back out of a disc's {@code custom_data},
	 * if present ({@code custom_data.travelers_jukebox.disc}).
	 */
	public static Optional<String> readDiscId(ItemStack stack) {
		return readInner(stack, "disc");
	}

	/** True for any Traveler's Jukebox disc (admin disc id or config station key). */
	public static boolean isRadioDisc(ItemStack stack) {
		return readDiscId(stack).isPresent() || readStationKey(stack).isPresent();
	}

	private static Optional<String> readInner(ItemStack stack, String key) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return Optional.empty();
		}
		CompoundTag root = data.copyTag();
		return root.getCompound(SavePointRadioMod.MOD_ID)
				.flatMap(inner -> inner.getString(key));
	}

	private RadioDiscFactory() {
	}
}
