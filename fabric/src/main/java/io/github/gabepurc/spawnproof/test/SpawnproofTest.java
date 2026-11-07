package io.github.gabepurc.spawnproof.test;

import java.util.Set;

/**
 * One client game test. List the class in your test mod's fabric.mod.json, under the {@code "spawnproof"} entrypoint, and
 * Spawnproof runs it in a game, records its checks and writes the results ({@code spawnproof/results.json} in the game's
 * folder):
 *
 * <pre>{@code
 * public final class HatTest implements SpawnproofTest {
 *     public void run(TestContext t) {
 *         try (TestSingleplayerContext world = t.game().worldBuilder().create()) {
 *             t.section("A supporter wearing the hat");
 *             t.check("hat drawn", hatDrawn(t.game()));
 *             t.screenshot("front");
 *         }
 *     }
 * }
 * }</pre>
 *
 * <p>A test that throws stops there and counts as an error; the next test still runs. A failed {@link TestContext#check}
 * doesn't stop it, so one run shows every check that fails.
 */
public interface SpawnproofTest {
	void run(TestContext t) throws Exception;

	/**
	 * The name it's picked by ({@code -Dspawnproof.tests=hat}) and listed under in the results: the class name without
	 * "Test", in lower case with dashes. HatTest is "hat", RideFeelTest is "ride-feel".
	 */
	default String name() {
		return Names.of(getClass());
	}

	/** More names to pick it by: {@code -Dspawnproof.tests=cosmetics} runs every test tagged cosmetics. */
	default Set<String> tags() {
		return Set.of();
	}

	/**
	 * False for a test that runs only when picked by name or tag, never in a plain run: a performance run, screenshots for
	 * a store page.
	 */
	default boolean runByDefault() {
		return true;
	}
}
