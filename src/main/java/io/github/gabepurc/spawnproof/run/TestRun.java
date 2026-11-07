package io.github.gabepurc.spawnproof.run;

import io.github.gabepurc.spawnproof.Dirs;
import io.github.gabepurc.spawnproof.Json;
import io.github.gabepurc.spawnproof.Wire;
import io.github.gabepurc.spawnproof.client.Client;

import java.io.IOException;
import java.io.PrintStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A test run: one game task's tests split across several games, run through the machine's queue, with one set of results
 * at the end. The Gradle plugin starts it in place of the game's java ({@code TestRunLauncher}), so it gets the game's
 * whole command line just as the build made it. It starts that command several times over, each game in a folder of its
 * own under the run folder's {@code games/}, a copy of what the build put in the run folder.
 *
 * <p>The games don't split the tests up front. Each asks the run for one test at a time ({@code Dispatch} in Spawnproof for
 * Fabric), and the run hands out the longest first, by past runs. So a game that waits longer for its slot, or stops early,
 * leaves its share to the others. A game that crashes takes only the test it was running with it: the rest go on in the
 * others, or in a new game started in its place.
 *
 * <p>The results are merged into the folder a single game would have used ({@code spawnproof/} in the run folder, or
 * {@code spawnproof.output}), and the run exits 1 if any test didn't pass, failing the Gradle task as a single game would.
 */
public final class TestRun {
	/** The game folders' own things: everything else in the run folder is the build's setup, copied into each. */
	private static final Set<String> NOT_COPIED = Set.of("games", "spawnproof", "logs", "crash-reports", "screenshots");

	private final List<String> command;
	private final Map<String, Object> settings;
	private final Path runDir;
	private final Path gamesDir;
	private final Path out;
	private final boolean onlyFailures;
	private final TestHistory history;
	private final PrintStream console = System.out;
	private final long startedAt = System.currentTimeMillis();
	private Path socket;

	// Guarded by this.
	private final List<Game> games = new ArrayList<>();
	/** How many games to keep going while there are tests left. */
	private int wanted;
	private Plan plan;
	private final Deque<String> work = new ArrayDeque<>();
	private final Map<String, MergedResults.Part> finished = new LinkedHashMap<>();
	private final List<String> notes = new ArrayList<>();
	private final List<String> problems = new ArrayList<>();
	private boolean warnedNoQueue;
	private boolean warnedNoDisplay;

	/** What the first game found: every test, and the ones this run means to run. */
	private record Plan(Map<String, Map<String, Object>> tests, List<String> chosen, String blocked, Map<String, Object> run, int notPicked) {}

	TestRun(List<String> command, Map<String, Object> settings, Path runDir) {
		this.command = command;
		this.settings = settings;
		this.runDir = runDir;
		this.gamesDir = runDir.resolve("games");
		this.out = resultsFolder(command, runDir);
		this.onlyFailures = Json.bool(settings, "onlyFailures");
		this.history = TestHistory.load(String.valueOf(settings.get("project")), String.valueOf(settings.get("task")), String.valueOf(settings.get("dir")));
	}

	/** {@code TestRun <java> <the game's arguments...>}, with the run's settings in SPAWNPROOF_RUN. */
	public static void main(String[] args) {
		int code;
		try {
			Map<String, Object> settings = Json.parseObject(System.getenv("SPAWNPROOF_RUN"));
			code = new TestRun(List.of(args), settings, Path.of("").toAbsolutePath()).run();
		} catch (Throwable e) {
			System.out.println("[spawnproof] the test run failed: " + e);
			e.printStackTrace(System.out);
			code = 1;
		}
		System.out.flush();
		System.exit(code);
	}

	int run() throws IOException, InterruptedException {
		MergedResults.clear(out);
		MergedResults.deleteTree(gamesDir);
		openSocket();
		Runtime.getRuntime().addShutdownHook(new Thread(this::stopGames, "spawnproof-run-stop"));
		try {
			if (onlyFailures && !history.isEmpty() && history.failed().isEmpty()) {
				notes.add("Nothing failed in the last run (" + history.lastRun() + "), so there's nothing to rerun.");
				return finish();
			}
			int n = gamesToStart();
			say("running the tests across " + MergedResults.plural(n, "game") + (history.isEmpty() ? "" : ", the longest first by past runs")
				+ (onlyFailures && !history.isEmpty() ? ": only the ones that failed last time" : ""));
			synchronized (this) {
				wanted = n;
				for (int i = 0; i < n; i++) startGame(null);
				while (live() > 0) wait();
			}
			return finish();
		} finally {
			try {
				Files.deleteIfExists(socket);
			} catch (IOException ignored) {
				// It's in the runtime folder, cleared at logout.
			}
		}
	}

	/** -Pspawnproof.games, or the queue's game slots; never more games than there are tests to run. */
	private int gamesToStart() {
		String asked = String.valueOf(settings.getOrDefault("games", "")).trim();
		int n;
		try {
			n = Integer.parseInt(asked);
		} catch (NumberFormatException e) {
			n = 1;
			if (queued()) {
				try {
					n = (int) Json.num(Json.obj(Client.call(Map.of("type", "status"), true), "config"), "slots", 1);
				} catch (IOException ignored) {
					// One game, then: the queue's unreachable, which the game says when it asks for its slot.
				}
			}
		}
		if (n > 1 && Json.bool(settings, "exclusive")) {
			say("the task runs alone (exclusive), so its tests run in one game");
			n = 1;
		}
		int expected = onlyFailures && !history.isEmpty() ? history.failed().size() : history.known();
		if (expected > 0) n = Math.min(n, expected);
		return Math.max(1, n);
	}

	private void openSocket() throws IOException {
		Files.createDirectories(Dirs.runtime());
		socket = Dirs.runtime().resolve("run-" + ProcessHandle.current().pid() + ".sock");
		Files.deleteIfExists(socket);
		ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
		server.bind(UnixDomainSocketAddress.of(socket));
		Thread accept = new Thread(() -> {
			while (true) {
				SocketChannel channel;
				try {
					channel = server.accept();
				} catch (IOException e) {
					return;
				}
				Thread t = new Thread(() -> serve(channel), "spawnproof-run-connection");
				t.setDaemon(true);
				t.start();
			}
		}, "spawnproof-run-accept");
		accept.setDaemon(true);
		accept.start();
	}

	/** One game's connection: hello, then a next for each test it takes and a done when it's run it. */
	private void serve(SocketChannel channel) {
		Wire wire = new Wire(channel);
		Game game = null;
		try {
			Map<String, Object> m;
			while ((m = wire.read()) != null) {
				switch (String.valueOf(m.get("type"))) {
					case "hello" -> {
						game = gameNumbered((int) Json.num(m, "game", -1));
						if (game != null) {
							game.connected();
							hello(game, m);
						}
					}
					case "next" -> {
						String test = next(game);
						wire.sendLine(test == null ? "stop" : "run " + test);
					}
					case "done" -> done(game, m);
					default -> {
						// Nothing else is said.
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			// The game's gone; ended() hears it from its process.
		} finally {
			wire.close();
			if (game != null) game.disconnected();
		}
	}

	private synchronized Game gameNumbered(int number) {
		for (Game g : games) if (g.number == number) return g;
		return null;
	}

	private synchronized void hello(Game game, Map<String, Object> m) {
		game.hello = true;
		if (plan != null) return;
		Map<String, Map<String, Object>> tests = new LinkedHashMap<>();
		List<String> picked = new ArrayList<>();
		for (Map<String, Object> t : Json.list(m, "tests")) {
			String name = Json.str(t, "name");
			if (name == null) continue;
			tests.put(name, t);
			if (Json.bool(t, "picked")) picked.add(name);
		}
		problems.addAll(Json.strings(m, "problems"));
		String blocked = Json.str(m, "blocked");
		Map<String, Object> run = Json.obj(m, "run");
		boolean named = !Json.strings(Json.obj(run, "selection"), "tests").isEmpty();

		List<String> chosen;
		if (blocked != null) {
			chosen = picked;
		} else if (onlyFailures && !history.isEmpty()) {
			// Only what failed, of the tests picked by name, or of every test (on-request ones too: they failed when asked for).
			Set<String> failed = history.failed();
			chosen = new ArrayList<>();
			for (String name : named ? picked : List.copyOf(tests.keySet())) if (failed.contains(name)) chosen.add(name);
			Set<String> gone = new LinkedHashSet<>(failed);
			gone.removeAll(tests.keySet());
			if (chosen.isEmpty()) notes.add("None of the tests that failed last time (" + String.join(", ", failed) + ") are among this run's.");
			else notes.add("Only the tests that failed last time: " + String.join(", ", chosen));
			if (!gone.isEmpty()) notes.add("Failed last time, but no longer found: " + String.join(", ", gone));
		} else {
			if (onlyFailures) notes.add("No earlier run of this task to go by, so every test runs.");
			chosen = picked;
			if (picked.isEmpty() && problems.isEmpty()) {
				problems.add("no test picked (spawnproof.tests, spawnproof.skip). The tests are: " + names(tests.values()));
			}
		}
		plan = new Plan(tests, chosen, blocked, run, tests.size() - chosen.size());
		if (blocked == null) work.addAll(history.longestFirst(chosen));
		if (!work.isEmpty()) say("to run: " + String.join(", ", work));
		// More games than tests would have nothing to do.
		for (int i = games.size() - 1; i >= 0 && live() > Math.max(work.size(), 1); i--) cancel(games.get(i));
		notifyAll();
	}

	private synchronized String next(Game game) {
		if (game == null || game.leaving != null || plan == null) {
			if (game != null) game.toldToStop = true;
			return null;
		}
		String test = work.poll();
		if (test == null) {
			game.toldToStop = true;
			// Games still waiting for a slot aren't needed now.
			for (Game g : games) cancel(g);
			return null;
		}
		game.current = test;
		game.ran.add(test);
		return test;
	}

	private synchronized void done(Game game, Map<String, Object> m) {
		String test = Json.str(m, "test");
		if (game == null || test == null) return;
		String part = Json.str(m, "part");
		MergedResults.Part p;
		try {
			p = part != null ? MergedResults.Part.read(Path.of(part), game.number, game.output())
				: MergedResults.Part.error(info(test), "game " + game.number + " doesn't have this test", game.number);
		} catch (IOException | RuntimeException e) {
			p = MergedResults.Part.error(info(test), "game " + game.number + "'s results for this test couldn't be read (" + e.getMessage() + ")", game.number);
		}
		finished.put(test, p);
		if (test.equals(game.current)) game.current = null;
		String leaving = Json.str(m, "leaving");
		if (leaving != null) {
			game.leaving = leaving;
			game.toldToStop = true;
			game.stoppedBecause = "took no more tests: " + leaving;
			say("game " + game.number + " takes no more tests: " + leaving);
		}
	}

	private synchronized void startGame(Integer inPlaceOf) {
		Game game = new Game(this, games.size() + 1, gamesDir.resolve(Integer.toString(games.size() + 1)));
		games.add(game);
		if (inPlaceOf != null) say("starting game " + game.number + " in place of game " + inPlaceOf + ", for the tests still to run");
		game.thread.start();
	}

	/** A game's slot came: whether it's still needed. */
	synchronized boolean starting(Game game) {
		if (game.state != Game.State.WAITING) return false;
		if (plan != null && work.isEmpty()) {
			game.state = Game.State.CANCELLED;
			game.stoppedBecause = "not started: the other games had taken every test by the time its slot came";
			return false;
		}
		game.state = Game.State.RUNNING;
		return true;
	}

	/** Called from the game's own thread as it finishes, however it does. */
	synchronized void ended(Game game) {
		if (game.state != Game.State.CANCELLED) game.state = Game.State.ENDED;
		game.endedAt = System.currentTimeMillis();
		if (game.exitCode != null && game.stoppedBecause == null) {
			if (!game.toldToStop) {
				game.stoppedBecause = (game.hello ? "stopped before the run was done" : "stopped without reaching Spawnproof's runner") + " (exit code "
					+ game.exitCode + ")";
				say("game " + game.number + " " + game.stoppedBecause + ". Its last lines:");
				for (String line : game.lastLines()) say("  | " + line);
				say("Its log: " + game.dir.resolve("logs/latest.log"));
			} else if (game.exitCode != 0) {
				game.stoppedBecause = "exited with code " + game.exitCode;
			}
		}
		if (game.current != null) {
			String test = game.current;
			game.current = null;
			finished.put(test, lostTest(game, test));
		}
		// A game that ran a test started fine: one in its place should too. (One that never got that far likely won't.)
		if (plan != null && !work.isEmpty() && !game.ran.isEmpty() && live() < wanted) startGame(game.number);
		notifyAll();
	}

	/** The test a game was running when it went: its results if it saved them on the way out, or a note of what happened. */
	private MergedResults.Part lostTest(Game game, String test) {
		Path parts = game.output().resolve("parts");
		if (Files.isDirectory(parts)) {
			try (var files = Files.list(parts)) {
				for (Path f : (Iterable<Path>) files::iterator) {
					try {
						MergedResults.Part p = MergedResults.Part.read(f, game.number, game.output());
						if (test.equals(p.name())) return p;
					} catch (IOException | RuntimeException ignored) {
						// Cut short as the game went.
					}
				}
			} catch (IOException ignored) {
				// No parts to go by.
			}
		}
		String why = game.stoppedBecause != null ? "game " + game.number + " " + game.stoppedBecause
			: "game " + game.number + " closed while this test ran: it crashed, was stopped, or ran out of time";
		return MergedResults.Part.error(info(test), why + " (see " + relative(game.dir.resolve("logs/latest.log")) + " and crash-reports/)", game.number);
	}

	/** Cancels a game that's still waiting for its slot. */
	private void cancel(Game game) {
		if (game.state != Game.State.WAITING) return;
		game.state = Game.State.CANCELLED;
		game.stoppedBecause = "not started: the other games had taken every test by the time its slot came";
		game.thread.interrupt();
	}

	private int live() {
		int n = 0;
		for (Game g : games) if (g.state == Game.State.WAITING || g.state == Game.State.RUNNING) n++;
		return n;
	}

	/** The run is over: the merged results, the history, and the summary. */
	private int finish() {
		List<MergedResults.Part> parts = new ArrayList<>();
		List<MergedResults.GameSummary> summaries = new ArrayList<>();
		Map<String, Object> run;
		int notPicked;
		synchronized (this) {
			if (plan == null && !games.isEmpty()) {
				problems.add("no game got as far as Spawnproof's runner (did it crash while starting, or is spawnproof-fabric missing from it?): see "
					+ relative(games.get(0).dir.resolve("output.log")));
			}
			if (plan != null) {
				for (String name : plan.chosen()) {
					MergedResults.Part p = finished.get(name);
					if (p == null) {
						p = MergedResults.Part.notRun(info(name), plan.blocked() != null ? plan.blocked()
							: "the games stopped before it ran (see each game's logs/latest.log in " + relative(gamesDir) + ")");
					}
					parts.add(p);
				}
			}
			for (Game g : games) {
				summaries.add(new MergedResults.GameSummary(g.number, g.dir, List.copyOf(g.ran), g.startedAt > 0 ? g.endedAt - g.startedAt : 0,
					g.exitCode, g.stoppedBecause));
			}
			run = plan != null ? plan.run() : Map.of();
			notPicked = plan != null ? plan.notPicked() : 0;
		}
		MergedResults merged = new MergedResults(out, startedAt, System.currentTimeMillis(), run, onlyFailures, notes, problems, notPicked, parts,
			summaries);
		try {
			merged.write();
		} catch (IOException e) {
			say("couldn't write the results in " + out + ": " + e.getMessage());
		}
		if (!parts.isEmpty()) {
			List<Map<String, Object>> results = new ArrayList<>();
			for (MergedResults.Part p : parts) results.add(p.result());
			history.record(results);
			history.save();
		}
		merged.summarize(console);
		return merged.status().equals("passed") ? 0 : 1;
	}

	/** The run's being stopped (Ctrl+C, or Gradle cancelling the task): its games go with it. */
	private void stopGames() {
		List<Game> all;
		synchronized (this) {
			all = new ArrayList<>(games);
		}
		for (Game g : all) if (g.alive()) g.stop();
	}

	private Map<String, Object> info(String test) {
		Map<String, Object> t = plan != null ? plan.tests().get(test) : null;
		if (t != null) return t;
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", test);
		return m;
	}

	private static String names(Iterable<Map<String, Object>> tests) {
		List<String> out = new ArrayList<>();
		for (Map<String, Object> t : tests) out.add(Json.str(t, "name") + (Json.bool(t, "runByDefault") ? "" : " (on request)"));
		return String.join(", ", out);
	}

	// What the games need to know.

	List<String> command() {
		return command;
	}

	Path socket() {
		return socket;
	}

	/** Whether the games wait their turn in the machine's queue: not on CI, nor with the queue turned off. */
	boolean queued() {
		return Json.bool(settings, "queue");
	}

	boolean hidden() {
		return Json.bool(settings, "hidden");
	}

	/** Inside {@code spawnproof run}, which holds one slot and its display for the whole build. */
	String outerDisplay() {
		return Json.str(settings, "display");
	}

	long timeLimitMs() {
		return Json.num(settings, "timeLimitMs", 0);
	}

	Map<String, Object> leaseRequest(Game game) {
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("project", settings.get("project"));
		request.put("task", settings.get("task") + " (game " + game.number + ")");
		request.put("dir", settings.get("dir"));
		request.put("branch", settings.get("branch"));
		request.put("label", settings.get("label"));
		request.put("role", "test");
		request.put("exclusive", Json.bool(settings, "exclusive"));
		request.put("hidden", hidden());
		request.put("pid", ProcessHandle.current().pid());
		return request;
	}

	synchronized void noteNoQueue(String why) {
		if (warnedNoQueue) return;
		warnedNoQueue = true;
		say("couldn't reach the game queue, so this run's games don't wait their turn (" + why + ")");
	}

	synchronized void noteNoDisplay(String note) {
		if (warnedNoDisplay) return;
		warnedNoDisplay = true;
		say(note);
	}

	/** The build's setup in the run folder (resource packs, options, the EULA), for a game's folder of its own. */
	void copyRunFolder(Path target) throws IOException {
		Files.createDirectories(target);
		try (var entries = Files.list(runDir)) {
			for (Path entry : (Iterable<Path>) entries::iterator) {
				String name = entry.getFileName().toString();
				if (NOT_COPIED.contains(name) || out.startsWith(entry) || name.endsWith(".sock")) continue;
				copy(entry, target.resolve(name));
			}
		}
	}

	private static void copy(Path from, Path to) throws IOException {
		if (!Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)) {
			Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
			return;
		}
		Files.walkFileTree(from, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				Files.createDirectories(to.resolve(from.relativize(dir).toString()));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES,
					LinkOption.NOFOLLOW_LINKS);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/** A path in messages: relative to the run folder's parent when inside it, as {@code clientGameTest/games/2} is. */
	String relative(Path path) {
		Path base = runDir.getParent() != null ? runDir.getParent() : runDir;
		Path abs = path.toAbsolutePath().normalize();
		return abs.startsWith(base) ? base.relativize(abs).toString().replace('\\', '/') : abs.toString();
	}

	void say(String line) {
		console.println("[spawnproof] " + line);
	}

	void echo(String line) {
		console.println(line);
	}

	/**
	 * Where the results go: where {@code spawnproof.output} points the game, or {@code spawnproof/} in the run folder. (The
	 * games are given folders of their own; this is where theirs are merged.) The setting may be in an argument file.
	 */
	static Path resultsFolder(List<String> command, Path runDir) {
		Pattern p = Pattern.compile("-Dspawnproof\\.output=(\"[^\"]*\"|\\S+)");
		String found = null;
		for (String arg : command) {
			if (arg.startsWith("-Dspawnproof.output=")) found = arg.substring("-Dspawnproof.output=".length());
			else if (arg.startsWith("@")) {
				try {
					Matcher m = p.matcher(Files.readString(Path.of(arg.substring(1)), StandardCharsets.UTF_8));
					while (m.find()) found = m.group(1).replace("\"", "");
				} catch (IOException | RuntimeException ignored) {
					// Not a file this can read: the game reads it, and its output setting is overridden anyway.
				}
			}
		}
		if (found == null || found.isBlank()) return runDir.resolve("spawnproof");
		return runDir.resolve(found).toAbsolutePath().normalize();
	}
}
