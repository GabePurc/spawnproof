package io.github.gabepurc.spawnproof.daemon;

import io.github.gabepurc.spawnproof.Dirs;
import io.github.gabepurc.spawnproof.Json;
import io.github.gabepurc.spawnproof.Processes;
import io.github.gabepurc.spawnproof.cli.TestCommand;
import io.github.gabepurc.spawnproof.run.KeptRuns;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Tests run again from the dashboard. A rerun starts from a kept run: every test of it, the ones that didn't pass, or the
 * ones named, with `spawnproof test` in the folder that run was made in. It's a build like any other, so its games wait
 * their turn in the queue, and its results are kept as a run of their own when it's done. Its console goes to a file
 * the dashboard reads the end of.
 */
final class Reruns {
	/** A build that runs longer than this is stopped. */
	private static final long TIME_LIMIT_MS = 3 * 60 * 60_000L;
	/** How many finished reruns the status still lists, and how many consoles are kept. */
	private static final int FINISHED_LISTED = 10;
	private static final int LOGS_KEPT = 30;

	private final Consumer<String> log;
	/** Where watched repos are cloned: a run made there was a pull request's, which is tested again another way. */
	private final Path watchRoot;
	private final Path logs = Dirs.state().resolve("reruns");
	/** The oldest first. Guarded by this, as is each rerun's state. */
	private final List<Rerun> all = new ArrayList<>();
	private long nextId = 1;

	Reruns(Consumer<String> log, Path watchRoot) {
		this.log = log;
		this.watchRoot = watchRoot;
	}

	private static final class Rerun {
		long id;
		/** The kept run it started from. */
		String of;
		String project;
		String task;
		String dir;
		String branch;
		/** "every test", "the 2 tests that didn't pass", or the tests' names. */
		String what;
		/** running, passed, failed (the build's own word: its exit code), or stopped. */
		String state = "running";
		long startedAt;
		long endedAt;
		Path console;
		Process process;
		String stoppedBecause;
		/** The run kept from it, once it's done and left results. */
		String result;

		String describe() {
			return project + " " + task + " (" + what + ")";
		}

		Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", id);
			m.put("of", of);
			m.put("project", project);
			m.put("task", task);
			m.put("dir", dir);
			m.put("branch", branch);
			m.put("what", what);
			m.put("state", state);
			m.put("startedAt", startedAt);
			if (endedAt > 0) m.put("endedAt", endedAt);
			if (stoppedBecause != null) m.put("stoppedBecause", stoppedBecause);
			if (result != null) m.put("result", result);
			m.put("console", console.toString());
			return m;
		}
	}

	/**
	 * Starts one. {@code tests} names the tests to run; none means every test, or with {@code notPassed} the ones that
	 * didn't pass in the kept run.
	 */
	Map<String, Object> start(String runId, List<String> tests, boolean notPassed) throws IOException {
		Map<String, Object> kept = KeptRuns.read(runId);
		if (kept == null) throw new IOException("that run isn't kept any more (older runs are let go to make room), so it can't be run again from here");
		Map<String, Object> run = Json.obj(kept, "run");
		String dir = Json.str(run, "dir");
		String task = Json.str(run, "task");
		if (dir == null || task == null) throw new IOException("that run doesn't say where it was made, so it can't be run again from here");
		String repo = Projects.watchedRepo(dir, watchRoot);
		if (repo != null) {
			throw new IOException("that run tested a pull request of " + repo + ", in Spawnproof's own clone of it: test the pull request again instead");
		}
		if (!Files.isDirectory(Path.of(dir))) throw new IOException(dir + " isn't there any more, so its tests can't be run again");

		List<String> names = new ArrayList<>(tests);
		String what;
		if (notPassed) {
			names = Json.strings(run, "notPassed");
			if (names.isEmpty()) throw new IOException("every test passed in that run, so there's none to run again");
			what = names.size() == 1 ? names.get(0) + ", which didn't pass" : "the " + names.size() + " tests that didn't pass";
		} else {
			what = names.isEmpty() ? "every test" : String.join(", ", names);
		}
		for (String name : names) {
			// As `spawnproof test <tests>` takes them: a comma would split one name into two.
			if (name == null || name.isBlank() || name.contains(",") || name.startsWith("-")) {
				throw new IOException("the test '" + name + "' can't be picked by its name, so run it from a terminal: spawnproof test");
			}
		}
		List<String> args = new ArrayList<>(names);
		args.addAll(List.of("--task", task));
		List<String> command;
		try {
			command = new ArrayList<>(TestCommand.build(Path.of(dir), args));
		} catch (TestCommand.BadOptions e) {
			throw new IOException("that run's task (" + task + ") can't be run again from here");
		}
		TestCommand.applyPlugin(command);

		Rerun r = new Rerun();
		synchronized (this) {
			for (Rerun other : all) {
				if (other.state.equals("running") && other.dir.equals(dir) && other.task.equals(task)) {
					throw new IOException(other.project + " " + task + " is being run again already (" + other.what + "): wait for it, or stop it first");
				}
			}
			r.id = nextId++;
			r.of = runId;
			r.project = Json.str(run, "project");
			r.task = task;
			r.dir = dir;
			r.branch = Json.str(run, "branch");
			r.what = what;
			r.startedAt = System.currentTimeMillis();
			Files.createDirectories(logs);
			pruneLogs();
			r.console = logs.resolve("rerun-" + r.startedAt + "-" + r.id + ".log");
			Files.writeString(r.console, "[spawnproof] Running " + what + " again, in " + dir + "\n[spawnproof] $ " + String.join(" ", command) + "\n",
				StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			ProcessBuilder pb = new ProcessBuilder(command).directory(Path.of(dir).toFile()).redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.appendTo(r.console.toFile())).redirectInput(ProcessBuilder.Redirect.PIPE);
			Map<String, String> env = pb.environment();
			// The games are to queue, which a build that thinks it's on CI, or inside a game's slot already, doesn't do.
			env.remove("CI");
			env.remove("SPAWNPROOF_LEASE");
			env.remove("SPAWNPROOF_DISPLAY");
			// Started at login, the daemon may have no JAVA_HOME for the wrapper to find a java with: it gets the daemon's.
			if (env.get("JAVA_HOME") == null) env.put("JAVA_HOME", System.getProperty("java.home"));
			r.process = pb.start();
			r.process.getOutputStream().close();
			all.add(r);
		}
		log.accept("rerun #" + r.id + " " + r.describe() + " in " + dir);
		Thread t = new Thread(() -> await(r), "spawnproof-rerun-" + r.id);
		t.setDaemon(true);
		t.start();
		Map<String, Object> reply = new LinkedHashMap<>();
		reply.put("type", "ok");
		reply.put("rerun", r.toMap());
		reply.put("message", "Running " + what + " of " + r.project + " " + task + " again. Its games wait their turn like any other build's.");
		return reply;
	}

	private void await(Rerun r) {
		Process p = r.process;
		int exit;
		try {
			if (!p.waitFor(TIME_LIMIT_MS, TimeUnit.MILLISECONDS)) {
				synchronized (this) {
					if (r.stoppedBecause == null) r.stoppedBecause = "it ran longer than " + TIME_LIMIT_MS / 3_600_000 + " hours";
				}
				Processes.stop(p, 30);
			}
			exit = p.waitFor();
		} catch (InterruptedException e) {
			Processes.stop(p, 5);
			return;
		}
		String result = keptFrom(r);
		synchronized (this) {
			r.endedAt = System.currentTimeMillis();
			r.state = r.stoppedBecause != null ? "stopped" : exit == 0 ? "passed" : "failed";
			r.result = result;
			r.process = null;
			// Only the last few finished ones stay listed.
			int finished = 0;
			for (int i = all.size() - 1; i >= 0; i--) {
				if (!all.get(i).state.equals("running") && ++finished > FINISHED_LISTED) all.remove(i);
			}
		}
		log.accept("rerun #" + r.id + " " + r.describe() + ": " + r.state + (r.stoppedBecause != null ? " (" + r.stoppedBecause + ")" : ""));
	}

	/** The run kept from this rerun's results: the newest of its task in its folder that ended since it started. */
	private static String keptFrom(Rerun r) {
		for (Map<String, Object> run : KeptRuns.list(r.project, r.dir, 20)) {
			if (!r.task.equals(Json.str(run, "task"))) continue;
			try {
				String ended = Json.str(run, "endedAt");
				if (ended != null && Instant.parse(ended).toEpochMilli() >= r.startedAt) return Json.str(run, "id");
			} catch (DateTimeParseException e) {
				// Not this one.
			}
			return null;
		}
		return null;
	}

	/** Stops one that's running: the build's own way, then for good if it hasn't gone in half a minute. */
	Map<String, Object> stop(long id, String why) throws IOException {
		Rerun r;
		Process p;
		synchronized (this) {
			r = find(id);
			if (r == null || r.process == null) throw new IOException("that rerun isn't running any more");
			if (r.stoppedBecause == null) r.stoppedBecause = why;
			p = r.process;
		}
		log.accept("stopping rerun #" + r.id + " " + r.describe() + ": " + why);
		Thread t = new Thread(() -> Processes.stop(p, 30), "spawnproof-stop-rerun-" + r.id);
		t.setDaemon(true);
		t.start();
		return Map.of("type", "ok", "message", "Stopping the rerun of " + r.project + " " + r.task + ".");
	}

	/** The end of a rerun's console. */
	Map<String, Object> console(long id, int lines) throws IOException {
		Rerun r;
		synchronized (this) {
			r = find(id);
		}
		if (r == null) throw new IOException("that rerun isn't listed any more");
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", "rerun-log");
		m.put("id", id);
		m.put("file", r.console.toString());
		m.put("lines", Files.isRegularFile(r.console) ? Processes.tail(r.console, Math.max(1, Math.min(lines, 2000))) : List.of());
		return m;
	}

	private Rerun find(long id) {
		for (Rerun r : all) if (r.id == id) return r;
		return null;
	}

	/** The reruns going now and the last few that finished, the newest first. */
	synchronized List<Map<String, Object>> describe() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (int i = all.size() - 1; i >= 0; i--) out.add(all.get(i).toMap());
		return out;
	}

	/** The ones going now, by name: the daemon stays up for them, and a newer Spawnproof waits before it replaces this one. */
	synchronized List<String> running() {
		List<String> out = new ArrayList<>();
		for (Rerun r : all) if (r.state.equals("running")) out.add("a rerun of " + r.project + " " + r.task);
		return out;
	}

	/** The daemon is stopping: its builds go with it. */
	void stopAll() {
		List<Process> going = new ArrayList<>();
		synchronized (this) {
			for (Rerun r : all) {
				if (r.process == null) continue;
				if (r.stoppedBecause == null) r.stoppedBecause = "Spawnproof stopped";
				going.add(r.process);
			}
		}
		for (Process p : going) Processes.stop(p, 5);
	}

	private void pruneLogs() throws IOException {
		List<Path> files;
		try (Stream<Path> list = Files.list(logs)) {
			files = list.filter(f -> f.getFileName().toString().endsWith(".log")).sorted(Comparator.comparing((Path f) -> f.getFileName().toString()).reversed())
				.toList();
		}
		for (int i = LOGS_KEPT - 1; i < files.size(); i++) Files.deleteIfExists(files.get(i));
	}
}
