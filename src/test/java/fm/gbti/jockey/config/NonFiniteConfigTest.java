package fm.gbti.jockey.config;

import com.google.gson.Gson;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Gson reads jockey_radio.json leniently, so {@code NaN}, {@code Infinity} and {@code -Infinity}
 * all parse into a double key. A comparison-based clamp lets NaN straight through (every comparison
 * with NaN is false), which used to leave a 1-frame jitter buffer or a NaN stretch budget. Each
 * non-finite value must resolve exactly as if the key were absent.
 */
class NonFiniteConfigTest {

	private static final List<String> DOUBLE_KEYS = List.of(
			"targetBufferSeconds", "maxBufferSeconds", "prerollSeconds", "reanchorLowWatermarkSeconds",
			"reanchorSustainSeconds", "maxStretchPercent", "feedDiagnosticsIntervalSeconds");

	static Stream<Arguments> nonFinite() {
		return DOUBLE_KEYS.stream().flatMap(key -> Stream.of("NaN", "Infinity", "-Infinity")
				.map(value -> Arguments.of(key, value)));
	}

	@ParameterizedTest(name = "{0} = {1} resolves to the default")
	@MethodSource("nonFinite")
	void nonFiniteValueFallsBackToTheDefault(String key, String value) {
		// Gson's default fromJson is lenient, like RadioConfig.load(), so the literal parses.
		RadioConfig loaded = new Gson().fromJson("{\"" + key + "\": " + value + "}", RadioConfig.class);
		ConfigNormalizer.normalizeIngestSettings(loaded);

		// The defaults a file without the key resolves to.
		RadioConfig absent = new Gson().fromJson("{}", RadioConfig.class);
		ConfigNormalizer.normalizeIngestSettings(absent);

		assertAll(
				() -> assertEquals(absent.targetFrames(), loaded.targetFrames(), "targetFrames"),
				() -> assertEquals(absent.maxBufferFrames(), loaded.maxBufferFrames(), "maxBufferFrames"),
				() -> assertEquals(absent.prerollFrames(), loaded.prerollFrames(), "prerollFrames"),
				() -> assertEquals(absent.reanchorLowWaterFrames(), loaded.reanchorLowWaterFrames(),
						"reanchorLowWaterFrames"),
				() -> assertEquals(absent.reanchorSustainFrames(), loaded.reanchorSustainFrames(),
						"reanchorSustainFrames"),
				() -> assertEquals(absent.maxStretchPercent(), loaded.maxStretchPercent(), "maxStretchPercent"),
				() -> assertEquals(absent.feedDiagnosticsIntervalSeconds, loaded.feedDiagnosticsIntervalSeconds,
						"feedDiagnosticsIntervalSeconds"));
	}
}
