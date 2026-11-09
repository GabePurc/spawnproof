package io.github.gabepurc.spawnproof.run;

import io.github.gabepurc.spawnproof.Json;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A test run's results, merged from its games' into the folder one game would have written them to, in the same format:
 * results.json, report.txt, junit.xml, and the tests' screenshots and files. Each game writes every test it ran as a part
 * (Spawnproof for Fabric's {@code parts/}): the test as results.json has it, its piece of report.txt and its test suite for
 * junit.xml. This puts them together. Tests that no game finished get a part made up here, saying why.
 */
public final class MergedResults {
	/** results.json's format version, the same as a single game's: a test run only adds fields. */
	static final int FORMAT_VERSION = 1;

	/** One test's results, and the game that ran it (0 for none). */
	record Part(Map<String, Object> result, String report, String junit, int cases, int failures, int errors, int game, Path from) {
		String name() {
			return Json.str(result, "name");
		}

		String status() {
			return Json.str(result, "status");
		}

		/** A part a game wrote; {@code from} is that game's results folder. */
		static Part read(Path file, int game, Path from) throws IOException {
			Map<String, Object> m = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
			Map<String, Object> junit = Json.obj(m, "junit");
			Map<String, Object> result = new LinkedHashMap<>(Json.obj(m, "result"));
			result.put("game", game);
			return new Part(result, String.valueOf(m.getOrDefault("report", "")), String.valueOf(junit.getOrDefault("xml", "")),
				(int) Json.num(junit, "cases", 0), (int) Json.num(junit, "failures", 0), (int) Json.num(junit, "errors", 0), game, from);
		}

		/** A test that stopped with no results of its own: its game crashed or was stopped while it ran. */
		static Part error(Map<String, Object> test, String message, int game) {
			Map<String, Object> result = base(test, "error");
			Map<String, Object> error = new LinkedHashMap<>();
			error.put("type", "spawnproof");
			error.put("message", message);
			result.put("error", error);
			result.put("checks", List.of());
			if (game > 0) result.put("game", game);
			String name = Json.str(result, "name");
			String junit = "  <testsuite name=\"" + attr(name) + "\" tests=\"1\" failures=\"0\" errors=\"1\" skipped=\"0\" time=\"0.000\">\n"
				+ "    <testcase classname=\"" + attr(Json.str(result, "mod") + "." + name) + "\" name=\"(the test stopped with an error)\" time=\"0\">\n"
				+ "      <error type=\"spawnproof\" message=\"" + attr(message) + "\">" + text(message) + "</error>\n    </testcase>\n  </testsuite>\n";
			return new Part(result, "## " + name + ": ERROR\n  ERROR  " + message + "\n", junit, 1, 0, 1, game, null);
		}

		/** A test the run meant to run that no game got to. */
		static Part notRun(Map<String, Object> test, String reason) {
			Map<String, Object> result = base(test, "not-run");
			result.put("stopReason", reason);
			result.put("checks", List.of());
			String name = Json.str(result, "name");
			String junit = "  <testsuite name=\"" + attr(name) + "\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"1\" time=\"0.000\">\n"
				+ "    <testcase classname=\"" + attr(Json.str(result, "mod") + "." + name) + "\" name=\"(not run)\" time=\"0\">\n"
				+ "      <skipped message=\"" + attr(reason) + "\"/>\n    </testcase>\n  </testsuite>\n";
			return new Part(result, "## " + name + ": NOT RUN\n  NOT RUN  " + reason + "\n", junit, 1, 0, 0, 0, null);
		}

		private static Map<String, Object> base(Map<String, Object> test, String status) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", Json.str(test, "name"));
			m.put("status", status);
			m.put("class", Json.str(test, "class"));
			m.put("mod", Json.str(test, "mod"));
			m.put("kind", Json.str(test, "kind"));
			return m;
		}
	}

	/** One game of the run, as the results describe it. */
	record GameSummary(int number, Path dir, List<String> tests, long durationMs, Integer exitCode, String stopped) {}

	private final Path out;
	private final long startedAt;
	private final long endedAt;
	private final Map<String, Object> run;
	private final boolean onlyFailures;
	private final List<String> notes;
	private final List<String> problems;
	private final int notPicked;
	private final List<Part> parts;
	private final List<GameSummary> games;

	MergedResults(Path out, long startedAt, long endedAt, Map<String, Object> run, boolean onlyFailures, List<String> notes, List<String> problems,
		int notPicked, List<Part> parts, List<GameSummary> games) {
		this.out = out;
		this.startedAt = startedAt;
		this.endedAt = endedAt;
		this.run = run;
		this.onlyFailures = onlyFailures;
		this.notes = notes;
		this.problems = problems;
		this.notPicked = notPicked;
		this.parts = parts;
		this.games = games;
	}

	String status() {
		if (!problems.isEmpty()) return "failed";
		for (Part p : parts) if (!"passed".equals(p.status())) return "failed";
		return "passed";
	}

	Map<String, Object> counts() {
		Map<String, Object> c = new LinkedHashMap<>();
		int[] byStatus = new int[4];
		long checks = 0;
		long failedChecks = 0;
		for (Part p : parts) {
			switch (String.valueOf(p.status())) {
				case "passed" -> byStatus[0]++;
				case "failed" -> byStatus[1]++;
				case "error" -> byStatus[2]++;
				default -> byStatus[3]++;
			}
			checks += Json.num(p.result(), "checkCount", Json.list(p.result(), "checks").size());
			failedChecks += Json.num(p.result(), "failedChecks", 0);
		}
		c.put("tests", parts.size());
		c.put("passed", byStatus[0]);
		c.put("failed", byStatus[1]);
		c.put("errors", byStatus[2]);
		c.put("notRun", byStatus[3]);
		c.put("checks", checks);
		c.put("failedChecks", failedChecks);
		c.put("notPicked", notPicked);
		return c;
	}

	/** The games' pace: their ticks over the time their tests took. */
	private long ticks() {
		long ticks = 0;
		for (Part p : parts) ticks += Json.num(p.result(), "ticks", 0);
		return ticks;
	}

	private double ticksPerSecond() {
		long ms = 0;
		for (Part p : parts) ms += Json.num(p.result(), "durationMs", 0);
		return ms > 0 ? ticks() * 1000.0 / ms : 0;
	}

	void write() throws IOException {
		Files.createDirectories(out);
		for (Part p : parts) bringFiles(p);
		write("results.json", Json.pretty(toMap()));
		write("report.txt", report());
		write("junit.xml", junit());
	}

	/** The test's screenshots and files, from its game's folder: their paths in the results are relative to this one. */
	private void bringFiles(Part p) throws IOException {
		if (p.from() == null) return;
		for (String key : List.of("screenshots", "files")) {
			for (Map<String, Object> f : Json.list(p.result(), key)) {
				String path = Json.str(f, "path");
				if (path == null || Path.of(path).isAbsolute()) continue;
				Path source = p.from().resolve(path);
				Path target = out.resolve(path);
				if (!Files.isRegularFile(source) || !target.normalize().startsWith(out)) continue;
				Files.createDirectories(target.getParent());
				Files.deleteIfExists(target);
				try {
					Files.createLink(target, source);
				} catch (IOException | UnsupportedOperationException e) {
					Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	Map<String, Object> toMap() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("format", "spawnproof-results");
		m.put("version", FORMAT_VERSION);
		m.put("status", status());
		m.put("startedAt", Instant.ofEpochMilli(startedAt).toString());
		m.put("endedAt", Instant.ofEpochMilli(endedAt).toString());
		m.put("durationMs", endedAt - startedAt);
		m.put("counts", counts());
		if (!problems.isEmpty()) m.put("problems", problems);
		if (!notes.isEmpty()) m.put("notes", notes);
		Map<String, Object> clock = new LinkedHashMap<>();
		clock.put("mode", run.getOrDefault("clock", null));
		clock.put("ticks", ticks());
		clock.put("ticksPerSecond", Math.round(ticksPerSecond() * 10) / 10.0);
		m.put("clock", clock);
		m.put("graphics", run.getOrDefault("graphics", null));
		Map<String, Object> selection = new LinkedHashMap<>(Json.obj(run, "selection"));
		if (onlyFailures) selection.put("onlyFailures", true);
		m.put("selection", selection);
		m.put("game", Json.obj(run, "game"));
		List<Map<String, Object>> list = new ArrayList<>();
		for (GameSummary g : games) {
			Map<String, Object> gm = new LinkedHashMap<>();
			gm.put("game", g.number());
			gm.put("dir", relative(g.dir()));
			gm.put("tests", g.tests());
			gm.put("durationMs", g.durationMs());
			gm.put("exitCode", g.exitCode());
			if (g.stopped() != null) gm.put("stopped", g.stopped());
			list.add(gm);
		}
		m.put("games", list);
		List<Map<String, Object>> tests = new ArrayList<>();
		for (Part p : parts) tests.add(p.result());
		m.put("tests", tests);
		return m;
	}

	String summaryLine() {
		StringBuilder s = new StringBuilder(countsLine(counts()));
		s.append(". ").append(duration(endedAt - startedAt));
		long played = games.stream().filter(g -> g.exitCode() != null).count();
		s.append(played == 1 ? " in 1 game" : " across " + played + " games");
		String clockName = Json.str(run, "clockName");
		if (clockName != null && ticks() > 0) s.append(String.format(Locale.ROOT, ", %s at %.0f ticks a second", clockName, ticksPerSecond()));
		return s.append('.').toString();
	}

	/** "5 tests: 2 passed, 1 failed, 2 errors. 14 checks, 4 failed", from results.json's counts. */
	public static String countsLine(Map<String, Object> c) {
		StringBuilder s = new StringBuilder(plural(Json.num(c, "tests", 0), "test")).append(": ");
		List<String> bits = new ArrayList<>();
		for (String k : List.of("passed", "failed", "errors", "notRun")) {
			long n = Json.num(c, k, 0);
			if (n > 0) bits.add(n + " " + (k.equals("notRun") ? "not run" : k.equals("errors") ? (n == 1 ? "error" : "errors") : k));
		}
		s.append(bits.isEmpty() ? "none" : String.join(", ", bits));
		long failed = Json.num(c, "failedChecks", 0);
		s.append(". ").append(plural(Json.num(c, "checks", 0), "check"));
		if (failed > 0) s.append(", ").append(failed).append(" failed");
		return s.toString();
	}

	String report() {
		StringBuilder out = new StringBuilder();
		out.append("Spawnproof results: ").append(status()).append('\n');
		out.append(summaryLine()).append('\n');
		Map<String, Object> game = Json.obj(run, "game");
		if (!game.isEmpty()) {
			out.append("Minecraft ").append(game.get("minecraft")).append(", ").append(game.get("loader")).append(", graphics ")
				.append(run.get("graphics")).append('\n');
		}
		Map<String, Object> selection = Json.obj(run, "selection");
		if (!Json.strings(selection, "tests").isEmpty()) out.append("Picked: ").append(String.join(", ", Json.strings(selection, "tests"))).append('\n');
		if (!Json.strings(selection, "skip").isEmpty()) out.append("Skipped: ").append(String.join(", ", Json.strings(selection, "skip"))).append('\n');
		for (String n : notes) out.append(n).append('\n');
		if (notPicked > 0) out.append(plural(notPicked, "other test")).append(" not picked\n");
		for (String p : problems) out.append("PROBLEM: ").append(p).append('\n');
		for (GameSummary g : games) out.append(describe(g)).append('\n');
		for (Part p : parts) out.append('\n').append(p.report());
		return out.toString();
	}

	/** "Game 2: terrain, hat (1m 02s), in ../games/2". */
	String describe(GameSummary g) {
		StringBuilder s = new StringBuilder("Game ").append(g.number()).append(": ");
		if (g.exitCode() == null) return s.append(g.stopped() != null ? g.stopped() : "not started").toString();
		s.append(g.tests().isEmpty() ? "no tests" : String.join(", ", g.tests())).append(" (").append(duration(g.durationMs())).append(')');
		if (g.stopped() != null) s.append("; ").append(g.stopped());
		return s.append(", in ").append(relative(g.dir())).toString();
	}

	String junit() {
		int cases = 0;
		int failures = 0;
		int errors = 0;
		StringBuilder suites = new StringBuilder();
		for (Part p : parts) {
			suites.append(p.junit());
			cases += p.cases();
			failures += p.failures();
			errors += p.errors();
		}
		return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuites name=\"spawnproof\" tests=\"" + cases + "\" failures=\"" + failures + "\" errors=\""
			+ errors + "\" time=\"" + String.format(Locale.ROOT, "%.3f", (endedAt - startedAt) / 1000.0) + "\">\n" + suites + "</testsuites>\n";
	}

	/** The end of the console output: what failed, and where to look. */
	void summarize(PrintStream console) {
		console.println("[spawnproof] ----");
		console.println("[spawnproof] " + summaryLine());
		for (String n : notes) console.println("[spawnproof] " + n);
		for (String p : problems) console.println("[spawnproof] PROBLEM: " + p);
		List<Map<String, Object>> tests = new ArrayList<>();
		for (Part p : parts) tests.add(p.result());
		for (String line : failureLines(tests)) console.println("[spawnproof] " + line);
		for (GameSummary g : games) if (g.stopped() != null && g.exitCode() != null) console.println("[spawnproof] " + describe(g));
		console.println("[spawnproof] Results: " + out.resolve("results.json") + " (report.txt, junit.xml, screenshots/ beside it)");
	}

	/**
	 * What went wrong in these tests (results.json's), a line each: "FAIL ride > Mounting > on the horse: expected true, got
	 * false", "ERROR hat: ...", "NOT RUN ...". At most ten failed checks a test.
	 */
	public static List<String> failureLines(List<Map<String, Object>> tests) {
		List<String> lines = new ArrayList<>();
		for (Map<String, Object> t : tests) {
			String name = Json.str(t, "name");
			String status = Json.str(t, "status");
			if ("passed".equals(status)) continue;
			if ("not-run".equals(status)) {
				lines.add("NOT RUN " + name + ": " + Json.str(t, "stopReason"));
				continue;
			}
			int shown = 0;
			for (Map<String, Object> c : Json.list(t, "checks")) {
				if (Json.bool(c, "ok")) continue;
				if (shown++ == 10) {
					lines.add("  ... " + (Json.num(t, "failedChecks", 0) - 10) + " more failed checks in " + name);
					break;
				}
				String section = Json.str(c, "section");
				lines.add("FAIL " + name + (section != null ? " > " + section : "") + " > " + Json.str(c, "name") + describeCheck(c));
			}
			String stop = Json.str(t, "stopReason");
			if (stop != null && !stop.startsWith("required check failed: ")) lines.add("STOPPED " + name + ": " + stop);
			Map<String, Object> error = Json.obj(t, "error");
			if (!error.isEmpty()) {
				String at = String.valueOf(error.getOrDefault("stack", "")).lines().map(String::strip).filter(l -> l.startsWith("at ")).findFirst().orElse("");
				String type = Json.str(error, "type");
				lines.add("ERROR " + name + ": " + ("spawnproof".equals(type) ? "" : type + ": ") + Json.str(error, "message")
					+ (at.isEmpty() ? "" : " (" + at + ")"));
			}
		}
		return lines;
	}

	private static String describeCheck(Map<String, Object> c) {
		if (c.containsKey("value")) {
			return String.format(Locale.ROOT, " = %.3f (target %.3f..%.3f)", num(c.get("value")), num(c.get("min")), num(c.get("max")));
		}
		if (c.containsKey("expected")) return ": expected " + c.get("expected") + ", got " + c.get("actual");
		if (c.containsKey("detail")) return ": " + String.valueOf(c.get("detail")).lines().findFirst().orElse("");
		return "";
	}

	private static double num(Object o) {
		return o instanceof Number n ? n.doubleValue() : Double.NaN;
	}

	/** A path as the results write it: relative to the results folder when that's short, as {@code ../games/2} is. */
	private String relative(Path path) {
		Path abs = path.toAbsolutePath().normalize();
		Path base = out.toAbsolutePath().normalize();
		if (base.getParent() != null && abs.startsWith(base.getParent())) return base.relativize(abs).toString().replace('\\', '/');
		return abs.toString();
	}

	private void write(String name, String text) throws IOException {
		Path temp = out.resolve(name + ".tmp");
		Files.writeString(temp, text, StandardCharsets.UTF_8);
		Files.move(temp, out.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/** What an earlier run left in the results folder, which would pass for this run's. Only Spawnproof's own files go. */
	static void clear(Path out) throws IOException {
		for (String name : List.of("events.jsonl", "results.json", "report.txt", "junit.xml")) Files.deleteIfExists(out.resolve(name));
		for (String sub : List.of("screenshots", "files", "parts")) deleteTree(out.resolve(sub));
	}

	public static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) return;
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
		}
	}

	public static String plural(long n, String one) {
		return n + " " + (n == 1 ? one : one + "s");
	}

	/** As Spawnproof for Fabric writes durations: tenths of a second under ten seconds. */
	static String duration(long ms) {
		long seconds = Math.max(0, ms / 1000);
		if (seconds < 60) return ms < 10_000 ? String.format(Locale.ROOT, "%.1fs", ms / 1000.0) : seconds + "s";
		long minutes = seconds / 60;
		if (minutes < 60) return minutes + "m " + String.format(Locale.ROOT, "%02ds", seconds % 60);
		return minutes / 60 + "h " + String.format(Locale.ROOT, "%02dm", minutes % 60);
	}

	private static String attr(String s) {
		return text(s).replace("\"", "&quot;").replace("\n", "&#10;");
	}

	private static String text(String s) {
		StringBuilder out = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '&' -> out.append("&amp;");
				case '<' -> out.append("&lt;");
				case '>' -> out.append("&gt;");
				default -> out.append(c < 0x20 && c != '\n' && c != '\t' && c != '\r' ? '?' : c);
			}
		}
		return out.toString();
	}
}
