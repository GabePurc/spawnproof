package io.github.gabepurc.spawnproof.watch;

import io.github.gabepurc.spawnproof.Json;
import io.github.gabepurc.spawnproof.cli.TestCommand;
import io.github.gabepurc.spawnproof.run.MergedResults;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * One pull request's test: its commit checked out in the watched repo's own clone, kept in Spawnproof's state folder, and
 * `spawnproof test` run there with the options `spawnproof watch` was given. It's a build like any other, so its games wait
 * their turn in the queue.
 *
 * <p>The clone is kept between tests, build folders and all, so Gradle and Loom don't start from nothing each time; only
 * the files git tracks are put back as the commit has them.
 */
final class PrTest {
	/** A build that runs longer than this is stopped. */
	private static final long TIME_LIMIT_MS = 3 * 60 * 60_000L;
	/** How many builds' consoles are kept. */
	private static final int LOGS_KEPT = 30;

	private final Path repoDir;
	private final Watch watch;
	final long pr;
	final String sha;
	private final String cloneUrl;
	private volatile Process process;
	private volatile String stoppedBecause;

	PrTest(Path repoDir, Watch watch, long pr, String sha, String cloneUrl) {
		this.repoDir = repoDir;
		this.watch = watch;
		this.pr = pr;
		this.sha = sha;
		this.cloneUrl = cloneUrl;
	}

	/** Why it was stopped before it finished, or null. */
	String stoppedBecause() {
		return stoppedBecause;
	}

	/** Stops it: the build's own way, then for good if it hasn't gone in half a minute. */
	void stop(String why) {
		stoppedBecause = why;
		Process p = process;
		if (p == null) return;
		p.descendants().forEach(ProcessHandle::destroy);
		p.destroy();
		Thread t = new Thread(() -> {
			try {
				if (!p.waitFor(30, TimeUnit.SECONDS)) {
					p.descendants().forEach(ProcessHandle::destroyForcibly);
					p.destroyForcibly();
				}
			} catch (InterruptedException ignored) {
				// The daemon is going.
			}
		}, "spawnproof-stop-pr");
		t.setDaemon(true);
		t.start();
	}

	/** Runs it. Null if the commit can't be had any more: the pull request moved on before its turn came. */
	Report.Outcome run() throws IOException, InterruptedException {
		Path checkout = repoDir.resolve("checkout");
		Path runs = repoDir.resolve("runs");
		Files.createDirectories(runs);
		Path log = runs.resolve("pr-" + pr + "-" + sha.substring(0, Math.min(7, sha.length())) + ".log");
		Files.deleteIfExists(log);
		pruneLogs(runs);
		Path resultsLog = runs.resolve("pr-" + pr + ".results");
		Files.deleteIfExists(resultsLog);
		long started = System.currentTimeMillis();
		say(log, "Spawnproof tests " + watch.repo() + " pull request #" + pr + " at " + sha);

		if (!Files.isDirectory(checkout.resolve(".git"))) {
			MergedResults.deleteTree(checkout);
			if (git(log, repoDir, "clone", "--quiet", "--no-checkout", cloneUrl, "checkout") != 0) {
				return stopped() ? null : Report.error("Couldn't clone " + watch.repo() + " (see " + Report.tilde(log.toString()) + ")");
			}
		}
		if (git(log, checkout, "fetch", "--quiet", "--force", "--no-tags", cloneUrl, "+refs/pull/" + pr + "/head:refs/spawnproof/pr/" + pr) != 0) {
			return stopped() ? null : Report.error("Couldn't fetch the pull request's commits (see " + Report.tilde(log.toString()) + ")");
		}
		if (git(log, checkout, "cat-file", "-e", sha + "^{commit}") != 0) return null;
		if (git(log, checkout, "checkout", "--quiet", "--force", "--detach", sha) != 0 || git(log, checkout, "clean", "-ffdq") != 0) {
			return stopped() ? null : Report.error("Couldn't check out the commit (see " + Report.tilde(log.toString()) + ")");
		}

		Path dir = checkout.resolve(watch.path()).normalize();
		if (!dir.startsWith(checkout) || !Files.isDirectory(dir)) return Report.error("The commit has no folder " + watch.path() + " to run the tests from");
		List<String> command;
		try {
			command = TestCommand.build(dir, watch.args());
		} catch (TestCommand.BadOptions e) {
			return Report.error("spawnproof watch's options don't work: " + e.getMessage());
		} catch (IOException e) {
			return Report.error("The commit has no Gradle wrapper (gradlew) to run the tests with");
		}
		TestCommand.applyPlugin(command);
		say(log, "$ " + String.join(" ", command));
		ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
			.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
		Map<String, String> env = pb.environment();
		env.put("SPAWNPROOF_LABEL", "PR #" + pr);
		env.put("SPAWNPROOF_RESULTS_LOG", resultsLog.toString());
		env.put("GIT_TERMINAL_PROMPT", "0");
		// The games are to queue, which a build that thinks it's on CI doesn't do.
		env.remove("CI");
		// Started at login, the daemon may have no JAVA_HOME for the wrapper to find a java with: it gets the daemon's.
		if (env.get("JAVA_HOME") == null) env.put("JAVA_HOME", System.getProperty("java.home"));
		int exit = runToEnd(pb);
		if (stopped()) return Report.error("Stopped: " + stoppedBecause);
		long duration = System.currentTimeMillis() - started;

		List<Map<String, Object>> results = new ArrayList<>();
		List<String> kept = new ArrayList<>();
		Path keep = repoDir.resolve("results").resolve("pr-" + pr);
		MergedResults.deleteTree(keep);
		Set<String> names = new HashSet<>();
		if (Files.isRegularFile(resultsLog)) {
			for (String line : Files.readAllLines(resultsLog, StandardCharsets.UTF_8)) {
				if (line.isBlank()) continue;
				try {
					Map<String, Object> entry = Json.parseObject(line);
					Path file = Path.of(Json.str(entry, "results"));
					results.add(Json.parseObject(Files.readString(file, StandardCharsets.UTF_8)));
					String name = String.valueOf(entry.get("task")).replaceAll("[^\\w.-]+", "-").replaceAll("^-+|-+$", "");
					if (name.isEmpty()) name = "results";
					while (!names.add(name)) name = name + "-again";
					Path copy = keep.resolve(name);
					copyResults(file.getParent(), copy);
					kept.add(copy.resolve("results.json").toString());
				} catch (IOException | RuntimeException e) {
					say(log, "Spawnproof couldn't read a test run's results: " + e);
				}
			}
		}
		return Report.of(exit, results, kept, tail(log, 3000), log.toString(), duration);
	}

	private boolean stopped() {
		return stoppedBecause != null;
	}

	private int git(Path log, Path dir, String... args) throws IOException, InterruptedException {
		if (stopped()) return 1;
		List<String> command = new ArrayList<>();
		command.add("git");
		command.addAll(List.of(args));
		say(log, "$ " + String.join(" ", command));
		ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
			.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
		pb.environment().put("GIT_TERMINAL_PROMPT", "0");
		return runToEnd(pb);
	}

	private int runToEnd(ProcessBuilder pb) throws IOException, InterruptedException {
		pb.redirectInput(ProcessBuilder.Redirect.PIPE);
		Process p = pb.start();
		process = p;
		p.getOutputStream().close();
		try {
			if (stopped()) stop(stoppedBecause);
			if (!p.waitFor(TIME_LIMIT_MS, TimeUnit.MILLISECONDS)) {
				stop("it ran longer than " + TIME_LIMIT_MS / 3_600_000 + " hours");
				p.waitFor();
			}
			return p.exitValue();
		} catch (InterruptedException e) {
			stop("Spawnproof stopped");
			throw e;
		} finally {
			process = null;
		}
	}

	private static void say(Path log, String line) throws IOException {
		Files.writeString(log, "[spawnproof] " + line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	/** The results folder without the games' parts: results.json, report.txt, junit.xml and the screenshots. */
	private static void copyResults(Path from, Path to) throws IOException {
		try (Stream<Path> files = Files.walk(from)) {
			for (Path f : (Iterable<Path>) files::iterator) {
				Path rel = from.relativize(f);
				if (rel.startsWith("parts")) continue;
				Path target = to.resolve(rel.toString());
				if (Files.isDirectory(f)) Files.createDirectories(target);
				else Files.copy(f, target, StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}

	/** The last lines of a file, read from its end so a long console isn't read whole. */
	static List<String> tail(Path file, int lines) throws IOException {
		try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
			long length = f.length();
			long from = Math.max(0, length - 400_000);
			f.seek(from);
			byte[] bytes = new byte[(int) (length - from)];
			f.readFully(bytes);
			List<String> all = new String(bytes, StandardCharsets.UTF_8).lines().toList();
			if (from > 0 && !all.isEmpty()) all = all.subList(1, all.size());
			return all.subList(Math.max(0, all.size() - lines), all.size());
		}
	}

	private static void pruneLogs(Path runs) throws IOException {
		List<Path> logs;
		try (Stream<Path> files = Files.list(runs)) {
			logs = files.filter(f -> f.getFileName().toString().endsWith(".log")).sorted(Comparator.comparingLong(PrTest::modified).reversed()).toList();
		}
		for (int i = LOGS_KEPT - 1; i < logs.size(); i++) Files.deleteIfExists(logs.get(i));
	}

	private static long modified(Path f) {
		try {
			return Files.getLastModifiedTime(f).toMillis();
		} catch (IOException e) {
			return 0;
		}
	}
}
