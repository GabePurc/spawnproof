package io.github.gabepurc.spawnproof.daemon;

import io.github.gabepurc.spawnproof.Json;
import io.github.gabepurc.spawnproof.run.KeptRuns;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reruns, with a stand-in for the mod's Gradle wrapper: it notes what it was asked to run, waits while its folder has a
 * file named hold, and fails if there's one named fail.txt.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class RerunsTest {
	@TempDir
	Path temp;
	private Path home;
	private Path mod;
	private Reruns reruns;

	@BeforeEach
	void setUp() throws IOException {
		home = temp.resolve("home");
		System.setProperty("spawnproof.home", home.toString());
		mod = temp.resolve("mod");
		Files.createDirectories(mod);
		Files.writeString(mod.resolve("gradlew"), String.join("\n",
			"#!/bin/sh",
			"echo \"fake gradle $*\"",
			"echo \"$*\" > args.txt",
			"while [ -f hold ]; do sleep 0.1; done",
			"[ ! -f fail.txt ]",
			""));
		Files.setPosixFilePermissions(mod.resolve("gradlew"), PosixFilePermissions.fromString("rwxr-xr-x"));
		reruns = new Reruns(message -> {}, home.resolve("state/watch"));
	}

	@AfterEach
	void tearDown() {
		reruns.stopAll();
		System.clearProperty("spawnproof.home");
	}

	/** A kept run of two tests in {@code dir}: a passed, and b passed or failed. */
	private String keptRun(Path dir, boolean bPassed) throws IOException {
		Path out = temp.resolve("results-" + System.nanoTime());
		Files.createDirectories(out);
		Files.writeString(out.resolve("results.json"), """
			{"format": "spawnproof-results", "version": 1, "status": "%s", "startedAt": "2026-11-09T10:00:00Z",
			 "endedAt": "2026-11-09T10:01:00Z", "durationMs": 60000, "counts": {"tests": 2},
			 "tests": [{"name": "a", "status": "passed"}, {"name": "b", "status": "%s"}]}
			""".formatted(bPassed ? "passed" : "failed", bPassed ? "passed" : "failed"));
		Map<String, Object> who = new LinkedHashMap<>();
		who.put("project", "mod");
		who.put("task", ":runClientGameTest");
		who.put("dir", dir.toString());
		who.put("branch", "main");
		who.put("how", "test run");
		return KeptRuns.keep(out, who, 20);
	}

	private static long id(Map<String, Object> reply) {
		return Json.num(Json.obj(reply, "rerun"), "id", -1);
	}

	/** The rerun as the status lists it, once it's no longer running. */
	private Map<String, Object> finished(long id) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 20_000;
		while (true) {
			for (Map<String, Object> r : reruns.describe()) {
				if (Json.num(r, "id", -1) == id && !"running".equals(r.get("state"))) return r;
			}
			if (System.currentTimeMillis() > deadline) throw new AssertionError("the rerun didn't finish: " + reruns.describe());
			Thread.sleep(50);
		}
	}

	private String args() throws IOException {
		return Files.readString(mod.resolve("args.txt")).trim();
	}

	@Test
	void runsEveryTestAgainInTheRunsFolder() throws Exception {
		Map<String, Object> reply = reruns.start(keptRun(mod, false), List.of(), false);
		assertEquals("ok", reply.get("type"));
		Map<String, Object> done = finished(id(reply));
		assertEquals("passed", done.get("state"));
		assertEquals("every test", done.get("what"));
		assertEquals(":runClientGameTest -Pspawnproof.games", args(), "spawnproof test, with the run's task");
		List<String> console = Json.strings(reruns.console(id(reply), 50), "lines");
		assertTrue(console.stream().anyMatch(l -> l.startsWith("fake gradle ")), "the build's console is kept: " + console);
		assertTrue(reruns.running().isEmpty());
	}

	@Test
	void runsTheTestsThatDidNotPassOrTheOnesNamed() throws Exception {
		String run = keptRun(mod, false);
		Map<String, Object> done = finished(id(reruns.start(run, List.of(), true)));
		assertEquals("b, which didn't pass", done.get("what"));
		assertEquals(":runClientGameTest -Pspawnproof.games -Pspawnproof.tests=b", args());

		finished(id(reruns.start(run, List.of("a", "b"), false)));
		assertEquals(":runClientGameTest -Pspawnproof.games -Pspawnproof.tests=a,b", args());
	}

	@Test
	void aBuildThatFailsIsARerunThatFailed() throws Exception {
		Files.writeString(mod.resolve("fail.txt"), "");
		assertEquals("failed", finished(id(reruns.start(keptRun(mod, false), List.of(), false))).get("state"));
	}

	@Test
	void oneAtATimeInAFolderAndItCanBeStopped() throws Exception {
		Files.writeString(mod.resolve("hold"), "");
		String run = keptRun(mod, false);
		long id = id(reruns.start(run, List.of(), false));
		assertEquals(List.of("a rerun of mod :runClientGameTest"), reruns.running(), "the daemon stays up for it");
		IOException again = assertThrows(IOException.class, () -> reruns.start(run, List.of("a"), false));
		assertTrue(again.getMessage().contains("is being run again already"), again.getMessage());

		reruns.stop(id, "stopped from the dashboard");
		Map<String, Object> done = finished(id);
		assertEquals("stopped", done.get("state"));
		assertEquals("stopped from the dashboard", done.get("stoppedBecause"));
		assertThrows(IOException.class, () -> reruns.stop(id, "again"), "it's not running any more");
	}

	@Test
	void saysWhyARunCannotBeRunAgain() throws Exception {
		assertTrue(assertThrows(IOException.class, () -> reruns.start("20261109-120000-000-abcd", List.of(), false)).getMessage().contains("isn't kept any more"));

		String allPassed = keptRun(mod, true);
		assertTrue(assertThrows(IOException.class, () -> reruns.start(allPassed, List.of(), true)).getMessage().contains("every test passed"));
		assertTrue(assertThrows(IOException.class, () -> reruns.start(allPassed, List.of("a,b"), false)).getMessage().contains("can't be picked by its name"));

		// A pull request's run, made in Spawnproof's clone of a watched repo, which the next pull request's test changes.
		Path clone = home.resolve("state/watch/test/mod/checkout");
		Files.createDirectories(clone);
		String ofPullRequest = keptRun(clone, false);
		assertTrue(assertThrows(IOException.class, () -> reruns.start(ofPullRequest, List.of(), false)).getMessage().contains("pull request of test/mod"));

		String gone = keptRun(temp.resolve("deleted"), false);
		assertTrue(assertThrows(IOException.class, () -> reruns.start(gone, List.of(), false)).getMessage().contains("isn't there any more"));

		Path bare = temp.resolve("no-wrapper");
		Files.createDirectories(bare);
		String noWrapper = keptRun(bare, false);
		assertTrue(assertThrows(IOException.class, () -> reruns.start(noWrapper, List.of(), false)).getMessage().contains("no Gradle wrapper"));
		assertFalse(Files.exists(bare.resolve("args.txt")));
		assertTrue(reruns.describe().isEmpty(), "nothing was started");
	}
}
