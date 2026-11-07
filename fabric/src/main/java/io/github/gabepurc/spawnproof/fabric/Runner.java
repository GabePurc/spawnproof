package io.github.gabepurc.spawnproof.fabric;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.impl.client.gametest.TestInputImpl;
import net.fabricmc.fabric.impl.client.gametest.threading.ThreadingImpl;
import net.fabricmc.fabric.impl.client.gametest.util.WindowHooks;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The one client game test Fabric runs ({@link SpawnproofClient}): it runs every other test, one after another in the same
 * game. Each test starts from the game's default options on the title screen, as Fabric's own runner gives them. One that
 * fails or throws is recorded and the next runs; only one that leaves a world open stops the rest, as nothing after it
 * could start clean. The results are written as the run goes ({@link Results}); at the end the run fails if any test did,
 * which fails the game's exit code and so its Gradle task.
 *
 * <p>A game that's one of several in a test run ({@link Dispatch}) runs the tests the run hands it instead, one at a time,
 * and leaves the verdict to the run.
 */
public final class Runner implements FabricClientGameTest {
	private static volatile Results results;

	/** A line in the game's console output. */
	static void say(String line) {
		System.out.println("[spawnproof] " + line);
	}

	/** The stall watch is about to stop the game: what was running gets the blame, then the results are written. */
	static void stalled(long ms) {
		Results r = results;
		if (r != null) r.finish("the game drew no frame for " + ms / 1000 + " s, so it was stopped (its threads are in the log)");
	}

	@Override
	public void runTest(ClientGameTestContext game) {
		Selection selection = new Selection(Settings.tests(), Settings.skip());
		List<Candidate> all = Candidate.findAll(SpawnproofClient.modFilter);
		List<Candidate> picked = new ArrayList<>();
		for (Candidate c : all) if (selection.picks(c)) picked.add(c);
		List<String> unmatched = selection.unmatched(all);
		String blocked = unmatched.isEmpty() ? null : "spawnproof.tests names a test that doesn't exist, so nothing ran";
		Dispatch dispatch = Dispatch.fromEnvironment();

		Results r = Results.open(Settings.output(), selection, dispatch != null);
		results = r;
		// In a test run, this game's results list only the tests it was given.
		r.plan(dispatch == null ? picked : List.of(), dispatch == null ? all.size() - picked.size() : 0);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> r.finish(
			"the game closed while this test ran: it crashed, was stopped, or ran out of time (see logs/latest.log and crash-reports/)"),
			"Spawnproof results"));
		LogCapture logs = LogCapture.install(r);

		List<String> problems = new ArrayList<>();
		if (!unmatched.isEmpty()) {
			problems.add("spawnproof.tests names no test: " + String.join(", ", unmatched) + ". The tests are: " + names(all));
		} else if (all.isEmpty()) {
			problems.add("no tests found: list them in a mod's fabric.mod.json under the \"spawnproof\" entrypoint");
		} else if (picked.isEmpty() && dispatch == null) {
			// (A test run says this itself: rerunning only what failed, it can pick tests the settings don't.)
			problems.add("no test picked (spawnproof.tests, spawnproof.skip). The tests are: " + names(all));
		}
		for (String p : problems) r.problem(p);

		if (dispatch != null) {
			runDispatched(game, dispatch, all, picked, problems, blocked, r, logs);
			return;
		}
		say("running " + Results.plural(picked.size(), "test") + (picked.isEmpty() ? "" : ": " + names(picked)) + " (" + Clock.name()
			+ ", graphics " + (Settings.LOW_GRAPHICS ? "low" : "normal") + ")");
		for (TestRecord record : r.tests()) {
			if (blocked != null) {
				record.notRun(blocked);
				continue;
			}
			blocked = runOne(game, record, r, logs);
		}
		r.finish(null);
		summarize(r);
		if (r.status().equals("failed")) throw new AssertionError("Spawnproof: " + r.summaryLine() + " See " + r.dir().resolve("report.txt"));
	}

	/**
	 * One game of a test run: it takes one test at a time from the run until there are none left. The run merges every
	 * game's results and fails the build if a test failed, so this game ends cleanly either way: a game that exits with an
	 * error is one that crashed.
	 */
	private static void runDispatched(ClientGameTestContext game, Dispatch dispatch, List<Candidate> all, List<Candidate> picked,
		List<String> problems, String blocked, Results r, LogCapture logs) {
		say("game " + dispatch.game + " of a test run: taking tests from it one at a time (" + Clock.name() + ", graphics "
			+ (Settings.LOW_GRAPHICS ? "low" : "normal") + ")");
		try {
			dispatch.hello(all, picked, problems, blocked, r.runInfo());
		} catch (IOException e) {
			throw new IllegalStateException("Spawnproof: couldn't reach the test run that started this game: " + e.getMessage(), e);
		}
		String name;
		while ((name = dispatch.next()) != null) {
			Candidate test = null;
			for (Candidate c : all) if (c.name().equals(name)) test = c;
			if (test == null) {
				// (The run only hands out tests this game listed.)
				dispatch.done(name, null, null);
				continue;
			}
			TestRecord record = r.add(test);
			String leaving = runOne(game, record, r, logs);
			dispatch.done(name, r.writePart(record), leaving);
			if (leaving != null) break;
		}
		r.finish(null);
		dispatch.close();
	}

	/** Runs one test and cleans up after it. Returns null if the next test can start, or why not. */
	private static String runOne(ClientGameTestContext game, TestRecord record, Results r, LogCapture logs) {
		r.begin(record);
		game.restoreDefaultGameOptions();
		try {
			record.test.run(game, new Context(game, record, r, logs));
		} catch (TestStopped stopped) {
			record.stopped(stopped.getMessage());
		} catch (Throwable e) {
			record.error(e);
		}
		String blocked = cleanUp(game, record);
		r.end(record);
		say(record.test.name() + ": " + record.status + " (" + Results.duration(record.durationMs()) + ", " + record.ticks + " ticks)");
		return blocked;
	}

	/**
	 * What Fabric's runner does after each test: keys up, the window's size and the cursor back. Then whether the next test
	 * can start: null if so, why not if not.
	 */
	private static String cleanUp(ClientGameTestContext game, TestRecord record) {
		try {
			if (game.getInput() instanceof TestInputImpl input) input.clearKeysDown();
			game.runOnClient(mc -> ((WindowHooks) (Object) mc.getWindow()).fabric_resetSize());
			int width = game.computeOnClient(mc -> mc.getWindow().getScreenWidth());
			int height = game.computeOnClient(mc -> mc.getWindow().getScreenHeight());
			game.getInput().setCursorPos(width * 0.5, height * 0.5);
		} catch (Throwable e) {
			record.problem("couldn't reset the game after the test: " + e);
		}
		if (ThreadingImpl.isServerRunning) {
			record.problem("the test ended with a server still running (close the world or server it started, with try-with-resources)");
			return "an earlier test (" + record.test.name() + ") left a server running";
		}
		boolean inWorld = game.computeOnClient(mc -> mc.level != null);
		if (inWorld) {
			record.problem("the test ended still in a world (close the world it opened, with try-with-resources)");
			return "an earlier test (" + record.test.name() + ") left the game in a world";
		}
		Screen screen = game.computeOnClient(mc -> mc.gui.screen());
		if (!(screen instanceof TitleScreen)) {
			record.note("(the test ended on " + (screen == null ? "no screen" : screen.getClass().getSimpleName()) + ": back to the title screen)");
			game.setScreen(() -> new TitleScreen());
		}
		return null;
	}

	/** The run in a few lines, at the end of the console output: what failed, and where to look. */
	private static void summarize(Results r) {
		say("----");
		say(r.summaryLine());
		for (TestRecord t : r.tests()) {
			if (t.status.equals(TestRecord.PASSED)) continue;
			if (t.status.equals(TestRecord.NOT_RUN)) {
				say("NOT RUN " + t.test.name() + ": " + t.stopReason);
				continue;
			}
			int shown = 0;
			for (Map<String, Object> c : t.checks) {
				if (Boolean.TRUE.equals(c.get("ok"))) continue;
				if (shown++ == 10) {
					say("  ... " + (t.failedChecks - 10) + " more failed checks in " + t.test.name());
					break;
				}
				Object section = c.get("section");
				say("FAIL " + t.test.name() + (section != null ? " > " + section : "") + " > " + c.get("name") + describe(c));
			}
			if (t.stopReason != null && !t.stopReason.startsWith("required check failed: ")) say("STOPPED " + t.test.name() + ": " + t.stopReason);
			if (t.error != null) {
				String stack = String.valueOf(t.error.getOrDefault("stack", ""));
				String at = stack.lines().map(String::strip).filter(l -> l.startsWith("at ")).findFirst().orElse("");
				say("ERROR " + t.test.name() + ": " + t.error.get("type") + ": " + t.error.get("message") + (at.isEmpty() ? "" : " (" + at + ")"));
			}
		}
		say("Results: " + r.dir().resolve("results.json") + " (report.txt, junit.xml, screenshots/ beside it)");
	}

	private static String describe(Map<String, Object> c) {
		if (c.containsKey("value")) return String.format(java.util.Locale.ROOT, " = %.3f (target %.3f..%.3f)", c.get("value"), c.get("min"), c.get("max"));
		if (c.containsKey("expected")) return ": expected " + c.get("expected") + ", got " + c.get("actual");
		if (c.containsKey("detail")) return ": " + String.valueOf(c.get("detail")).lines().findFirst().orElse("");
		return "";
	}

	private static String names(List<Candidate> tests) {
		List<String> out = new ArrayList<>();
		for (Candidate c : tests) out.add(c.runByDefault() ? c.name() : c.name() + " (on request)");
		return String.join(", ", out);
	}
}
