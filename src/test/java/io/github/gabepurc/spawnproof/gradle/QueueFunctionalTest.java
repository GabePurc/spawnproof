package io.github.gabepurc.spawnproof.gradle;

import io.github.gabepurc.spawnproof.Dirs;
import io.github.gabepurc.spawnproof.cli.Main;
import io.github.gabepurc.spawnproof.client.Client;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real builds against a real daemon, in a home of their own (SPAWNPROOF_HOME) so the machine's queue is left alone. */
class QueueFunctionalTest {
	@TempDir
	Path temp;
	private Path home;

	@BeforeEach
	void setUp() throws IOException {
		home = temp.resolve("home");
		Files.createDirectories(home.resolve("config"));
		Files.writeString(home.resolve("config/config.properties"), "slots=1\ndisplay=none\n");
		System.setProperty("spawnproof.home", home.toString());
	}

	@AfterEach
	void tearDown() {
		Main.run(new String[] {"stop", "--force"}, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));
		System.clearProperty("spawnproof.home");
	}

	/** A mod build whose runClientGameTest "game" notes when it ran, and on what display. */
	private Path project(String name, boolean settingsPlugin) throws IOException {
		return project(name, settingsPlugin, 2000, "");
	}

	private Path project(String name, boolean settingsPlugin, long gameMs, String taskLines) throws IOException {
		Path dir = temp.resolve(name);
		Files.createDirectories(dir.resolve("src/main/java"));
		Files.writeString(dir.resolve("settings.gradle"),
			(settingsPlugin ? "plugins { id 'io.github.gabepurc.spawnproof' }\n" : "") + "rootProject.name = '" + name + "'\n");
		Files.writeString(dir.resolve("build.gradle"), String.join("\n",
			"plugins {",
			"\tid 'java'",
			settingsPlugin ? "" : "\tid 'io.github.gabepurc.spawnproof'",
			"}",
			"tasks.register('runClientGameTest', JavaExec) {",
			"\tclasspath = sourceSets.main.runtimeClasspath",
			"\tmainClass = 'FakeGame'",
			"\targs file('ran.txt').absolutePath",
			taskLines,
			"}",
			""));
		Files.writeString(dir.resolve("src/main/java/FakeGame.java"), String.join("\n",
			"public class FakeGame {",
			"\tpublic static void main(String[] args) throws Exception {",
			"\t\tlong start = System.currentTimeMillis();",
			"\t\tThread.sleep(" + gameMs + ");",
			"\t\tjava.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), start + \" \" + System.currentTimeMillis() + \" \" + System.getenv(\"DISPLAY\"));",
			"\t}",
			"}",
			""));
		return dir;
	}

	private GradleRunner runner(Path dir, Map<String, String> extraEnv, String... args) {
		Map<String, String> env = new HashMap<>(System.getenv());
		env.remove("CI");
		env.put("SPAWNPROOF_HOME", home.toString());
		env.putAll(extraEnv);
		return GradleRunner.create().withProjectDir(dir.toFile()).withPluginClasspath().withEnvironment(env).withArguments(args).forwardOutput();
	}

	/** The history, once it has n games: a game's slot is let go just after its build returns. */
	private List<String> history(int n) throws Exception {
		Path file = home.resolve("state/history.jsonl");
		long deadline = System.currentTimeMillis() + 10_000;
		List<String> lines = List.of();
		while (System.currentTimeMillis() < deadline) {
			lines = Files.exists(file) ? Files.readAllLines(file) : List.of();
			if (lines.size() >= n) return lines;
			Thread.sleep(50);
		}
		return lines;
	}

	private static long[] ran(Path dir) throws IOException {
		String[] parts = Files.readString(dir.resolve("ran.txt")).trim().split(" ");
		return new long[] {Long.parseLong(parts[0]), Long.parseLong(parts[1])};
	}

	@Test
	void gamesFromTwoBuildsNeverOverlap() throws Exception {
		Path a = project("mod-a", true);
		Path b = project("mod-b", false);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Future<BuildResult>> builds = new ArrayList<>();
			for (Path dir : List.of(a, b)) builds.add(pool.submit(() -> runner(dir, Map.of(), "runClientGameTest", "--stacktrace").build()));
			String output = builds.get(0).get().getOutput() + builds.get(1).get().getOutput();
			long[] first = ran(a);
			long[] second = ran(b);
			assertTrue(first[1] <= second[0] || second[1] <= first[0],
				"the games overlapped: " + first[0] + "-" + first[1] + " and " + second[0] + "-" + second[1] + "\n" + output);
		} finally {
			pool.shutdownNow();
		}
		List<String> history = history(2);
		assertEquals(2, history.size(), "each game is in the history: " + history);
		assertTrue(history.get(0).contains("runClientGameTest"));
	}

	@Test
	void statusShowsTheQueue() throws Exception {
		Path a = project("mod-a", true);
		runner(a, Map.of(), "runClientGameTest").build();
		history(1);
		BuildResult status = runner(a, Map.of(), "spawnproofStatus", "-q").build();
		assertTrue(status.getOutput().contains("Spawnproof"), status.getOutput());
		assertTrue(status.getOutput().contains("Recently"), status.getOutput());
		assertTrue(status.getOutput().contains("mod-a :runClientGameTest"), status.getOutput());
	}

	@Test
	void onCiGamesRunDirectly() throws IOException {
		Path a = project("mod-a", true);
		runner(a, Map.of("CI", "true"), "runClientGameTest").build();
		assertTrue(Files.exists(a.resolve("ran.txt")));
		assertFalse(Files.exists(Dirs.socket()), "no daemon was started");
	}

	@Test
	void worksWithTheConfigurationCache() throws Exception {
		Path a = project("mod-a", true);
		runner(a, Map.of(), "runClientGameTest", "--configuration-cache").build();
		Files.delete(a.resolve("ran.txt"));
		BuildResult again = runner(a, Map.of(), "runClientGameTest", "--configuration-cache").build();
		assertTrue(again.getOutput().contains("Reusing configuration cache"), again.getOutput());
		assertTrue(Files.exists(a.resolve("ran.txt")));
		assertEquals(2, history(2).size(), "both runs queued");
	}

	@Test
	void waitingInLineDoesNotUseUpTheTimeout() throws Exception {
		Path a = project("mod-a", true, 1000, "\ttimeout = java.time.Duration.ofSeconds(4)");
		// Hold the only slot for 10 s: the task waits far longer than its whole 4 s timeout.
		Thread holder = new Thread(() -> {
			try (Client.Lease lease = Client.acquire(Map.of("project", "holder", "task", "hold", "hidden", false), m -> {})) {
				Thread.sleep(10_000);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		holder.start();
		Thread.sleep(500);
		BuildResult result = runner(a, Map.of(), "runClientGameTest", "--configuration-cache").build();
		holder.join();
		assertTrue(result.getOutput().contains("next in line"), result.getOutput());
		assertTrue(Files.exists(a.resolve("ran.txt")), "the game ran to the end");
	}

	@Test
	void theTimeoutStillStopsAGameThatRunsTooLong() throws Exception {
		Path a = project("mod-a", true, 60_000, "\ttimeout = java.time.Duration.ofSeconds(3)");
		long start = System.currentTimeMillis();
		BuildResult result = runner(a, Map.of(), "runClientGameTest").buildAndFail();
		assertTrue(System.currentTimeMillis() - start < 40_000, "stopped well before the game's 60 s");
		assertTrue(result.getOutput().contains("ran past its timeout"), result.getOutput());
		assertFalse(Files.exists(a.resolve("ran.txt")), "the game didn't finish");
	}

	@Test
	void runQueuesAnyCommand() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		PrintStream print = new PrintStream(out, true, StandardCharsets.UTF_8);
		int code = Main.run(new String[] {"run", "--label", "by hand", "--", "sh", "-c", "test -n \"$SPAWNPROOF_LEASE\""}, print, print);
		assertEquals(0, code, out.toString(StandardCharsets.UTF_8));
		assertTrue(String.join("\n", history(1)).contains("by hand"));
	}
}
