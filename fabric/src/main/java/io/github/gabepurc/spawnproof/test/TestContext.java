package io.github.gabepurc.spawnproof.test;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.nio.file.Path;

/**
 * What a test is given: the game, and ways to record what it finds. Everything it records goes into the run's results, in
 * order: {@code results.json} for programs, {@code report.txt} for people, {@code junit.xml} for CI.
 */
public interface TestContext {
	/** Fabric's test context: making worlds, waiting for ticks, pressing keys, running code on the client. */
	ClientGameTestContext game();

	/** This test's name ({@link SpawnproofTest#name()}). */
	String name();

	/** Starts a part of the test. The checks after it are listed under it, until the next section. */
	void section(String name);

	/** A check, recorded either way. The test goes on when it fails. Returns {@code ok}, to act on it. */
	boolean check(String name, boolean ok);

	/** A check with what went wrong when it fails ("server corrected 3 moves: ..."), recorded with it. */
	boolean check(String name, boolean ok, String detailIfFailed);

	/** A check on a number: passes when {@code min <= value <= max}. The value is recorded either way, to compare runs. */
	boolean check(String name, double value, double min, double max);

	/** A check that two things are equal; when not, both are recorded ("expected 0x8e2b2b, got 0x2b8e2b"). */
	boolean checkEqual(String name, Object expected, Object actual);

	/** A check the rest of the test needs (the horse spawned): when it fails, the test stops there. */
	void require(String name, boolean ok);

	/** Stops the test as failed, for a reason. */
	void fail(String reason);

	/** A line in the report, under the current section. */
	void note(String format, Object... args);

	/** Takes a screenshot, listed with the test's results; returns where it's saved ({@code screenshots/<test>/<name>.png}). */
	Path screenshot(String name);

	/** A file for the test's own output (a trace, a table of numbers), in {@code files/<test>/}; listed with the results. */
	Path file(String name);

	/** Starts collecting the game log's warnings and errors, until closed: to check none were logged ("moved wrongly"). */
	LogWatch watchLog();
}
