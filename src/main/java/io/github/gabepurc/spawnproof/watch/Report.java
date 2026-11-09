package io.github.gabepurc.spawnproof.watch;

import io.github.gabepurc.spawnproof.Json;
import io.github.gabepurc.spawnproof.client.Text;
import io.github.gabepurc.spawnproof.run.MergedResults;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** How a pull request's test run went, said for GitHub: the commit status's one line, and the comment on the pull request. */
final class Report {
	/** Lines of what failed a comment shows before it points at the full results instead. */
	private static final int MOST_LINES = 60;

	private Report() {}

	/**
	 * What a run came to. state is GitHub's: success, failure (the tests or the build failed) or error (it couldn't be
	 * tested). summary says it in a few words; failures are what failed, a line each; buildLines are what Gradle said went
	 * wrong, when the build failed; results are where the results are kept, and log the build's console.
	 */
	record Outcome(String state, String summary, List<String> failures, List<String> buildLines, List<String> results, String log, long durationMs) {
		boolean passed() {
			return "success".equals(state);
		}
	}

	static Outcome error(String why) {
		return new Outcome("error", why, List.of(), List.of(), List.of(), null, 0);
	}

	/**
	 * The outcome of a build that ran the tests: its exit code, each test run's results.json (a build may run several, one
	 * for each Minecraft version), where those are kept, and the build's console.
	 */
	static Outcome of(int exitCode, List<Map<String, Object>> results, List<String> kept, List<String> console, String log, long durationMs) {
		long tests = 0;
		long passed = 0;
		List<String> failures = new ArrayList<>();
		boolean problems = false;
		for (Map<String, Object> r : results) {
			Map<String, Object> counts = Json.obj(r, "counts");
			tests += Json.num(counts, "tests", 0);
			passed += Json.num(counts, "passed", 0);
			for (String p : Json.strings(r, "problems")) {
				failures.add("PROBLEM: " + p);
				problems = true;
			}
			failures.addAll(MergedResults.failureLines(Json.list(r, "tests")));
		}
		long notPassing = tests - passed;
		String state;
		String summary;
		List<String> buildLines = List.of();
		if (results.isEmpty()) {
			state = "failure";
			summary = exitCode == 0 ? "The build ran no tests" : "The build failed before the tests ran";
			buildLines = whatWentWrong(console);
		} else if (notPassing > 0) {
			state = "failure";
			summary = notPassing + " of " + MergedResults.plural(tests, "test") + " failed";
		} else if (problems) {
			state = "failure";
			summary = "The test run went wrong";
		} else if (exitCode != 0) {
			state = "failure";
			summary = (tests == 0 ? "No tests ran" : tests == 1 ? "The test passed" : "The tests passed") + ", but the build failed";
			buildLines = whatWentWrong(console);
		} else {
			state = "success";
			summary = tests == 0 ? "No tests ran" : tests == 1 ? "1 test passed" : "All " + tests + " tests passed";
		}
		return new Outcome(state, summary, failures, buildLines, kept, log, durationMs);
	}

	/** The commit status's line: "2 of 6 tests failed, on blintzbug-pc in 3m 36s". GitHub keeps 140 characters of it. */
	static String description(Outcome o, String host) {
		return o.summary() + (o.durationMs() > 0 ? ", on " + host + " in " + Text.duration(o.durationMs()) : "");
	}

	/** The comment on the pull request, edited after each run. */
	static String comment(Outcome o, String sha, String host) {
		StringBuilder s = new StringBuilder(GitHub.MARKER).append('\n');
		s.append("**Spawnproof: ").append(o.summary()).append("** at `").append(sha, 0, Math.min(7, sha.length())).append('`');
		if (o.durationMs() > 0) s.append(", tested on ").append(host).append(" in ").append(Text.duration(o.durationMs()));
		s.append(".\n");
		block(s, o.failures());
		if (!o.buildLines().isEmpty()) {
			s.append("\nWhat Gradle said went wrong:\n");
			block(s, o.buildLines());
		}
		if (!o.results().isEmpty()) {
			s.append("\nThe full results are on ").append(host).append(':');
			for (String r : o.results()) s.append(o.results().size() == 1 ? " " : "\n- ").append('`').append(tilde(r)).append('`');
			s.append(o.results().size() == 1 ? ", with " : "\n\nEach with ").append("report.txt, junit.xml and the screenshots beside it.\n");
		}
		if (o.log() != null && (o.results().isEmpty() || !o.passed())) s.append("\nThe build's console: `").append(tilde(o.log())).append("`\n");
		s.append("\n<sub>Spawnproof tests this pull request's new commits on ").append(host).append(" and edits this comment after each run.</sub>\n");
		return s.toString();
	}

	private static void block(StringBuilder s, List<String> lines) {
		if (lines.isEmpty()) return;
		s.append("\n```text\n");
		int n = 0;
		for (String line : lines) {
			if (n++ == MOST_LINES) {
				s.append("... and ").append(lines.size() - MOST_LINES).append(" more lines\n");
				break;
			}
			s.append(tilde(line).replace("```", "'''")).append('\n');
		}
		s.append("```\n");
	}

	/**
	 * What Gradle said went wrong: the console's "What went wrong" section, without the advice after it. Failing that, the
	 * console's last lines.
	 */
	static List<String> whatWentWrong(List<String> console) {
		List<String> out = new ArrayList<>();
		boolean in = false;
		for (String line : console) {
			if (line.startsWith("* What went wrong:")) {
				in = true;
				if (!out.isEmpty()) out.add("");
				continue;
			}
			if (in && (line.startsWith("* Try:") || line.startsWith("* Exception is:") || line.startsWith("BUILD FAILED"))) {
				in = false;
				continue;
			}
			if (in && out.size() < 40) out.add(line);
		}
		while (!out.isEmpty() && out.get(out.size() - 1).isBlank()) out.remove(out.size() - 1);
		if (!out.isEmpty()) return out;
		return new ArrayList<>(console.subList(Math.max(0, console.size() - 20), console.size()));
	}

	/** A path under the home folder written from ~, so a public pull request doesn't show more of the machine than it needs. */
	static String tilde(String text) {
		String home = System.getProperty("user.home");
		return home == null || home.length() < 2 ? text : text.replace(home + "/", "~/");
	}
}
