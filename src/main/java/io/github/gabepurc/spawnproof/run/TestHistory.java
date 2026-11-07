package io.github.gabepurc.spawnproof.run;

import io.github.gabepurc.spawnproof.Dirs;
import io.github.gabepurc.spawnproof.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * How each test went in a game task's earlier test runs: how long it took, for handing the longest out first, and whether
 * it passed, for rerunning only what failed. One file per task in each checkout, in Spawnproof's state folder
 * ({@code tests/}): the run folder is cleared before every run, so it can't live there.
 *
 * <p>Past failures belong to one checkout, but timings carry over: a checkout with none of its own (a new worktree) goes by
 * another checkout of the same project.
 */
final class TestHistory {
	/** Durations kept for each test; its estimate is their median. */
	private static final int KEEP = 5;

	private final Path file;
	private final String project;
	private final String task;
	private final String dir;
	private final Map<String, Map<String, Object>> tests = new LinkedHashMap<>();
	private String updatedAt;
	/** Durations from other checkouts of the same project, for tests this one hasn't timed. */
	private Map<String, Map<String, Object>> elsewhere;

	private TestHistory(Path file, String project, String task, String dir) {
		this.file = file;
		this.project = project;
		this.task = task;
		this.dir = dir;
	}

	static TestHistory load(String project, String task, String dir) {
		Path folder = Dirs.state().resolve("tests");
		TestHistory h = new TestHistory(folder.resolve(key(dir + "\n" + task) + ".json"), project, task, dir);
		Map<String, Object> data = read(h.file);
		if (data != null) {
			h.updatedAt = Json.str(data, "updatedAt");
			Map<String, Object> saved = Json.obj(data, "tests");
			for (String name : saved.keySet()) h.tests.put(name, new LinkedHashMap<>(Json.obj(saved, name)));
		}
		return h;
	}

	private static Map<String, Object> read(Path file) {
		try {
			return Files.isRegularFile(file) ? Json.parseObject(Files.readString(file, StandardCharsets.UTF_8)) : null;
		} catch (IOException | IllegalArgumentException e) {
			return null; // A history is a nicety: without one, the tests run in their listed order.
		}
	}

	boolean isEmpty() {
		return tests.isEmpty();
	}

	/** When the last run of this task here ended, as written in the file. */
	String lastRun() {
		return updatedAt;
	}

	/** The tests whose last run failed or stopped with an error. */
	Set<String> failed() {
		Set<String> out = new LinkedHashSet<>();
		tests.forEach((name, t) -> {
			String status = Json.str(t, "status");
			if ("failed".equals(status) || "error".equals(status)) out.add(name);
		});
		return out;
	}

	/** How many tests the last runs here ran: more games than that would have nothing to do. */
	int known() {
		return tests.size();
	}

	/** The median of a test's recent durations, or -1 with none to go by. */
	long estimateMs(String test) {
		long here = median(tests.get(test));
		if (here >= 0) return here;
		if (elsewhere == null) elsewhere = fromOtherCheckouts();
		return median(elsewhere.get(test));
	}

	private static long median(Map<String, Object> entry) {
		if (entry == null || !(entry.get("durations") instanceof List<?> list) || list.isEmpty()) return -1;
		List<Long> d = new ArrayList<>();
		for (Object o : list) if (o instanceof Number n) d.add(n.longValue());
		if (d.isEmpty()) return -1;
		Collections.sort(d);
		return d.get(d.size() / 2);
	}

	/** The same project's same task in other folders, most recently run first. */
	private Map<String, Map<String, Object>> fromOtherCheckouts() {
		Map<String, Map<String, Object>> out = new LinkedHashMap<>();
		List<Map<String, Object>> others = new ArrayList<>();
		try (Stream<Path> files = Files.list(file.getParent())) {
			for (Path f : (Iterable<Path>) files::iterator) {
				if (f.equals(file) || !f.getFileName().toString().endsWith(".json")) continue;
				Map<String, Object> data = read(f);
				if (data != null && project.equals(Json.str(data, "project")) && task.equals(Json.str(data, "task"))) others.add(data);
			}
		} catch (IOException e) {
			return out;
		}
		others.sort(Comparator.comparing((Map<String, Object> d) -> String.valueOf(Json.str(d, "updatedAt"))).reversed());
		for (Map<String, Object> data : others) {
			Map<String, Object> t = Json.obj(data, "tests");
			for (String name : t.keySet()) out.putIfAbsent(name, Json.obj(t, name));
		}
		return out;
	}

	/**
	 * The tests in the order to hand them out: the longest first, so the last test to finish isn't a long one started late.
	 * Tests never timed go first of all, in their listed order: they may well be long, and then they're timed.
	 */
	List<String> longestFirst(List<String> names) {
		List<String> unknown = new ArrayList<>();
		List<String> known = new ArrayList<>();
		Map<String, Long> estimates = new LinkedHashMap<>();
		for (String n : names) {
			long e = estimateMs(n);
			estimates.put(n, e);
			(e < 0 ? unknown : known).add(n);
		}
		known.sort(Comparator.comparing((String n) -> estimates.get(n)).reversed());
		List<String> out = new ArrayList<>(unknown);
		out.addAll(known);
		return out;
	}

	/** This run's tests. A test that didn't run keeps what it had: rerunning failures leaves the passing tests' records be. */
	void record(List<Map<String, Object>> results) {
		for (Map<String, Object> r : results) {
			String name = Json.str(r, "name");
			String status = Json.str(r, "status");
			if (name == null || !List.of("passed", "failed", "error").contains(status)) continue;
			Map<String, Object> entry = new LinkedHashMap<>(tests.getOrDefault(name, Map.of()));
			entry.put("status", status);
			entry.put("at", Instant.now().toString());
			// A test that stopped with an error (or took the game down) didn't run its course: its time says little.
			if (!status.equals("error") && r.get("durationMs") instanceof Number ms) {
				List<Object> durations = new ArrayList<>(entry.get("durations") instanceof List<?> l ? l : List.of());
				durations.add(ms.longValue());
				while (durations.size() > KEEP) durations.remove(0);
				entry.put("durations", durations);
			}
			tests.put(name, entry);
		}
		updatedAt = Instant.now().toString();
	}

	void save() {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("project", project);
		data.put("task", task);
		data.put("dir", dir);
		data.put("updatedAt", updatedAt);
		data.put("tests", tests);
		try {
			Files.createDirectories(file.getParent());
			Path temp = Files.createTempFile(file.getParent(), "tests", ".tmp");
			Files.writeString(temp, Json.pretty(data), StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			System.out.println("[spawnproof] couldn't save the test history in " + file + ": " + e.getMessage());
		}
	}

	private static String key(String text) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
