package io.github.gabepurc.spawnproof.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * A run's results, in its folder ({@code spawnproof/} in the game's folder unless {@code spawnproof.output} says otherwise):
 *
 * <ul>
 * <li>{@code events.jsonl}: one line per thing that happens, written as it happens, so a run that dies midway still says
 * how far it got (and a program can follow a run live).
 * <li>{@code results.json}: the whole run, rewritten after every test.
 * <li>{@code report.txt}: the same for people: each test's sections, checks, notes and screenshots.
 * <li>{@code junit.xml}: for CI, one test case per check.
 * <li>{@code screenshots/<test>/}, {@code files/<test>/}: what the tests saved.
 * </ul>
 */
final class Results {
	static final int FORMAT_VERSION = 1;

	private final Path dir;
	private final BufferedWriter events;
	private final Object eventLock = new Object();
	private boolean eventsClosed;
	private final long startedAt = System.currentTimeMillis();
	private final long startTicks = Clock.ticks;
	private long endTicks;
	private final Selection selection;
	private final List<TestRecord> tests = new ArrayList<>();
	private final List<String> problems = new ArrayList<>();
	private int notPicked;
	private volatile TestRecord current;
	private boolean finished;
	private long endedAt;

	private Results(Path dir, Selection selection) throws IOException {
		this.dir = dir;
		this.selection = selection;
		Files.createDirectories(dir);
		// An earlier run's results would pass for this one's.
		for (String name : List.of("events.jsonl", "results.json", "report.txt", "junit.xml")) Files.deleteIfExists(dir.resolve(name));
		for (String sub : List.of("screenshots", "files")) deleteTree(dir.resolve(sub));
		events = Files.newBufferedWriter(dir.resolve("events.jsonl"), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
			StandardOpenOption.TRUNCATE_EXISTING);
	}

	static Results open(Path dir, Selection selection) {
		try {
			return new Results(dir, selection);
		} catch (IOException e) {
			throw new UncheckedIOException("Spawnproof couldn't make its results folder " + dir, e);
		}
	}

	Path dir() {
		return dir;
	}

	/** A path in the results as it's written down: relative to the results folder when inside it. */
	String relative(Path path) {
		Path abs = path.toAbsolutePath().normalize();
		return abs.startsWith(dir) ? dir.relativize(abs).toString().replace('\\', '/') : abs.toString();
	}

	TestRecord current() {
		return current;
	}

	synchronized void plan(List<Candidate> picked, int notPicked) {
		this.notPicked = notPicked;
		for (Candidate c : picked) tests.add(new TestRecord(c, this));
		Map<String, Object> e = new LinkedHashMap<>();
		e.put("event", "run");
		e.put("startedAt", Instant.ofEpochMilli(startedAt).toString());
		e.put("tests", picked.stream().map(Candidate::name).toList());
		e.put("game", game());
		e.put("clock", Clock.FAST ? "frames" : "real");
		e.put("graphics", Settings.LOW_GRAPHICS ? "low" : "normal");
		event(e);
	}

	List<TestRecord> tests() {
		return tests;
	}

	/** A problem with the run itself: no test picked, a name that picks none. */
	synchronized void problem(String message) {
		problems.add(message);
		event(new TestRecord.Events("problem", null).put("message", message).map);
	}

	void begin(TestRecord record) {
		record.start();
		current = record;
		event(new TestRecord.Events("test", record.test.name()).put("class", record.test.className()).put("kind", record.test.kind().label()).map);
		Runner.say(record.test.name() + ": started");
	}

	void end(TestRecord record) {
		record.end();
		current = null;
		Map<String, Object> e = new TestRecord.Events("done", record.test.name()).map;
		e.put("status", record.status);
		e.put("durationMs", record.durationMs());
		e.put("ticks", record.ticks);
		e.put("failedChecks", record.failedChecks);
		if (record.error != null) e.put("error", record.error.get("type") + ": " + record.error.get("message"));
		if (record.stopReason != null) e.put("stopReason", record.stopReason);
		event(e);
		writeFiles();
	}

	/** The run is over: normally, or because the game is going away (it crashed, stalled or was stopped). */
	synchronized void finish(String cutShort) {
		if (finished) return;
		if (cutShort != null) {
			TestRecord running = current;
			if (running != null) {
				running.problem(cutShort);
				running.end();
			}
			for (TestRecord t : tests) {
				if (t.status.equals(TestRecord.RUNNING) && t != running) t.notRun("the game stopped before it ran");
			}
			problems.add(cutShort);
		}
		finished = true;
		endedAt = System.currentTimeMillis();
		endTicks = Clock.ticks;
		Map<String, Object> e = new TestRecord.Events("end", null).map;
		e.put("status", status());
		e.put("durationMs", endedAt - startedAt);
		e.put("counts", counts());
		event(e);
		writeFiles();
		synchronized (eventLock) {
			eventsClosed = true;
			try {
				events.close();
			} catch (IOException ignored) {
				// Written already.
			}
		}
	}

	synchronized boolean finished() {
		return finished;
	}

	synchronized long durationMs() {
		return (finished ? endedAt : System.currentTimeMillis()) - startedAt;
	}

	/** Client ticks while the tests ran. */
	synchronized long ticks() {
		return (finished ? endTicks : Clock.ticks) - startTicks;
	}

	/** The run's pace: on the frame clock, how much faster than a normal game's 20. */
	synchronized double ticksPerSecond() {
		long ms = durationMs();
		return ms > 0 ? ticks() * 1000.0 / ms : 0;
	}

	synchronized String status() {
		if (!problems.isEmpty()) return "failed";
		for (TestRecord t : tests) if (!t.status.equals(TestRecord.PASSED)) return "failed";
		return "passed";
	}

	/**
	 * Appends to events.jsonl. On a lock of its own, taken last: a test's record calls this holding its own lock, and the
	 * results call the records holding theirs.
	 */
	void event(Map<String, Object> e) {
		synchronized (eventLock) {
			if (eventsClosed) return;
			try {
				Map<String, Object> line = new LinkedHashMap<>();
				line.put("event", e.get("event"));
				line.put("ms", System.currentTimeMillis() - startedAt);
				line.putAll(e);
				events.write(Json.write(line));
				events.write('\n');
				events.flush();
			} catch (IOException ignored) {
				// results.json still has it.
			}
		}
	}

	synchronized Map<String, Object> counts() {
		Map<String, Object> c = new LinkedHashMap<>();
		int checks = 0;
		int failedChecks = 0;
		Map<String, Integer> byStatus = new TreeMap<>();
		for (TestRecord t : tests) {
			byStatus.merge(t.status, 1, Integer::sum);
			checks += t.checks.size();
			failedChecks += t.failedChecks;
		}
		c.put("tests", tests.size());
		for (String s : List.of(TestRecord.PASSED, TestRecord.FAILED, TestRecord.ERROR, TestRecord.NOT_RUN)) c.put(key(s), byStatus.getOrDefault(s, 0));
		c.put("checks", checks);
		c.put("failedChecks", failedChecks);
		c.put("notPicked", notPicked);
		return c;
	}

	private static String key(String status) {
		return switch (status) {
			case TestRecord.ERROR -> "errors";
			case TestRecord.NOT_RUN -> "notRun";
			default -> status;
		};
	}

	synchronized Map<String, Object> toMap() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("format", "spawnproof-results");
		m.put("version", FORMAT_VERSION);
		m.put("status", finished ? status() : "running");
		m.put("startedAt", Instant.ofEpochMilli(startedAt).toString());
		if (finished) m.put("endedAt", Instant.ofEpochMilli(endedAt).toString());
		m.put("durationMs", (finished ? endedAt : System.currentTimeMillis()) - startedAt);
		m.put("counts", counts());
		if (!problems.isEmpty()) m.put("problems", problems);
		Map<String, Object> clock = new LinkedHashMap<>();
		clock.put("mode", Clock.FAST ? "frames" : "real");
		clock.put("ticks", ticks());
		clock.put("ticksPerSecond", Math.round(ticksPerSecond() * 10) / 10.0);
		m.put("clock", clock);
		m.put("graphics", Settings.LOW_GRAPHICS ? "low" : "normal");
		Map<String, Object> picked = new LinkedHashMap<>();
		picked.put("tests", selection.include());
		picked.put("skip", selection.exclude());
		m.put("selection", picked);
		m.put("game", game());
		List<Map<String, Object>> list = new ArrayList<>();
		for (TestRecord t : tests) list.add(t.toMap());
		m.put("tests", list);
		return m;
	}

	/** The game and the mods in it: Fabric API's own modules and mods nested in others left out. */
	private static Map<String, Object> game() {
		FabricLoader loader = FabricLoader.getInstance();
		Map<String, Object> g = new LinkedHashMap<>();
		g.put("minecraft", version(loader, "minecraft"));
		g.put("loader", "fabric " + version(loader, "fabricloader"));
		g.put("fabricApi", version(loader, "fabric-api"));
		g.put("java", System.getProperty("java.version"));
		g.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
		Map<String, Object> mods = new TreeMap<>();
		for (ModContainer mod : loader.getAllMods()) {
			String id = mod.getMetadata().getId();
			if (mod.getContainingMod().isPresent() || id.startsWith("fabric-") || List.of("java", "minecraft", "fabricloader", "mixinextras").contains(id)) continue;
			mods.put(id, mod.getMetadata().getVersion().getFriendlyString());
		}
		g.put("mods", mods);
		return g;
	}

	private static String version(FabricLoader loader, String id) {
		return loader.getModContainer(id).map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse(null);
	}

	/** results.json, report.txt and junit.xml, each replaced whole so a reader never sees half of one. */
	synchronized void writeFiles() {
		write("results.json", Json.pretty(toMap()));
		write("report.txt", report());
		write("junit.xml", JUnitXml.of(this));
	}

	private void write(String name, String text) {
		try {
			Path temp = dir.resolve(name + ".tmp");
			Files.writeString(temp, text, StandardCharsets.UTF_8);
			Files.move(temp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Runner.say("couldn't write " + dir.resolve(name) + ": " + e.getMessage());
		}
	}

	synchronized String summaryLine() {
		Map<String, Object> c = counts();
		StringBuilder s = new StringBuilder();
		s.append(plural((int) c.get("tests"), "test")).append(": ");
		List<String> parts = new ArrayList<>();
		for (String k : List.of("passed", "failed", "errors", "notRun")) {
			int n = (int) c.get(k);
			if (n > 0) parts.add(n + " " + (k.equals("notRun") ? "not run" : k.equals("errors") ? (n == 1 ? "error" : "errors") : k));
		}
		s.append(parts.isEmpty() ? "none" : String.join(", ", parts));
		s.append(". ").append(plural((int) c.get("checks"), "check"));
		if ((int) c.get("failedChecks") > 0) s.append(", ").append(c.get("failedChecks")).append(" failed");
		s.append(". ").append(duration(durationMs())).append(String.format(Locale.ROOT, ", %s at %.0f ticks a second.", Clock.name(), ticksPerSecond()));
		return s.toString();
	}

	private synchronized String report() {
		StringBuilder out = new StringBuilder();
		out.append("Spawnproof results: ").append(finished ? status() : "running").append('\n');
		out.append(summaryLine()).append('\n');
		Map<String, Object> g = game();
		out.append("Minecraft ").append(g.get("minecraft")).append(", ").append(g.get("loader")).append(", graphics ")
			.append(Settings.LOW_GRAPHICS ? "low" : "normal").append('\n');
		if (!selection.include().isEmpty()) out.append("Picked: ").append(String.join(", ", selection.include())).append('\n');
		if (!selection.exclude().isEmpty()) out.append("Skipped: ").append(String.join(", ", selection.exclude())).append('\n');
		if (notPicked > 0) out.append(plural(notPicked, "other test")).append(" not picked\n");
		for (String p : problems) out.append("PROBLEM: ").append(p).append('\n');
		for (TestRecord t : tests) {
			out.append('\n').append("## ").append(t.test.name()).append(": ").append(t.status.toUpperCase(Locale.ROOT).replace('-', ' '));
			if (!t.status.equals(TestRecord.NOT_RUN)) {
				out.append(" (").append(duration(t.durationMs())).append(", ").append(t.ticks).append(" ticks");
				if (t.failedChecks > 0) out.append(", ").append(t.failedChecks).append(" of ").append(t.checks.size()).append(" checks failed");
				else out.append(", ").append(plural(t.checks.size(), "check"));
				out.append(")");
			}
			out.append('\n');
			for (String line : t.report) out.append(line).append('\n');
			if (!t.log.isEmpty()) {
				out.append("  log: ").append(plural(t.log.size() + t.logDropped, "warning or error", "warnings and errors")).append('\n');
				for (String line : t.log.subList(0, Math.min(10, t.log.size()))) out.append("    ").append(firstLine(line)).append('\n');
				if (t.log.size() > 10) out.append("    ... (all in results.json)\n");
			}
		}
		return out.toString();
	}

	private static String firstLine(String s) {
		int nl = s.indexOf('\n');
		String line = nl < 0 ? s : s.substring(0, nl);
		return line.length() > 300 ? line.substring(0, 297) + "..." : line;
	}

	static String plural(int n, String one) {
		return plural(n, one, one + "s");
	}

	static String plural(int n, String one, String many) {
		return n + " " + (n == 1 ? one : many);
	}

	static String duration(long ms) {
		long seconds = Math.max(0, ms / 1000);
		if (seconds < 60) return ms < 10_000 ? String.format(Locale.ROOT, "%.1fs", ms / 1000.0) : seconds + "s";
		long minutes = seconds / 60;
		if (minutes < 60) return minutes + "m " + String.format(Locale.ROOT, "%02ds", seconds % 60);
		return minutes / 60 + "h " + String.format(Locale.ROOT, "%02dm", minutes % 60);
	}

	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) return;
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
		}
	}
}
