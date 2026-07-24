package fm.savepoint.radio;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Tiny i18n seam for the {@code /savepoint} dialogs and commands. Every
 * user-facing string carries both a translation key (resolved from
 * {@code assets/savepoint_radio/lang/en_us.json} by clients that have the mod)
 * and a literal fallback.
 *
 * <p>Fallbacks are mandatory here, not optional: SavePoint Radio is a
 * <em>server</em> mod, and the vanilla clients it pushes dialogs to have no
 * {@code savepoint_radio} language file. Without the fallback such a client
 * would render the raw key ({@code savepoint_radio.menu.title}); with it the
 * text renders correctly everywhere, and a client that <em>does</em> ship the
 * mod (or a resource pack) can still localise it.</p>
 *
 * <p>Only presentation strings flow through here. Log lines (the {@code [radio]}
 * contract the deployment gates grep) stay plain literals.</p>
 */
public final class Lang {

	private Lang() {
	}

	/** A translatable component with a literal English fallback. */
	public static MutableComponent tr(String key, String fallback) {
		return Component.translatableWithFallback(key, fallback);
	}

	/**
	 * A translatable component with a literal English fallback and arguments.
	 * The fallback is a format string ({@code %s}); the same args fill both the
	 * localised value and the fallback, so untrusted args must already be
	 * sanitised by the caller.
	 */
	public static MutableComponent tr(String key, String fallback, Object... args) {
		return Component.translatableWithFallback(key, fallback, args);
	}
}
