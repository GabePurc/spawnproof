package io.github.gabepurc.spawnproof.watch;

import io.github.gabepurc.spawnproof.Json;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportTest {
	private static Map<String, Object> results(String json) {
		return Json.parseObject(json);
	}

	private static final String PASSED = """
		{"status":"passed","counts":{"tests":3,"passed":3,"failed":0,"errors":0,"notRun":0,"checks":9,"failedChecks":0},
		 "tests":[{"name":"a","status":"passed","checks":[]}]}""";

	private static final String FAILED = """
		{"status":"failed","counts":{"tests":3,"passed":1,"failed":1,"errors":1,"notRun":0,"checks":4,"failedChecks":1},
		 "tests":[
		  {"name":"ride","status":"failed","failedChecks":1,"checks":[{"name":"on the horse","section":"Mounting","ok":false,"expected":true,"actual":false}]},
		  {"name":"hat","status":"error","checks":[],"error":{"type":"java.lang.IllegalStateException","message":"no hat","stack":"x\\n\\tat Hat.run(Hat.java:3)"}},
		  {"name":"fine","status":"passed","checks":[]}]}""";

	@Test
	void allPassing() {
		Report.Outcome o = Report.of(0, List.of(results(PASSED)), List.of("/x/results.json"), List.of(), "/x/log", 65_000);
		assertEquals("success", o.state());
		assertEquals("All 3 tests passed", o.summary());
		assertEquals("All 3 tests passed, on box in 1m 05s", Report.description(o, "box"));
		assertTrue(o.failures().isEmpty());
	}

	@Test
	void failuresAreCountedAndListed() {
		Report.Outcome o = Report.of(1, List.of(results(FAILED)), List.of("/x/results.json"), List.of(), "/x/log", 1000);
		assertEquals("failure", o.state());
		assertEquals("2 of 3 tests failed", o.summary());
		assertEquals(List.of("FAIL ride > Mounting > on the horse: expected true, got false",
			"ERROR hat: java.lang.IllegalStateException: no hat (at Hat.run(Hat.java:3))"), o.failures());
		assertTrue(o.buildLines().isEmpty(), "the tests failed, not the build");
	}

	@Test
	void severalTestRunsAddUp() {
		Report.Outcome o = Report.of(1, List.of(results(PASSED), results(FAILED)), List.of("/a", "/b"), List.of(), null, 1000);
		assertEquals("2 of 6 tests failed", o.summary());
	}

	@Test
	void aBuildThatFailsBeforeTheTests() {
		List<String> console = List.of("> Task :compileJava FAILED", "", "FAILURE: Build failed with an exception.", "", "* What went wrong:",
			"Execution failed for task ':compileJava'.", "> Compilation failed; see the compiler output below.", "", "* Try:",
			"> Run with --stacktrace", "BUILD FAILED in 3s");
		Report.Outcome o = Report.of(1, List.of(), List.of(), console, "/x/log", 3000);
		assertEquals("failure", o.state());
		assertEquals("The build failed before the tests ran", o.summary());
		assertEquals(List.of("Execution failed for task ':compileJava'.", "> Compilation failed; see the compiler output below."), o.buildLines());
	}

	@Test
	void testsThatPassInABuildThatFails() {
		Report.Outcome o = Report.of(1, List.of(results(PASSED)), List.of("/x"), List.of("* What went wrong:", "a later task failed", "* Try:"), null, 1);
		assertEquals("failure", o.state());
		assertEquals("The tests passed, but the build failed", o.summary());
		assertEquals(List.of("a later task failed"), o.buildLines());
	}

	@Test
	void withoutAWhatWentWrongTheConsoleEndIsShown() {
		List<String> console = new ArrayList<>();
		for (int i = 0; i < 50; i++) console.add("line " + i);
		List<String> lines = Report.whatWentWrong(console);
		assertEquals(20, lines.size());
		assertEquals("line 49", lines.get(19));
	}

	@Test
	void theComment() {
		String home = System.getProperty("user.home");
		Report.Outcome o = Report.of(1, List.of(results(FAILED)), List.of(home + "/state/results.json"), List.of(), home + "/state/pr.log", 2000);
		String comment = Report.comment(o, "0123456789abcdef", "box");
		assertTrue(comment.startsWith(GitHub.MARKER + "\n"), comment);
		assertTrue(comment.contains("**Spawnproof: 2 of 3 tests failed** at `0123456`, tested on box in 2s."), comment);
		assertTrue(comment.contains("```text\nFAIL ride > Mounting > on the horse: expected true, got false\n"), comment);
		assertTrue(comment.contains("`~/state/results.json`"), "home folders are written from ~: " + comment);
		assertFalse(comment.contains(home + "/"), comment);
		assertTrue(comment.contains("The build's console: `~/state/pr.log`"), comment);
	}

	@Test
	void anErrorSaysWhy() {
		Report.Outcome o = Report.error("Couldn't clone test/mod");
		assertEquals("error", o.state());
		assertEquals("Couldn't clone test/mod", Report.description(o, "box"));
	}

	@Test
	void repoFromRemoteAddresses() {
		assertEquals("GabePurc/horsing-around", GitHub.repoFromUrl("https://github.com/GabePurc/horsing-around.git"));
		assertEquals("GabePurc/horsing-around", GitHub.repoFromUrl("https://github.com/GabePurc/horsing-around"));
		assertEquals("GabePurc/spawnproof", GitHub.repoFromUrl("git@github.com:GabePurc/spawnproof.git"));
		assertEquals("a-b/c.d", GitHub.repoFromUrl("ssh://git@github.com/a-b/c.d.git"));
		assertNull(GitHub.repoFromUrl("https://gitlab.com/someone/mod.git"));
		assertNull(GitHub.repoFromUrl(null));
	}

	@Test
	void repoNames() {
		assertTrue(Watch.validRepo("GabePurc/horsing-around"));
		assertFalse(Watch.validRepo("../etc"));
		assertFalse(Watch.validRepo("a/.."));
		assertFalse(Watch.validRepo("a/b/c"));
		assertFalse(Watch.validRepo(null));
	}
}
