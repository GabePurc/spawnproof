package io.github.gabepurc.spawnproof.gradle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RulesTest {
	@Test
	void namesSayWhatATaskIs() {
		assertEquals(Rules.TEST, Rules.byName("runClientGameTest", false));
		assertEquals(Rules.TEST, Rules.byName("runClientGameTestRideA", false));
		assertEquals(Rules.TEST, Rules.byName("runProductionClientGameTest", false));
		assertEquals(Rules.TEST, Rules.byName("runSmoke", false));
		assertEquals(Rules.TEST, Rules.byName("runCompatIcys", true));
		assertEquals(Rules.PLAY, Rules.byName("runClient", false));
		assertEquals(Rules.PLAY, Rules.byName("runFabricClient", false));
		assertEquals(Rules.PLAY, Rules.byName("runNeoForgeClient", false));
		assertEquals(Rules.TEST, Rules.byName("runAuditClient", false));
		assertEquals(Rules.PLAY, Rules.byName("playCompatIcys", true));
		assertEquals(Rules.NONE, Rules.byName("runServer", false));
		assertEquals(Rules.NONE, Rules.byName("runGameTest", false));
		assertEquals(Rules.NONE, Rules.byName("run", false));
		assertEquals(Rules.NONE, Rules.byName("playground", false));
	}

	@Test
	void globsMatchNamesAndPaths() {
		assertTrue(Rules.glob("runCompat*").matcher("runCompatIcys").matches());
		assertFalse(Rules.glob("runCompat*").matcher("playCompatIcys").matches());
		assertTrue(Rules.glob(":*:runSmoke").matcher(":26.3:runSmoke").matches());
		assertFalse(Rules.glob("run.Smoke").matcher("runXSmoke").matches());
	}
}
