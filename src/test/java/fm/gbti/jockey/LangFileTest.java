package fm.gbti.jockey;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps {@code en_us.json} identical to the English fallbacks passed to {@link Lang#tr}. The
 * fallback is what every vanilla client sees, so the lang file has to say the same thing or a
 * translator works from stale text. Fix a failure by editing whichever side is wrong.
 */
class LangFileTest {

	private static final String LITERAL = "\"(?:[^\"\\\\]|\\\\.)*\"";
	/** {@code Lang.tr("key", "fallback" + "continued"...}. */
	private static final Pattern TR = Pattern.compile(
			"Lang\\.tr\\(\\s*(" + LITERAL + ")\\s*,\\s*((?:" + LITERAL + "\\s*\\+?\\s*)+)");
	private static final Pattern ANY_TR = Pattern.compile("Lang\\.tr\\(\\s*\"");
	private static final Pattern PART = Pattern.compile(LITERAL);

	@Test
	@DisplayName("en_us.json holds exactly the code's keys, each with its fallback text")
	void langFileMatchesFallbacks() throws IOException {
		Map<String, String> code = fallbacksInCode();
		assertFalse(code.isEmpty(), "found no Lang.tr calls; is jockey.sourceDir right?");

		JsonObject json;
		try (InputStream in = LangFileTest.class.getResourceAsStream("/assets/jockey_radio/lang/en_us.json")) {
			assertTrue(in != null, "en_us.json is not on the classpath");
			json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
		Map<String, String> file = new TreeMap<>();
		json.entrySet().forEach(e -> file.put(e.getKey(), e.getValue().getAsString()));

		List<String> problems = new ArrayList<>();
		for (Map.Entry<String, String> e : code.entrySet()) {
			String inFile = file.get(e.getKey());
			if (inFile == null) {
				problems.add("missing from en_us.json: " + e.getKey());
			} else if (!inFile.equals(e.getValue())) {
				problems.add(e.getKey() + ": en_us.json says '" + inFile + "', the code says '" + e.getValue() + "'");
			}
		}
		file.keySet().stream().filter(k -> !code.containsKey(k))
				.forEach(k -> problems.add("in en_us.json but unused: " + k));
		assertTrue(problems.isEmpty(), String.join("\n", problems));
	}

	/** Every key and its fallback, failing on a key given two different fallbacks. */
	private static Map<String, String> fallbacksInCode() throws IOException {
		Path root = Path.of(System.getProperty("jockey.sourceDir", "src/main/java"));
		List<Path> sources = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(root)) {
			walk.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
		}
		Map<String, String> pairs = new TreeMap<>();
		for (Path p : sources) {
			String src = Files.readString(p);
			int calls = count(ANY_TR.matcher(src));
			int parsed = 0;
			Matcher m = TR.matcher(src);
			while (m.find()) {
				parsed++;
				String key = unquote(m.group(1));
				StringBuilder fallback = new StringBuilder();
				Matcher part = PART.matcher(m.group(2));
				while (part.find()) {
					fallback.append(unquote(part.group()));
				}
				String previous = pairs.putIfAbsent(key, fallback.toString());
				assertTrue(previous == null || previous.contentEquals(fallback),
						key + " has two fallbacks: '" + previous + "' and '" + fallback + "'");
			}
			assertEquals(calls, parsed, p.getFileName() + ": a Lang.tr call has a key or fallback that is not a literal");
		}
		return pairs;
	}

	private static int count(Matcher m) {
		int n = 0;
		while (m.find()) {
			n++;
		}
		return n;
	}

	/** A Java string literal's value. Covers the escapes the fallbacks use. */
	private static String unquote(String literal) {
		String body = literal.substring(1, literal.length() - 1);
		StringBuilder out = new StringBuilder(body.length());
		for (int i = 0; i < body.length(); i++) {
			char c = body.charAt(i);
			if (c != '\\') {
				out.append(c);
				continue;
			}
			char next = body.charAt(++i);
			switch (next) {
				case 'n' -> out.append('\n');
				case 't' -> out.append('\t');
				case 'u' -> {
					out.append((char) Integer.parseInt(body.substring(i + 1, i + 5), 16));
					i += 4;
				}
				default -> out.append(next);
			}
		}
		return out.toString();
	}
}
