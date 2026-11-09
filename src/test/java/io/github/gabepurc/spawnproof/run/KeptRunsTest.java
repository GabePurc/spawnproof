package io.github.gabepurc.spawnproof.run;

import io.github.gabepurc.spawnproof.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeptRunsTest {
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

	/** A results folder as Spawnproof for Fabric writes one: test a passes with a screenshot, b fails. */
	private Path results(String name) throws IOException {
		Path out = temp.resolve(name);
		Files.createDirectories(out.resolve("screenshots/a"));
		Files.createDirectories(out.resolve("parts"));
		Files.writeString(out.resolve("screenshots/a/front.png"), "a picture");
		Files.writeString(out.resolve("parts/1.json"), "{}");
		Files.writeString(out.resolve("report.txt"), "## a: PASSED\n");
		Files.writeString(out.resolve("results.json"), """
			{"format": "spawnproof-results", "version": 1, "status": "failed", "startedAt": "2026-11-09T10:00:00Z",
			 "endedAt": "2026-11-09T10:01:00Z", "durationMs": 60000, "counts": {"tests": 2, "passed": 1, "failed": 1},
			 "tests": [
			   {"name": "a", "status": "passed", "checks": [], "screenshots": [{"name": "front", "path": "screenshots/a/front.png"}]},
			   {"name": "b", "status": "failed", "checks": [{"name": "works", "ok": false}]}
			 ]}
			""");
		return out;
	}

	private static Map<String, Object> who(String dir, String task) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("project", "mod");
		m.put("task", task);
		m.put("dir", dir);
		m.put("branch", "main");
		m.put("label", null);
		m.put("how", "test run");
		return m;
	}

	@Test
	void keepsACopyOfTheResultsAndSumsThemUp() throws IOException {
		String id = KeptRuns.keep(results("out"), who("/mod", ":runClientGameTest"), 5);
		assertNotNull(id);
		Path kept = KeptRuns.folder().resolve(id);
		assertTrue(Files.isRegularFile(kept.resolve("screenshots/a/front.png")), "the screenshots are kept");
		assertTrue(Files.isRegularFile(kept.resolve("report.txt")));
		assertFalse(Files.exists(kept.resolve("parts")), "the games' parts are only of use while the run lasts");

		Map<String, Object> run = KeptRuns.read(id);
		assertNotNull(run);
		Map<String, Object> summary = Json.obj(run, "run");
		assertEquals("mod", summary.get("project"));
		assertEquals("failed", summary.get("status"));
		assertEquals(List.of("b"), Json.strings(summary, "notPassed"));
		assertEquals(1, Json.num(summary, "screenshots", -1));
		assertEquals(2, Json.list(Json.obj(run, "results"), "tests").size());
		assertEquals(kept.toAbsolutePath().toString(), run.get("folder"));
	}

	@Test
	void keepsOnlyTheLastFewOfEachTaskInEachCheckout() throws IOException {
		Path out = results("out");
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < 4; i++) ids.add(KeptRuns.keep(out, who("/mod", ":runClientGameTest"), 2));
		String elsewhere = KeptRuns.keep(out, who("/worktree", ":runClientGameTest"), 2);
		String otherTask = KeptRuns.keep(out, who("/mod", ":runSmoke"), 2);

		List<String> left = new ArrayList<>();
		for (Map<String, Object> run : KeptRuns.all()) left.add(Json.str(run, "id"));
		assertEquals(List.of(otherTask, elsewhere, ids.get(3), ids.get(2)), left, "the newest first, two of the first checkout's task");
		assertEquals(List.of(elsewhere), KeptRuns.list("mod", "/worktree", 10).stream().map(r -> Json.str(r, "id")).toList());
		assertEquals(2, KeptRuns.list("mod", null, 2).size());
		assertEquals(0, KeptRuns.list("another", null, 10).size());
	}

	@Test
	void leavesResultsThatArentSpawnproofsAlone() throws IOException {
		Path out = temp.resolve("other");
		Files.createDirectories(out);
		assertNull(KeptRuns.keep(out, who("/mod", ":run"), 5), "no results.json");
		Files.writeString(out.resolve("results.json"), "{\"something\": \"else\"}");
		assertNull(KeptRuns.keep(out, who("/mod", ":run"), 5), "not Spawnproof's format");
		assertTrue(KeptRuns.all().isEmpty());
	}

	@Test
	void readsOnlyRunsByTheirIds() throws IOException {
		String id = KeptRuns.keep(results("out"), who("/mod", ":runClientGameTest"), 5);
		assertTrue(KeptRuns.isId(id));
		for (String bad : new String[] {null, "", "..", "../../config", id + "/../..", "/etc"}) {
			assertFalse(KeptRuns.isId(bad), bad);
			assertNull(KeptRuns.read(bad), bad);
		}
	}
}
