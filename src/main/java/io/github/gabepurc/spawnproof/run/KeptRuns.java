package io.github.gabepurc.spawnproof.run;

import io.github.gabepurc.spawnproof.Dirs;
import io.github.gabepurc.spawnproof.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Past test runs' results, kept in Spawnproof's state folder ({@code runs/}) for the dashboard to look back on. Loom clears
 * the run folder before every run, so without a copy a run's results and screenshots would be gone by the next one.
 *
 * <p>Each run gets a folder of its own, named so that sorting by name sorts by time: a copy of its results folder (results.json,
 * report.txt, junit.xml, the screenshots and files), and {@code run.json}, which says whose run it was and sums it up, so a
 * list of runs needn't read every results file. Only the last few runs of each task in each checkout are kept.
 *
 * <p>Test runs, the plugin and the daemon all use this from processes of their own, so a run is put together in a folder
 * of its own and moved into place whole, and a run that disappears while it's read (trimmed by another process) is skipped.
 */
public final class KeptRuns {
	/** How many runs of each task in each checkout are kept when the settings don't say. */
	public static final int DEFAULT_KEEP = 20;
	private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
	private static final Pattern ID = Pattern.compile("\\d{8}-\\d{6}-\\d{3}-[0-9a-f]{4}");
	/** In a results folder, but only of use while its run lasts. */
	private static final List<String> NOT_KEPT = List.of("parts");
	private static long lastIdMillis;

	private KeptRuns() {}

	public static Path folder() {
		return Dirs.state().resolve("runs");
	}

	/** Whether this is the name of a kept run: anything else asked for (a path, say) is turned down. */
	public static boolean isId(String id) {
		return id != null && ID.matcher(id).matches();
	}

	/**
	 * Keeps a copy of the results in {@code results} (a results folder, with results.json in it), then lets go of this task's
	 * oldest runs in this checkout beyond {@code keep}. {@code who} says whose run it was: project, task, dir, branch, label
	 * and how it ran. Returns the run's id, or null with no results to keep. Never throws: a run that can't be kept is still
	 * a run that happened, and it says so on the console.
	 */
	public static String keep(Path results, Map<String, Object> who, int keep) {
		Path json = results.resolve("results.json");
		Map<String, Object> data;
		try {
			if (!Files.isRegularFile(json)) return null;
			data = Json.parseObject(Files.readString(json, StandardCharsets.UTF_8));
		} catch (IOException | IllegalArgumentException e) {
			return null; // Not Spawnproof's results, or cut short: nothing worth keeping.
		}
		if (!"spawnproof-results".equals(Json.str(data, "format"))) return null;
		Path root = folder();
		String id = newId();
		Path temp = null;
		try {
			Files.createDirectories(root);
			temp = Files.createTempDirectory(root, ".keeping-");
			copyResults(results, temp);
			Files.writeString(temp.resolve("run.json"), Json.pretty(summary(id, who, data)), StandardCharsets.UTF_8);
			Files.move(temp, root.resolve(id), StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException | RuntimeException e) {
			System.out.println("[spawnproof] couldn't keep this run's results for the dashboard in " + root + ": " + e.getMessage());
			if (temp != null) deleteTree(temp);
			return null;
		}
		trim(Json.str(who, "dir"), Json.str(who, "task"), keep);
		return id;
	}

	/**
	 * The time to the millisecond, then a few random letters for runs that end at the same moment in other processes. In
	 * this one, each id comes after the last, so runs kept in quick succession still sort in order.
	 */
	private static synchronized String newId() {
		long now = Math.max(System.currentTimeMillis(), lastIdMillis + 1);
		lastIdMillis = now;
		LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault());
		return time.format(ID_TIME) + "-" + String.format("%04x", ThreadLocalRandom.current().nextInt(0x10000));
	}

		/** What run.json holds: whose run it was, and enough of how it went to list it. */
	static Map<String, Object> summary(String id, Map<String, Object> who, Map<String, Object> data) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", id);
		for (String key : List.of("project", "task", "dir", "branch", "label", "how")) m.put(key, who.get(key));
		m.put("status", Json.str(data, "status"));
		m.put("startedAt", Json.str(data, "startedAt"));
		m.put("endedAt", Json.str(data, "endedAt"));
		m.put("durationMs", Json.num(data, "durationMs", 0));
		m.put("counts", Json.obj(data, "counts"));
		List<String> failed = new ArrayList<>();
		int screenshots = 0;
		for (Map<String, Object> t : Json.list(data, "tests")) {
			if (!"passed".equals(Json.str(t, "status"))) failed.add(Json.str(t, "name"));
			screenshots += Json.list(t, "screenshots").size();
		}
		m.put("notPassed", failed);
		m.put("screenshots", screenshots);
		return m;
	}

	private static void copyResults(Path from, Path to) throws IOException {
		Files.walkFileTree(from, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				Path rel = from.relativize(dir);
				if (rel.getNameCount() == 1 && NOT_KEPT.contains(rel.toString())) return FileVisitResult.SKIP_SUBTREE;
				Files.createDirectories(to.resolve(rel.toString()));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				if (attrs.isRegularFile()) {
					Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
				}
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/** Lets go of the oldest runs of a task in a checkout, so that {@code keep} are left. */
	static void trim(String dir, String task, int keep) {
		List<Map<String, Object>> same = new ArrayList<>();
		for (Map<String, Object> run : all()) {
			if (String.valueOf(dir).equals(String.valueOf(run.get("dir"))) && String.valueOf(task).equals(String.valueOf(run.get("task")))) same.add(run);
		}
		for (int i = Math.max(1, keep); i < same.size(); i++) deleteTree(folder().resolve(Json.str(same.get(i), "id")));
	}

	/** Every kept run's run.json, the newest first. */
	public static List<Map<String, Object>> all() {
		List<Map<String, Object>> out = new ArrayList<>();
		List<Path> folders = new ArrayList<>();
		try (Stream<Path> list = Files.list(folder())) {
			list.filter(p -> isId(p.getFileName().toString())).forEach(folders::add);
		} catch (IOException e) {
			return out; // None kept yet.
		}
		folders.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
		for (Path f : folders) {
			Map<String, Object> run = readJson(f.resolve("run.json"));
			if (run != null) out.add(run);
		}
		return out;
	}

	/** The newest runs, of one project or one checkout if asked (null for any), at most {@code limit}. */
	public static List<Map<String, Object>> list(String project, String dir, int limit) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> run : all()) {
			if (out.size() >= limit) break;
			if (project != null && !project.equals(run.get("project"))) continue;
			if (dir != null && !dir.equals(run.get("dir"))) continue;
			out.add(run);
		}
		return out;
	}

	/** One run: its run.json, its results.json, and its folder, where the screenshots' paths start. Null if it's gone. */
	public static Map<String, Object> read(String id) {
		if (!isId(id)) return null;
		Path dir = folder().resolve(id);
		Map<String, Object> run = readJson(dir.resolve("run.json"));
		Map<String, Object> results = readJson(dir.resolve("results.json"));
		if (run == null || results == null) return null;
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("run", run);
		m.put("results", results);
		m.put("folder", dir.toAbsolutePath().toString());
		return m;
	}

	private static Map<String, Object> readJson(Path file) {
		try {
			return Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
		} catch (IOException | IllegalArgumentException e) {
			return null;
		}
	}

	private static void deleteTree(Path dir) {
		try {
			MergedResults.deleteTree(dir);
		} catch (IOException ignored) {
			// Another process may be deleting it too; what's left goes next time.
		}
	}
}
