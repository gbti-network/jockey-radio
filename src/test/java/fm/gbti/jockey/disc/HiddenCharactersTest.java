package fm.gbti.jockey.disc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Admin-entered text that players see (the disc name, the marquee template) and the URLs shown on
 * the disc page must not carry characters that hide or reorder what is displayed: a right-to-left
 * override turns "Radio" + RLO + "3pm.exe" into a name that reads differently from what it is.
 */
class HiddenCharactersTest {

	private static final String RLO = "‮";

	/** Code points that hide or reorder text, plus the C1, separator and colour-code cases. */
	private static final List<String> HIDDEN = List.of(
			"‪", "‫", "‬", "‭", RLO, // embeddings and overrides
			"⁦", "⁧", "⁨", "⁩", // isolates
			"‎", "‏", "؜", // direction marks
			"​", "⁠", "﻿", "­", // zero-width and soft hyphen
			"\u0085", "\u009B", // C1 controls (NEL, CSI)
			" ", " ", // line and paragraph separators
			"\n", "\u0007", "\u007F", "§", // what was already refused
			"\uD800"); // an unpaired surrogate

	private static DiscDefinition disc(String name) {
		DiscDefinition d = new DiscDefinition();
		d.discId = "lofi";
		d.name = name;
		d.sourceType = DiscDefinition.SourceType.HLS_PULL;
		d.streamUrl = "https://radio.example.com/live.m3u8";
		d.applyDefaults();
		return d;
	}

	@Test
	@DisplayName("a name with a hidden or reordering character is refused")
	void hiddenCharactersInNameAreRefused() {
		for (String c : HIDDEN) {
			String name = "Radio" + c + "Pink";
			assertTrue(disc(name).validate().isPresent(),
					"accepted a name containing U+" + Integer.toHexString(c.codePointAt(0)).toUpperCase());
		}
	}

	@Test
	@DisplayName("the marquee template and the URLs are held to the same rule")
	void hiddenCharactersInTemplateAndUrlsAreRefused() {
		DiscDefinition template = disc("Radio Pink");
		template.nowPlayingTemplate = "{station} " + RLO + "{title}";
		assertTrue(template.validate().isPresent(), "accepted a template with U+202E");

		DiscDefinition stream = disc("Radio Pink");
		stream.streamUrl = "https://radio.example.com/live" + RLO + "8u3m.m3u8";
		assertTrue(stream.validate().isPresent(), "accepted a stream URL with U+202E");

		DiscDefinition nowPlaying = disc("Radio Pink");
		nowPlaying.nowPlayingUrl = "https://radio.example.com/np​";
		assertTrue(nowPlaying.validate().isPresent(), "accepted a now-playing URL with U+200B");
	}

	@Test
	@DisplayName("ordinary Unicode, emoji sequences and every name style stay valid")
	void ordinaryTextIsKept() {
		List<String> fine = List.of(
				"Café Señal", "Радио Пинк", "ラジオ", "راديو", "🎵 Lo-fi Beats",
				"❤️ Love FM", // U+FE0F variation selector
				"👨‍👩‍👧 Family Hour", // zero-width joiners inside one emoji
				"🏴󠁧󠁢󠁥󠁮󠁧󠁿 London", // tag characters inside one flag
				" Custom Glyph"); // private use, as resource packs use for icons
		for (String name : fine) {
			assertEquals(java.util.Optional.empty(), disc(name).validate(), name);
		}
		String style = NameStyler.nextStyle(null);
		do {
			DiscDefinition styled = disc("Radio Pink 42");
			styled.nameStyle = style;
			styled.applyDefaults();
			assertEquals(java.util.Optional.empty(), styled.validate(), style);
			String shown = styled.displayName();
			assertTrue(shown.codePoints().noneMatch(cp -> Character.getType(cp) == Character.FORMAT),
					style + " rendered an invisible character");
			style = NameStyler.nextStyle(style);
		} while (!style.equals(NameStyler.nextStyle(null)));
	}

	@Test
	@DisplayName("an older discs.json is cleaned on load instead of losing the disc")
	void loadStripsHiddenCharacters() {
		DiscDefinition spoofed = new DiscDefinition();
		spoofed.name = "Radio" + RLO + "Pink​";
		spoofed.nowPlayingTemplate = "{station}⁦ {title}";
		spoofed.streamUrl = "https://radio.example.com/live.m3u8";
		DiscDefinition emoji = new DiscDefinition();
		emoji.name = "👨‍👩‍👧 Family Hour";
		emoji.streamUrl = "https://radio.example.com/family.m3u8";
		Map<String, DiscDefinition> loaded = new LinkedHashMap<>();
		loaded.put("pink", spoofed);
		loaded.put("family", emoji);

		Map<String, DiscDefinition> fresh = new LinkedHashMap<>();
		boolean dropped = DiscRegistry.parseEntries(loaded, fresh);

		assertFalse(dropped, "a disc was dropped instead of cleaned");
		assertEquals("RadioPink", fresh.get("pink").name);
		assertEquals("{station} {title}", fresh.get("pink").nowPlayingTemplate);
		assertEquals("👨‍👩‍👧 Family Hour", fresh.get("family").name, "the emoji was changed");
	}
}
