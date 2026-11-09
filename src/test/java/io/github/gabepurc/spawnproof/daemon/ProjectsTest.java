package io.github.gabepurc.spawnproof.daemon;

import io.github.gabepurc.spawnproof.Json;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProjectsTest {
	private static final Path WATCH = Path.of("/state/watch");

	private static Map<String, Object> run(String id, String project, String dir, String branch, String endedAt) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", id);
		m.put("project", project);
		m.put("dir", dir);
		m.put("branch", branch);
		m.put("endedAt", endedAt);
		return m;
	}

	private static Map<String, Object> game(String project, String dir, String branch, long endedAt) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("project", project);
		m.put("dir", dir);
		m.put("branch", branch);
		m.put("endedAt", endedAt);
		return m;
	}

	@Test
	void groupsRunsAndGamesByProjectAndCheckout() {
		List<Map<String, Object>> runs = List.of(
			run("r3", "horsing-around", "/state/watch/GabePurc/horsing-around/checkout", null, "2026-11-09T12:00:00Z"),
			run("r2", "horsing-around", "/code/ha", "main", "2026-11-09T11:00:00Z"),
			run("r1", "horsing-around", "/code/ha", "old-branch", "2026-11-09T09:00:00Z"));
		long tenAm = java.time.Instant.parse("2026-11-09T10:00:00Z").toEpochMilli();
		long onePm = java.time.Instant.parse("2026-11-09T13:00:00Z").toEpochMilli();
		List<Map<String, Object>> games = List.of(
			game("spawnproof", "/code/spawnproof", "17-dashboard", onePm),
			game("horsing-around", "/code/ha-worktree", "100-hats", tenAm),
			game("?", "/somewhere", null, onePm));

		List<Map<String, Object>> projects = Projects.list(runs, games, WATCH);
		assertEquals(List.of("spawnproof", "horsing-around"), projects.stream().map(p -> Json.str(p, "name")).toList(),
			"the most recently busy first; a game with no project isn't one");

		Map<String, Object> ha = projects.get(1);
		assertEquals(3, Json.num(ha, "runs", -1));
		assertEquals("r3", Json.str(Json.obj(ha, "lastRun"), "id"));
		List<Map<String, Object>> checkouts = Json.list(ha, "checkouts");
		assertEquals(List.of("/state/watch/GabePurc/horsing-around/checkout", "/code/ha", "/code/ha-worktree"),
			checkouts.stream().map(c -> Json.str(c, "dir")).toList());
		assertEquals("GabePurc/horsing-around", checkouts.get(0).get("watch"));
		assertEquals("main", checkouts.get(1).get("branch"), "the newest run says which branch a checkout is on");
		assertNull(checkouts.get(1).get("watch"));
		assertEquals(0, Json.num(projects.get(0), "runs", -1), "a project with games but no kept runs");
	}

	@Test
	void tellsAWatchedRepoFromItsClonesFolder() {
		assertEquals("owner/repo", Projects.watchedRepo("/state/watch/owner/repo/checkout/fabric", WATCH));
		assertNull(Projects.watchedRepo("/state/watch/owner", WATCH));
		assertNull(Projects.watchedRepo("/code/mod", WATCH));
	}
}
