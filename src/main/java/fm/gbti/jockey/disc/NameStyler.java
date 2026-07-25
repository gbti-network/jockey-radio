package fm.gbti.jockey.disc;

/**
 * Renders a plain display name into a "fancy" Unicode style (sow-010) using curated,
 * Minecraft-safe glyph maps: small caps, fullwidth, bold, italic, script, monospace.
 *
 * <p><b>Store plain, render styled.</b> The disc's {@link DiscDefinition#name} stays the plain
 * ASCII-ish text the admin types (so the existing {@code 1..48} / no-control-char validation is
 * unchanged and there is no astral-length problem), and the chosen {@link DiscDefinition#nameStyle}
 * is applied here at render time via {@link DiscDefinition#displayName()}. This keeps the style
 * fully reversible and the name editable.</p>
 *
 * <p><b>Safe by construction.</b> Only {@code A–Z a–z 0–9} are mapped, to curated letter-like
 * code points; every other character (spaces, punctuation, existing non-Latin letters) passes
 * through verbatim. No bidi overrides, zero-width, or combining marks are ever produced, so this
 * cannot be used to spoof or break rendering. Some styles have small coverage gaps (e.g. small-caps
 * q/x, or digits in italic/script) - those characters fall back to the original glyph, exactly as
 * every other fancy-text tool does. Astral code points (the math-alphanumeric blocks) are emitted
 * correctly as surrogate pairs.</p>
 */
public final class NameStyler {

	private NameStyler() {
	}

	/** {@code {id, displayName}} in cycle order; {@code plain} is first (the default = no styling). */
	private static final String[][] STYLES = {
			{"plain", "Plain"},
			{"small_caps", "Small Caps"},
			{"fullwidth", "Fullwidth"},
			{"bold", "Bold"},
			{"italic", "Italic"},
			{"script", "Script"},
			{"monospace", "Mono"},
	};

	/**
	 * Small-caps code points for {@code a..z}. Letters without a clean small-cap (q, x) map to
	 * themselves (0x71/0x78) so they render as ordinary lowercase rather than a wrong glyph.
	 */
	private static final int[] SMALL_CAPS = {
			0x1D00, 0x0299, 0x1D04, 0x1D05, 0x1D07, 0xA730, 0x0262, 0x029C, 0x026A, 0x1D0A,
			0x1D0B, 0x029F, 0x1D0D, 0x0274, 0x1D0F, 0x1D18, 0x0071, 0x0280, 0xA731, 0x1D1B,
			0x1D1C, 0x1D20, 0x1D21, 0x0078, 0x028F, 0x1D22,
	};

	/** True for a known style id (including {@code plain}). Null / unknown → false. */
	public static boolean isKnown(String styleId) {
		if (styleId == null) {
			return false;
		}
		for (String[] s : STYLES) {
			if (s[0].equals(styleId)) {
				return true;
			}
		}
		return false;
	}

	/** The cycler label for a style id ("Small Caps", ...), or "Plain" for null/unknown. */
	public static String displayName(String styleId) {
		if (styleId != null) {
			for (String[] s : STYLES) {
				if (s[0].equals(styleId)) {
					return s[1];
				}
			}
		}
		return STYLES[0][1];
	}

	/** The next style id in cycle order (wraps); a null/unknown current advances to the first. */
	public static String nextStyle(String styleId) {
		int idx = -1;
		if (styleId != null) {
			for (int i = 0; i < STYLES.length; i++) {
				if (STYLES[i][0].equals(styleId)) {
					idx = i;
					break;
				}
			}
		}
		return STYLES[(idx + 1) % STYLES.length][0];
	}

	/**
	 * Renders {@code text} in {@code styleId}. Returns {@code text} unchanged for null/empty text,
	 * a null/unknown style, or {@code plain}. Only A–Z/a–z/0–9 are transformed; all other characters
	 * pass through.
	 */
	public static String apply(String text, String styleId) {
		if (text == null || text.isEmpty() || styleId == null || styleId.equals("plain") || !isKnown(styleId)) {
			return text;
		}
		StringBuilder sb = new StringBuilder(text.length() * 2);
		int i = 0;
		while (i < text.length()) {
			int cp = text.codePointAt(i);
			i += Character.charCount(cp);
			sb.appendCodePoint(mapCodePoint(cp, styleId));
		}
		return sb.toString();
	}

	private static int mapCodePoint(int cp, String style) {
		boolean upper = cp >= 'A' && cp <= 'Z';
		boolean lower = cp >= 'a' && cp <= 'z';
		boolean digit = cp >= '0' && cp <= '9';
		switch (style) {
			case "fullwidth":
				// Printable ASCII 0x21..0x7E → the Fullwidth Forms block (offset +0xFEE0).
				return (cp >= 0x21 && cp <= 0x7E) ? cp + 0xFEE0 : cp;
			case "small_caps":
				if (upper) {
					return SMALL_CAPS[cp - 'A'];
				}
				if (lower) {
					return SMALL_CAPS[cp - 'a'];
				}
				return cp;
			case "bold": // Mathematical Sans-Serif Bold
				if (upper) {
					return cp - 'A' + 0x1D5D4;
				}
				if (lower) {
					return cp - 'a' + 0x1D5EE;
				}
				if (digit) {
					return cp - '0' + 0x1D7EC;
				}
				return cp;
			case "italic": // Mathematical Sans-Serif Italic (no italic digits → plain)
				if (upper) {
					return cp - 'A' + 0x1D608;
				}
				if (lower) {
					return cp - 'a' + 0x1D622;
				}
				return cp;
			case "script": // Mathematical Bold Script (contiguous, no holes; no script digits → plain)
				if (upper) {
					return cp - 'A' + 0x1D4D0;
				}
				if (lower) {
					return cp - 'a' + 0x1D4EA;
				}
				return cp;
			case "monospace": // Mathematical Monospace
				if (upper) {
					return cp - 'A' + 0x1D670;
				}
				if (lower) {
					return cp - 'a' + 0x1D68A;
				}
				if (digit) {
					return cp - '0' + 0x1D7F6;
				}
				return cp;
			default:
				return cp;
		}
	}
}
