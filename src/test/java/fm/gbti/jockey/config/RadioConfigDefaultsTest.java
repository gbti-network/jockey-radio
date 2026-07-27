package fm.gbti.jockey.config;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class RadioConfigDefaultsTest {

	@Test
	void productionDiagnosticsAreOffByDefault() {
		RadioConfig defaults = RadioConfig.createDefault();

		assertFalse(defaults.feedStatsLogging,
				"periodic feed-stat INFO logging must be opt-in");
		assertFalse(defaults.feedDiagnostics,
				"per-interval CSV diagnostics must be opt-in");
	}
}
