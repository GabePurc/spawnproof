package io.github.gabepurc.spawnproof.run;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestHistoryTest {
	@TempDir
	Path temp;

	@BeforeEach
	void setUp() {
		System.setProperty("spawnproof.home", temp.resolve("home").toString());
	}

	@AfterEach
	void tearDown() {
		System.clearProperty("spawnproof.home");
	}

	private static Map<String, Object> test(String name, String status, long ms) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", name);
		m.put("status", status);
		m.put("durationMs", ms);
		return m;
	}

	@Test
	void handsOutTheLongestFirstAndNewTestsBeforeThem() {
		TestHistory h = TestHistory.load("mod", ":runClientGameTest", "/work/mod");
		h.record(List.of(test("short", "passed", 1_000), test("long", "passed", 60_000), test("middle", "failed", 9_000)));
		h.save();
		TestHistory again = TestHistory.load("mod", ":runClientGameTest", "/work/mod");
		assertEquals(List.of("new", "long", "middle", "short"), again.longestFirst(List.of("short", "new", "middle", "long")));
	}

	@Test
	void remembersWhatFailedUntilItPasses() {
		TestHistory h = TestHistory.load("mod", ":runClientGameTest", "/work/mod");
		h.record(List.of(test("a", "passed", 100), test("b", "failed", 100), test("c", "error", 100)));
		assertEquals(Set.of("b", "c"), h.failed());
		// A rerun of b alone: it passes, and the others keep what they had.
		h.record(List.of(test("b", "passed", 100), test("a", "not-run", 0)));
		assertEquals(Set.of("c"), h.failed());
		assertEquals(3, h.known());
	}

	@Test
	void anotherCheckoutOfTheSameProjectLendsItsTimings() {
		TestHistory main = TestHistory.load("mod", ":runClientGameTest", "/work/mod");
		main.record(List.of(test("a", "passed", 5_000), test("b", "passed", 50_000)));
		main.save();
		TestHistory worktree = TestHistory.load("mod", ":runClientGameTest", "/work/mod/.worktrees/feature");
		assertTrue(worktree.isEmpty(), "failures stay with their checkout");
		assertEquals(50_000, worktree.estimateMs("b"));
		assertEquals(List.of("b", "a"), worktree.longestFirst(List.of("a", "b")));
		TestHistory other = TestHistory.load("another-mod", ":runClientGameTest", "/work/another");
		assertEquals(-1, other.estimateMs("b"), "but not to another project");
	}

	@Test
	void anErrorsTimeIsNotKept() {
		TestHistory h = TestHistory.load("mod", ":runClientGameTest", "/work/mod");
		h.record(List.of(test("a", "passed", 4_000)));
		h.record(List.of(test("a", "error", 300_000)));
		assertEquals(4_000, h.estimateMs("a"));
	}

	@Test
	void findsTheResultsFolderTheBuildGaveTheGame() throws IOException {
		Path run = temp.resolve("run");
		assertEquals(run.resolve("spawnproof"), TestRun.resultsFolder(List.of("java", "-Xmx2G", "Main"), run));
		assertEquals(run.resolve("out"), TestRun.resultsFolder(List.of("java", "-Dspawnproof.output=out", "Main"), run));
		Path args = temp.resolve("args.txt");
		Files.writeString(args, "-Xmx2G\n-Dspawnproof.output=\"/elsewhere/results\"\n-cp a.jar\n");
		assertEquals(Path.of("/elsewhere/results"), TestRun.resultsFolder(List.of("java", "@" + args, "Main"), run));
	}
}
