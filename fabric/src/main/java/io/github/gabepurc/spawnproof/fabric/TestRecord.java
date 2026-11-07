package io.github.gabepurc.spawnproof.fabric;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One test's results, as it runs: what it checked, saved and logged, and how it ended. */
final class TestRecord {
	static final String RUNNING = "running";
	static final String PASSED = "passed";
	static final String FAILED = "failed";
	static final String ERROR = "error";
	static final String NOT_RUN = "not-run";
	/** Lines of the game log kept for each test: past these, they're only counted. */
	private static final int LOG_LINES = 200;

	final Candidate test;
	private final Results results;
	long startedAt;
	private long startTicks;
	long endedAt;
	long ticks;
	String status = RUNNING;
	private String section;
	final List<Map<String, Object>> checks = new ArrayList<>();
	final List<Map<String, Object>> screenshots = new ArrayList<>();
	final List<Map<String, Object>> files = new ArrayList<>();
	final List<Map<String, Object>> notes = new ArrayList<>();
	final List<String> log = new ArrayList<>();
	int logDropped;
	int failedChecks;
	/** Why it stopped early: a failed requirement, or {@code fail}. */
	String stopReason;
	Map<String, Object> error;
	/** report.txt's lines for this test, in order. */
	final List<String> report = new ArrayList<>();

	TestRecord(Candidate test, Results results) {
		this.test = test;
		this.results = results;
	}

	synchronized void start() {
		startedAt = System.currentTimeMillis();
		startTicks = Clock.ticks;
	}

	synchronized void section(String name) {
		section = name;
		report.add("== " + name);
		results.event(event("section").put("name", name).map);
	}

	/** Records a check. Everything but name and ok is optional (null). */
	synchronized boolean check(String name, boolean ok, String detail, Double value, Double min, Double max, Object expected, Object actual,
		boolean required) {
		Map<String, Object> c = new LinkedHashMap<>();
		c.put("name", name);
		if (section != null) c.put("section", section);
		c.put("ok", ok);
		if (value != null) {
			c.put("value", value);
			c.put("min", min);
			c.put("max", max);
		}
		if (!ok && (expected != null || actual != null)) {
			c.put("expected", String.valueOf(expected));
			c.put("actual", String.valueOf(actual));
		}
		if (!ok && detail != null && !detail.isBlank()) c.put("detail", detail);
		if (required) c.put("required", true);
		checks.add(c);
		if (!ok) failedChecks++;

		StringBuilder line = new StringBuilder(ok ? "  PASS  " : "  FAIL  ").append(name);
		if (value != null) line.append(String.format(Locale.ROOT, " = %.3f  (target %.3f..%.3f)", value, min, max));
		if (!ok && (expected != null || actual != null)) line.append(": expected ").append(expected).append(", got ").append(actual);
		if (!ok && required) line.append("  (required: the test stopped here)");
		report.add(line.toString());
		if (!ok && detail != null && !detail.isBlank()) {
			for (String d : detail.strip().split("\n")) report.add("        " + d);
		}
		Events e = event("check");
		e.map.putAll(c);
		results.event(e.map);
		Runner.say(test.name() + (ok ? "  PASS  " : "  FAIL  ") + line.substring(8));
		return ok;
	}

	synchronized void note(String text) {
		Map<String, Object> n = new LinkedHashMap<>();
		if (section != null) n.put("section", section);
		n.put("text", text);
		notes.add(n);
		for (String line : text.split("\n")) report.add("  " + line);
		results.event(event("note").put("text", text).map);
	}

	synchronized void screenshot(String name, String path) {
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("name", name);
		if (section != null) s.put("section", section);
		s.put("path", path);
		screenshots.add(s);
		report.add("  screenshot " + name + " -> " + path);
		results.event(event("screenshot").put("name", name).put("path", path).map);
	}

	synchronized void file(String name, String path) {
		Map<String, Object> f = new LinkedHashMap<>();
		f.put("name", name);
		f.put("path", path);
		files.add(f);
		report.add("  file " + name + " -> " + path);
		results.event(event("file").put("name", name).put("path", path).map);
	}

	synchronized void logLine(String line) {
		if (log.size() < LOG_LINES) log.add(line);
		else logDropped++;
	}

	/** Stopped by require or fail. */
	synchronized void stopped(String reason) {
		stopReason = reason;
		if (!reason.startsWith("required check failed: ")) report.add("  STOPPED  " + reason);
	}

	/** An exception the test didn't expect. */
	synchronized void error(Throwable e) {
		error = describe(e);
		report.add("  ERROR  " + error.get("type") + ": " + error.get("message"));
		for (String line : String.valueOf(error.get("stack")).split("\n")) report.add("        " + line);
	}

	/** A problem found around the test, not by it (it left a world open, the game stopped): an error without an exception. */
	synchronized void problem(String message) {
		error = new LinkedHashMap<>();
		error.put("type", "spawnproof");
		error.put("message", message);
		report.add("  ERROR  " + message);
	}

	synchronized void end() {
		endedAt = System.currentTimeMillis();
		ticks = Clock.ticks - startTicks;
		if (status.equals(RUNNING)) status = error != null ? ERROR : failedChecks > 0 || stopReason != null ? FAILED : PASSED;
	}

	synchronized void notRun(String reason) {
		status = NOT_RUN;
		stopReason = reason;
		report.add("  NOT RUN  " + reason);
	}

	long durationMs() {
		return (endedAt > 0 ? endedAt : System.currentTimeMillis()) - startedAt;
	}

	synchronized Map<String, Object> toMap() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", test.name());
		m.put("status", status);
		m.put("class", test.className());
		m.put("mod", test.mod());
		m.put("kind", test.kind().label());
		if (!test.tags().isEmpty()) m.put("tags", List.copyOf(test.tags()));
		if (!status.equals(NOT_RUN)) {
			m.put("durationMs", durationMs());
			m.put("ticks", endedAt > 0 ? ticks : Clock.ticks - startTicks);
			m.put("checkCount", checks.size());
			m.put("failedChecks", failedChecks);
		}
		if (stopReason != null) m.put("stopReason", stopReason);
		if (error != null) m.put("error", error);
		m.put("checks", checks);
		if (!screenshots.isEmpty()) m.put("screenshots", screenshots);
		if (!files.isEmpty()) m.put("files", files);
		if (!notes.isEmpty()) m.put("notes", notes);
		if (!log.isEmpty()) m.put("log", log);
		if (logDropped > 0) m.put("logDropped", logDropped);
		return m;
	}

	private Events event(String type) {
		return new Events(type, test.name());
	}

	/** An exception for the results: its type, message and the stack trace, cut short below the test's own frames. */
	static Map<String, Object> describe(Throwable e) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", e.getClass().getName());
		m.put("message", String.valueOf(e.getMessage()));
		StringWriter text = new StringWriter();
		e.printStackTrace(new PrintWriter(text));
		List<String> lines = new ArrayList<>();
		for (String line : text.toString().split("\n")) {
			// The runner's own frames and below say nothing about the test.
			if (line.contains("io.github.gabepurc.spawnproof.fabric.Candidate.run") || line.contains("io.github.gabepurc.spawnproof.fabric.Runner.")
				|| lines.size() >= 60) break;
			lines.add(line.stripTrailing());
		}
		m.put("stack", String.join("\n", lines));
		return m;
	}

	/** An event line under construction. */
	static final class Events {
		final Map<String, Object> map = new LinkedHashMap<>();

		Events(String type, String test) {
			map.put("event", type);
			if (test != null) map.put("test", test);
		}

		Events put(String key, Object value) {
			map.put(key, value);
			return this;
		}
	}
}
