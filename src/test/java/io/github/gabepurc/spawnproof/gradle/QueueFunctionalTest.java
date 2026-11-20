package io.github.gabepurc.spawnproof.gradle;

import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import io.github.gabepurc.spawnproof.Json;
import io.github.gabepurc.spawnproof.Dirs;
import io.github.gabepurc.spawnproof.cli.Main;
import io.github.gabepurc.spawnproof.client.Client;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

	/**
	 * A mod build with a production client run, as Loom has them: a task that isn't a JavaExec and takes no environment, only
	 * a java launcher. The stand-in (in buildSrc, under Loom's own class name, which is how the plugin knows one) says which
	 * java it starts its "game" with.
	 */
	private Path productionRunProject(String name, String taskLines) throws IOException {
		Path dir = project(name, true, 0, "");
		Path loom = dir.resolve("buildSrc/src/main/java/net/fabricmc/loom/task/prod");
		Files.createDirectories(loom);
		Files.writeString(dir.resolve("buildSrc/build.gradle"), "plugins { id 'java' }\ndependencies { implementation gradleApi() }\n");
		Files.writeString(loom.resolve("ClientProductionRunTask.java"), String.join("\n",
			"package net.fabricmc.loom.task.prod;",
			"",
			"import org.gradle.api.DefaultTask;",
			"import org.gradle.api.file.ConfigurableFileCollection;",
			"import org.gradle.api.plugins.JavaPluginExtension;",
			"import org.gradle.api.provider.ListProperty;",
			"import org.gradle.api.provider.Property;",
			"import org.gradle.api.tasks.*;",
			"import org.gradle.jvm.toolchain.JavaLauncher;",
			"import org.gradle.jvm.toolchain.JavaToolchainService;",
			"import org.gradle.process.ExecOperations;",
			"",
			"import javax.inject.Inject;",
			"",
			"public abstract class ClientProductionRunTask extends DefaultTask {",
			"\t@Nested public abstract Property<JavaLauncher> getJavaLauncher();",
			"\t@Input public abstract ListProperty<String> getJvmArgs();",
			"\t@Classpath public abstract ConfigurableFileCollection getClasspath();",
			"\t@Input public abstract Property<String> getRanFile();",
			"\t@Inject protected abstract ExecOperations getExecOperations();",
			"\t@Inject protected abstract JavaToolchainService getJavaToolchainService();",
			"",
			"\tpublic ClientProductionRunTask() {",
			"\t\tJavaPluginExtension java = getProject().getExtensions().getByType(JavaPluginExtension.class);",
			"\t\tgetJavaLauncher().convention(getJavaToolchainService().launcherFor(java.getToolchain()));",
			"\t}",
			"",
			"\t@TaskAction",
			"\tpublic void run() {",
			"\t\tgetLogger().lifecycle(\"game java: \" + getJavaLauncher().get().getExecutablePath());",
			"\t\tgetExecOperations().exec(spec -> {",
			"\t\t\tspec.commandLine(getJavaLauncher().get().getExecutablePath());",
			"\t\t\tspec.args(getJvmArgs().get());",
			"\t\t\tspec.args(\"-cp\", getClasspath().getAsPath(), \"FakeGame\", getRanFile().get());",
			"\t\t});",
			"\t}",
			"}",
			""));
		Files.writeString(dir.resolve("build.gradle"), String.join("\n",
			"",
			"tasks.register('auditClientMixins', net.fabricmc.loom.task.prod.ClientProductionRunTask) {",
			"\tclasspath.from(sourceSets.main.runtimeClasspath)",
			"\tranFile = file('ran.txt').absolutePath",
			taskLines,
			"}",
			""), StandardOpenOption.APPEND);
		return dir;
	}

	/** The java the stand-in production run started its game with. */
	private static String gameJava(BuildResult result) {
		return result.getOutput().lines().filter(l -> l.startsWith("game java: ")).map(l -> l.substring("game java: ".length())).findFirst()
			.orElseThrow(() -> new AssertionError("no game ran:\n" + result.getOutput()));
	}

	/** A build that picks the java its production game runs on, as a mod does to run each Minecraft version on its own Java. */
	private static final String OWN_LAUNCHER =
		"\tjavaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(" + Runtime.version().feature() + ") }";

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
	void aNewDaemonWaitsForTheOldOneToLetGo() throws Exception {
		// As a replaced daemon does on its way out: it no longer answers, but it holds the lock until it's gone.
		Files.createDirectories(home.resolve("state"));
		try (FileChannel file = FileChannel.open(home.resolve("state/daemon.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
			FileLock old = file.lock();
			Thread letGo = new Thread(() -> {
				try {
					Thread.sleep(3_000);
					old.release();
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			});
			letGo.start();
			try (Client.Connection c = Client.connect(true)) {
				assertEquals(io.github.gabepurc.spawnproof.Version.VERSION, c.version, "the new daemon took over");
			}
			letGo.join();
		}
		Map<String, Object> status = Client.call(Map.of("type", "status"), false);
		assertTrue(status.containsKey("running"), status.toString());
	}

	@Test
	void runQueuesAnyCommand() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		PrintStream print = new PrintStream(out, true, StandardCharsets.UTF_8);
		int code = Main.run(new String[] {"run", "--label", "by hand", "--", "sh", "-c", "test -n \"$SPAWNPROOF_LEASE\""}, print, print);
		assertEquals(0, code, out.toString(StandardCharsets.UTF_8));
		assertTrue(String.join("\n", history(1)).contains("by hand"));
	}

	@Test
	@EnabledOnOs(OS.LINUX)
	void aProductionRunStartsThroughTheHidingScript() throws Exception {
		Path a = productionRunProject("mod-a", "");
		BuildResult result = runner(a, Map.of(), "auditClientMixins").build();
		assertTrue(gameJava(result).startsWith(home.resolve("state/launchers").toString()), result.getOutput());
		assertTrue(Files.exists(a.resolve("ran.txt")), "the script ran the real java");
		assertTrue(String.join("\n", history(1)).contains("\"hidden\":true"), history(1).toString());
	}

	@Test
	@EnabledOnOs(OS.LINUX)
	void aProductionRunWithItsOwnLauncherIsHiddenToo() throws Exception {
		Path a = productionRunProject("mod-a", OWN_LAUNCHER);
		BuildResult result = runner(a, Map.of(), "auditClientMixins", "--configuration-cache").build();
		assertTrue(gameJava(result).startsWith(home.resolve("state/launchers").toString()), result.getOutput());
		assertTrue(Files.exists(a.resolve("ran.txt")), "the script ran the real java");
		Files.delete(a.resolve("ran.txt"));
		BuildResult again = runner(a, Map.of(), "auditClientMixins", "--configuration-cache").build();
		assertTrue(again.getOutput().contains("Reusing configuration cache"), again.getOutput());
		assertTrue(gameJava(again).startsWith(home.resolve("state/launchers").toString()), again.getOutput());
		assertTrue(Files.exists(a.resolve("ran.txt")), "the script ran the real java");
	}

	@Test
	@EnabledOnOs(OS.LINUX)
	void aVisibleProductionRunKeepsItsOwnJava() throws Exception {
		Path a = productionRunProject("mod-a", OWN_LAUNCHER);
		BuildResult result = runner(a, Map.of(), "auditClientMixins", "-Pspawnproof.visible").build();
		assertFalse(gameJava(result).startsWith(home.toString()), result.getOutput());
		assertTrue(Files.exists(a.resolve("ran.txt")));
	}

	@Test
	@EnabledOnOs(OS.LINUX)
	void aProductionRunThatCantBeHiddenSaysSo() throws Exception {
		Path a = productionRunProject("mod-a", OWN_LAUNCHER + "\n\tjavaLauncher.disallowChanges()");
		BuildResult result = runner(a, Map.of(), "auditClientMixins").build();
		assertTrue(result.getOutput().contains("couldn't put :auditClientMixins on the hidden display"), result.getOutput());
		assertFalse(gameJava(result).startsWith(home.toString()), result.getOutput());
		assertTrue(Files.exists(a.resolve("ran.txt")), "the game still ran");
		assertTrue(String.join("\n", history(1)).contains("\"hidden\":false"), history(1).toString());
	}

	@Test
	void theQueueIsPausedAndSetOverTheSocket() throws Exception {
		Client.call(Map.of("type", "pause"), true);
		assertEquals(Boolean.TRUE, Client.call(Map.of("type", "status"), false).get("paused"));
		Client.call(Map.of("type", "resume"), false);
		assertEquals(Boolean.FALSE, Client.call(Map.of("type", "status"), false).get("paused"));

		Client.call(Map.of("type", "set", "key", "slots", "value", "3"), false);
		assertEquals(3L, Json.num(Client.call(Map.of("type", "status"), false), "slots", -1));
		assertTrue(Files.readString(home.resolve("config/config.properties")).contains("slots=3"), "and it's kept for next time");
		assertThrows(IOException.class, () -> Client.call(Map.of("type", "set", "key", "slots", "value", "none"), false));
	}

	@Test
	void aRunningGameIsToldToStop() throws Exception {
		CompletableFuture<String> told = new CompletableFuture<>();
		try (Client.Lease lease = Client.acquire(Map.of("project", "mod", "task", "game", "hidden", false), m -> {}, told::complete)) {
			Map<String, Object> status = Client.call(Map.of("type", "status"), false);
			assertEquals(Boolean.TRUE, Json.list(status, "running").get(0).get("stoppable"), status.toString());
			Map<String, Object> reply = Client.call(Map.of("type", "stop-game", "id", lease.id, "by", "the dashboard"), false);
			assertTrue(Json.str(reply, "message").contains("Stopping game #" + lease.id), reply.toString());
			assertEquals("stopped from the dashboard", told.get(10, TimeUnit.SECONDS));
		}
		// A build with a Spawnproof from before this: its game holds a slot, but doesn't listen.
		try (Client.Lease old = Client.acquire(Map.of("project", "mod", "task", "game", "hidden", false), m -> {})) {
			IOException e = assertThrows(IOException.class, () -> Client.call(Map.of("type", "stop-game", "id", old.id), false));
			assertTrue(e.getMessage().contains("older Spawnproof"), e.getMessage());
		}
		IOException gone = assertThrows(IOException.class, () -> Client.call(Map.of("type", "stop-game", "id", 999), false));
		assertTrue(gone.getMessage().contains("isn't running"), gone.getMessage());
	}

	@Test
	void stoppingAGameFromTheQueueStopsItsBuild() throws Exception {
		Path a = project("mod-a", true, 60_000, "");
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<BuildResult> build = pool.submit(() -> runner(a, Map.of(), "runClientGameTest").buildAndFail());
			long deadline = System.currentTimeMillis() + 60_000;
			List<Map<String, Object>> running = List.of();
			while (running.isEmpty() && System.currentTimeMillis() < deadline) {
				Thread.sleep(100);
				try {
					running = Json.list(Client.call(Map.of("type", "status"), false), "running");
				} catch (IOException notUpYet) {
					// The build starts the daemon.
				}
			}
			assertEquals(1, running.size(), "the game is running");
			// (A moment for the game itself to start: the slot comes just before it.)
			Thread.sleep(1500);
			long asked = System.currentTimeMillis();
			Client.call(Map.of("type", "stop-game", "id", Json.num(running.get(0), "id", -1), "by", "the dashboard"), false);
			BuildResult result = build.get(40, TimeUnit.SECONDS);
			assertTrue(System.currentTimeMillis() - asked < 40_000, "stopped well before the game's 60 s");
			assertTrue(result.getOutput().contains(":runClientGameTest was stopped from the dashboard"), result.getOutput());
			assertFalse(Files.exists(a.resolve("ran.txt")), "the game didn't finish");
		} finally {
			pool.shutdownNow();
		}
		assertEquals(1, history(1).size(), "its slot came free");
	}
}
