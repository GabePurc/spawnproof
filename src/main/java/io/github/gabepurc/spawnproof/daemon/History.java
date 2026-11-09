package io.github.gabepurc.spawnproof.daemon;

import io.github.gabepurc.spawnproof.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Every game that ran, one JSON line each: for "about 3 minutes left" and for looking back. Null file keeps it in memory. */
public final class History {
	static final int KEEP = 500;
	private final Path file;
	private final ArrayDeque<Map<String, Object>> recent = new ArrayDeque<>();

	public History(Path file) {
		this.file = file;
		if (file == null || !Files.isRegularFile(file)) return;
		try {
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			int from = Math.max(0, lines.size() - KEEP);
			for (String line : lines.subList(from, lines.size())) {
				try {
					recent.addLast(Json.parseObject(line));
				} catch (IllegalArgumentException ignored) {
					// A line cut short by a crash.
				}
			}
			if (lines.size() > 4 * KEEP) Files.write(file, lines.subList(from, lines.size()), StandardCharsets.UTF_8);
		} catch (IOException ignored) {
			// History is a nicety; start without it.
		}
	}

	synchronized void record(Lease lease, long endedAt) {
		Map<String, Object> entry = lease.toMap();
		entry.put("endedAt", endedAt);
		entry.put("durationMs", endedAt - lease.startedAt);
		entry.put("waitedMs", lease.startedAt - lease.queuedAt);
		recent.addLast(entry);
		while (recent.size() > KEEP) recent.removeFirst();
		if (file == null) return;
		try {
			Files.writeString(file, Json.write(entry) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException ignored) {
			// Kept in memory anyway.
		}
	}

	/** The median of the last few runs of this task in this project, or -1 with none to go by. */
	public synchronized long estimateMs(String project, String task) {
		List<Long> durations = new ArrayList<>();
		for (Iterator<Map<String, Object>> it = recent.descendingIterator(); it.hasNext() && durations.size() < 7; ) {
			Map<String, Object> e = it.next();
			if ("play".equals(e.get("role"))) continue;
			if (project.equals(e.get("project")) && task.equals(e.get("task"))) durations.add(Json.num(e, "durationMs", 0));
		}
		if (durations.isEmpty()) return -1;
		Collections.sort(durations);
		return durations.get(durations.size() / 2);
	}

	public synchronized List<Map<String, Object>> last(int n) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Iterator<Map<String, Object>> it = recent.descendingIterator(); it.hasNext() && out.size() < n; ) out.add(it.next());
		return out;
	}
}
