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

/**
 * Every game that ran, one JSON line each: for "about 3 minutes left" and for looking back. Null file keeps it in memory.
 *
 * <p>It also hands out the games' ids, which go on from one daemon to the next: an id names one game in {@code status}, the
 * daemon's log and the history, so it mustn't come round again after a restart. The last id handed out is kept beside the
 * history, since a game that leaves the line without running never reaches the history itself.
 */
public final class History {
	static final int KEEP = 500;
	private final Path file;
	private final Path lastIdFile;
	private final ArrayDeque<Map<String, Object>> recent = new ArrayDeque<>();
	private long lastId;

	public History(Path file) {
		this.file = file;
		this.lastIdFile = file == null ? null : file.resolveSibling("last-game-id");
		if (file == null) return;
		lastId = readLastId();
		if (!Files.isRegularFile(file)) return;
		try {
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			int from = Math.max(0, lines.size() - KEEP);
			for (int i = 0; i < lines.size(); i++) {
				Map<String, Object> entry;
				try {
					entry = Json.parseObject(lines.get(i));
				} catch (IllegalArgumentException ignored) {
					continue; // A line cut short by a crash.
				}
				// Every line counts for the ids, kept or not: a history from before ids carried over numbers its games from 1
				// again after each restart, so the highest may be anywhere in it.
				lastId = Math.max(lastId, Json.num(entry, "id", 0));
				if (i >= from) recent.addLast(entry);
			}
			if (lines.size() > 4 * KEEP) Files.write(file, lines.subList(from, lines.size()), StandardCharsets.UTF_8);
		} catch (IOException ignored) {
			// History is a nicety; start without it.
		}
	}

	private long readLastId() {
		try {
			return Files.isRegularFile(lastIdFile) ? Long.parseLong(Files.readString(lastIdFile, StandardCharsets.UTF_8).trim()) : 0;
		} catch (IOException | NumberFormatException e) {
			return 0; // The history's own ids are still gone by.
		}
	}

	/** The id for the next game: one more than any this daemon or an earlier one has handed out. */
	synchronized long nextId() {
		lastId++;
		if (lastIdFile != null) {
			try {
				Files.writeString(lastIdFile, lastId + "\n", StandardCharsets.UTF_8);
			} catch (IOException ignored) {
				// The history's ids still carry most of it over.
			}
		}
		return lastId;
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
