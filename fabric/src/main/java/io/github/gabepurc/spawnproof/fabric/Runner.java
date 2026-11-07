package io.github.gabepurc.spawnproof.fabric;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.impl.client.gametest.TestInputImpl;
import net.fabricmc.fabric.impl.client.gametest.threading.ThreadingImpl;
import net.fabricmc.fabric.impl.client.gametest.util.WindowHooks;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The one client game test Fabric runs ({@link SpawnproofClient}): it runs every other test, one after another in the same
 * game. Each test starts from the game's default options on the title screen, as Fabric's own runner gives them. One that
 * fails or throws is recorded and the next runs; only one that leaves a world open stops the rest, as nothing after it
 * could start clean. The results are written as the run goes ({@link Results}); at the end the run fails if any test did,
 * which fails the game's exit code and so its Gradle task.
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

		Results r = Results.open(Settings.output(), selection);
		results = r;
		r.plan(picked, all.size() - picked.size());
		Runtime.getRuntime().addShutdownHook(new Thread(() -> r.finish(
			"the game closed while this test ran: it crashed, was stopped, or ran out of time (see logs/latest.log and crash-reports/)"),
			"Spawnproof results"));
		LogCapture logs = LogCapture.install(r);

		List<String> unmatched = selection.unmatched(all);
		if (!unmatched.isEmpty()) {
			r.problem("spawnproof.tests names no test: " + String.join(", ", unmatched) + ". The tests are: " + names(all));
		} else if (all.isEmpty()) {
			r.problem("no tests found: list them in a mod's fabric.mod.json under the \"spawnproof\" entrypoint");
		} else if (picked.isEmpty()) {
			r.problem("no test picked (spawnproof.tests, spawnproof.skip). The tests are: " + names(all));
		}
		say("running " + Results.plural(picked.size(), "test") + (picked.isEmpty() ? "" : ": " + names(picked)) + " (" + Clock.name()
			+ ", graphics " + (Settings.LOW_GRAPHICS ? "low" : "normal") + ")");

		String blocked = null;
		if (!r.tests().isEmpty() && !unmatched.isEmpty()) blocked = "spawnproof.tests names a test that doesn't exist, so nothing ran";
		for (TestRecord record : r.tests()) {
			if (blocked != null) {
				record.notRun(blocked);
				continue;
			}
			r.begin(record);
			game.restoreDefaultGameOptions();
			try {
				record.test.run(game, new Context(game, record, r, logs));
			} catch (TestStopped stopped) {
				record.stopped(stopped.getMessage());
			} catch (Throwable e) {
				record.error(e);
			}
			blocked = cleanUp(game, record);
			r.end(record);
			say(record.test.name() + ": " + record.status + " (" + Results.duration(record.durationMs()) + ", " + record.ticks + " ticks)");
		}
		r.finish(null);
		summarize(r);
		if (r.status().equals("failed")) throw new AssertionError("Spawnproof: " + r.summaryLine() + " See " + r.dir().resolve("report.txt"));
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
