package io.github.gabepurc.spawnproof.selftest;

import io.github.gabepurc.spawnproof.test.SpawnproofTest;
import io.github.gabepurc.spawnproof.test.TestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.util.Set;

/** Throws in a world: an error, the world closes on the way out, and the next test still runs. */
public final class ThrowingTest implements SpawnproofTest {
	@Override
	public Set<String> tags() {
		return Set.of("selftest", "selftest-failing");
	}

	@Override
	public boolean runByDefault() {
		return false;
	}

	@Override
	public void run(TestContext t) {
		try (TestSingleplayerContext world = t.game().worldBuilder().create()) {
			t.check("in a world", t.game().computeOnClient(mc -> mc.level != null));
			throw new IllegalStateException("thrown on purpose");
		}
	}
}
