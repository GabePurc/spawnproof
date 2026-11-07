package io.github.gabepurc.spawnproof.selftest;

import io.github.gabepurc.spawnproof.test.SpawnproofTest;
import io.github.gabepurc.spawnproof.test.TestContext;

import java.util.Set;

/** Checks of each kind failing, then a requirement that stops the test: its last check must never be recorded. */
public final class FailingTest implements SpawnproofTest {
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
		t.section("Checks that fail");
		t.check("this one passes", true);
		t.check("this one fails", false, "because it was told to\nover two lines");
		t.check("a number out of range", 3.0, 0.0, 1.0);
		t.checkEqual("two things that differ", "a", "b");
		t.section("A requirement");
		t.require("a requirement that fails", false);
		t.check("never reached", true);
	}
}
