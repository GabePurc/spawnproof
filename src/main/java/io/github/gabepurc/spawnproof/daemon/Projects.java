package io.github.gabepurc.spawnproof.daemon;

import io.github.gabepurc.spawnproof.Json;

import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The mods Spawnproof knows of, for the dashboard: from the test runs it kept and the games it ran. A project is a Gradle
 * build's name; each folder it was built in (a checkout, a worktree, a watched repo's clone) is one of its checkouts.
 */
final class Projects {
	private Projects() {}

	/**
	 * {@code runs} are kept runs' run.json, the newest first; {@code games} the history's games, the newest first; and
	 * {@code watchRoot} the folder watched repos are cloned in, to tell their checkouts apart.
	 */
	static List<Map<String, Object>> list(List<Map<String, Object>> runs, List<Map<String, Object>> games, Path watchRoot) {
		Map<String, Map<String, Object>> projects = new LinkedHashMap<>();
		for (Map<String, Object> run : runs) {
			Map<String, Object> p = project(projects, Json.str(run, "project"));
			if (p == null) continue;
			p.putIfAbsent("lastRun", run);
			p.put("runs", Json.num(p, "runs", 0) + 1);
			seen(p, run, millis(Json.str(run, "endedAt")), watchRoot);
		}
		for (Map<String, Object> game : games) {
			Map<String, Object> p = project(projects, Json.str(game, "project"));
			if (p != null) seen(p, game, Json.num(game, "endedAt", 0), watchRoot);
		}
		List<Map<String, Object>> out = new ArrayList<>(projects.values());
		for (Map<String, Object> p : out) {
			@SuppressWarnings("unchecked")
			Map<String, Map<String, Object>> checkouts = (Map<String, Map<String, Object>>) p.remove("byDir");
			List<Map<String, Object>> list = new ArrayList<>(checkouts.values());
			list.sort(Comparator.comparing((Map<String, Object> c) -> Json.num(c, "lastAt", 0)).reversed());
			p.put("checkouts", list);
		}
		out.sort(Comparator.comparing((Map<String, Object> p) -> Json.num(p, "lastAt", 0)).reversed());
		return out;
	}

	private static Map<String, Object> project(Map<String, Map<String, Object>> projects, String name) {
		if (name == null || name.isBlank() || name.equals("?")) return null;
		return projects.computeIfAbsent(name, n -> {
			Map<String, Object> p = new LinkedHashMap<>();
			p.put("name", n);
			p.put("runs", 0L);
			p.put("lastAt", 0L);
			p.put("byDir", new LinkedHashMap<String, Map<String, Object>>());
			return p;
		});
	}

	/** A run or game in one of the project's checkouts: the newest one says what branch it's on. */
	private static void seen(Map<String, Object> project, Map<String, Object> entry, long at, Path watchRoot) {
		String dir = Json.str(entry, "dir");
		if (dir == null) return;
		project.put("lastAt", Math.max(Json.num(project, "lastAt", 0), at));
		@SuppressWarnings("unchecked")
		Map<String, Map<String, Object>> byDir = (Map<String, Map<String, Object>>) project.get("byDir");
		Map<String, Object> c = byDir.get(dir);
		if (c == null) {
			c = new LinkedHashMap<>();
			c.put("dir", dir);
			c.put("lastAt", 0L);
			String repo = watchedRepo(dir, watchRoot);
			if (repo != null) c.put("watch", repo);
			byDir.put(dir, c);
		}
		if (at >= Json.num(c, "lastAt", 0)) {
			c.put("lastAt", at);
			c.put("branch", entry.get("branch"));
			c.put("label", entry.get("label"));
		}
	}

	/** "owner/repo" for a folder in a watched repo's clone ({@code <watchRoot>/<owner>/<repo>/checkout}), or null. */
	static String watchedRepo(String dir, Path watchRoot) {
		try {
			Path rel = watchRoot.toAbsolutePath().normalize().relativize(Path.of(dir).toAbsolutePath().normalize());
			if (rel.getNameCount() < 2 || rel.startsWith("..")) return null;
			return rel.getName(0) + "/" + rel.getName(1);
		} catch (IllegalArgumentException e) { // Not a path, or on another drive (Windows).
			return null;
		}
	}

	private static long millis(String instant) {
		try {
			return instant == null ? 0 : Instant.parse(instant).toEpochMilli();
		} catch (DateTimeParseException e) {
			return 0;
		}
	}
}
