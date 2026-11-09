package io.github.gabepurc.spawnproof.run;

import io.github.gabepurc.spawnproof.Dirs;
import io.github.gabepurc.spawnproof.Json;
import io.github.gabepurc.spawnproof.Wire;
import io.github.gabepurc.spawnproof.client.Client;
import io.github.gabepurc.spawnproof.cli.Main;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test runs in real builds, against a real daemon in a home of its own. The "game" is a stand-in that talks to the run as
 * Spawnproof for Fabric does: it lists its tests, takes them one at a time, and writes each one's results as a part.
 */
class TestRunFunctionalTest {
	@TempDir
	Path temp;
	private Path home;
	private Path project;

	/** The stand-in game. Its tests are a, b, c and d, or the ones in spawnproof.tests; fake.failing fails some, crash exits. */
	private static final String FAKE_GAME = """
		import java.io.*;
		import java.net.*;
		import java.nio.channels.*;
		import java.nio.charset.StandardCharsets;
		import java.nio.file.*;
		import java.util.*;

		public class FakeGame {
			public static void main(String[] args) throws Exception {
				if (!Files.isRegularFile(Path.of("setup.txt"))) throw new IllegalStateException("the build's setup wasn't copied in");
				String socket = System.getenv("SPAWNPROOF_DISPATCH");
				int game = Integer.parseInt(System.getenv("SPAWNPROOF_GAME"));
				Path out = Path.of(System.getenv("SPAWNPROOF_OUTPUT"));
				List<String> all = List.of("a", "crash", "b", "c", "d");
				String asked = System.getProperty("spawnproof.tests", "");
				List<String> picked = asked.isEmpty() ? List.of("a", "b", "c", "d") : List.of(asked.split(","));
				Set<String> failing = new HashSet<>(List.of(System.getProperty("fake.failing", "").split(",")));
				SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
				ch.connect(UnixDomainSocketAddress.of(socket));
				Writer w = new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8);
				BufferedReader r = new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
				StringBuilder tests = new StringBuilder();
				for (String t : all) {
					if (tests.length() > 0) tests.append(',');
					tests.append("{\\"name\\":\\"" + t + "\\",\\"class\\":\\"Fake\\",\\"mod\\":\\"fake\\",\\"kind\\":\\"spawnproof\\",\\"runByDefault\\":"
						+ !t.equals("crash") + ",\\"picked\\":" + picked.contains(t) + "}");
				}
				w.write("{\\"type\\":\\"hello\\",\\"game\\":" + game + ",\\"tests\\":[" + tests + "],\\"problems\\":[],\\"blocked\\":null,"
					+ "\\"run\\":{\\"clock\\":\\"frames\\",\\"clockName\\":\\"frame clock\\",\\"graphics\\":\\"low\\",\\"selection\\":{\\"tests\\":[],\\"skip\\":[]},"
					+ "\\"game\\":{\\"minecraft\\":\\"26.3\\",\\"loader\\":\\"fake\\"}}}\\n");
				w.flush();
				int n = 0;
				while (true) {
					w.write("{\\"type\\":\\"next\\"}\\n");
					w.flush();
					String line = r.readLine();
					if (line == null || !line.startsWith("run ")) break;
					String name = line.substring(4);
					System.out.println("[spawnproof] " + name + ": started");
					if (name.equals("crash")) System.exit(3);
					Thread.sleep(700);
					boolean ok = !failing.contains(name);
					Path shot = out.resolve("screenshots/" + name + "/shot.png");
					Files.createDirectories(shot.getParent());
					Files.writeString(shot, "a picture of " + name);
					String result = "{\\"name\\":\\"" + name + "\\",\\"status\\":\\"" + (ok ? "passed" : "failed") + "\\",\\"class\\":\\"Fake\\",\\"mod\\":\\"fake\\","
						+ "\\"durationMs\\":700,\\"ticks\\":40,\\"checkCount\\":1,\\"failedChecks\\":" + (ok ? 0 : 1) + ","
						+ "\\"checks\\":[{\\"name\\":\\"works\\",\\"ok\\":" + ok + "}],"
						+ "\\"screenshots\\":[{\\"name\\":\\"shot\\",\\"path\\":\\"screenshots/" + name + "/shot.png\\"}]}";
					String part = "{\\"result\\":" + result + ",\\"report\\":\\"## " + name + ": " + (ok ? "PASSED" : "FAILED") + "\\\\n  "
						+ (ok ? "PASS" : "FAIL") + "  works\\\\n\\",\\"junit\\":{\\"xml\\":\\"  <testsuite name=\\\\\\"" + name + "\\\\\\"/>\\\\n\\",\\"cases\\":1,"
						+ "\\"failures\\":" + (ok ? 0 : 1) + ",\\"errors\\":0}}";
					Path file = out.resolve("parts/" + (++n) + ".json");
					Files.createDirectories(file.getParent());
					Files.writeString(file, part);
					w.write("{\\"type\\":\\"done\\",\\"test\\":\\"" + name + "\\",\\"part\\":" + "\\"" + file.toString().replace("\\\\", "\\\\\\\\") + "\\",\\"leaving\\":null}\\n");
					w.flush();
				}
				ch.close();
			}
		}
		""";

	/** A game run on its own, without a test run: it writes its results as Spawnproof for Fabric would, and a test fails. */
	private static final String ONE_GAME = """
		import java.nio.file.*;

		public class OneGame {
			public static void main(String[] args) throws Exception {
				Path out = Path.of("spawnproof");
				Files.createDirectories(out.resolve("screenshots/hat"));
				Files.writeString(out.resolve("screenshots/hat/front.png"), "a picture of a hat");
				Files.writeString(out.resolve("results.json"), "{\\"format\\":\\"spawnproof-results\\",\\"version\\":1,\\"status\\":\\"failed\\","
					+ "\\"counts\\":{\\"tests\\":1,\\"failed\\":1},\\"tests\\":[{\\"name\\":\\"hat\\",\\"status\\":\\"failed\\",\\"checks\\":[],"
					+ "\\"screenshots\\":[{\\"name\\":\\"front\\",\\"path\\":\\"screenshots/hat/front.png\\"}]}]}");
				System.exit(1);
			}
		}
		""";

	@BeforeEach
	void setUp() throws IOException {
		home = temp.resolve("home");
		Files.createDirectories(home.resolve("config"));
		Files.writeString(home.resolve("config/config.properties"), "slots=2\ndisplay=none\n");
		System.setProperty("spawnproof.home", home.toString());
		project = temp.resolve("mod");
		Files.createDirectories(project.resolve("src/main/java"));
		Files.writeString(project.resolve("settings.gradle"), "plugins { id 'io.github.gabepurc.spawnproof' }\nrootProject.name = 'mod'\n");
		Files.writeString(project.resolve("build.gradle"), String.join("\n",
			"plugins { id 'java' }",
			"def runFolder = file('run')",
			"tasks.register('runClientGameTest', JavaExec) {",
			"\tclasspath = sourceSets.main.runtimeClasspath",
			"\tmainClass = 'FakeGame'",
			"\tworkingDir = runFolder",
			"\tsystemProperty 'fake.failing', findProperty('fakeFailing') ?: ''",
			"\tdoFirst {",
			"\t\trunFolder.mkdirs()",
			"\t\tnew File(runFolder, 'setup.txt').text = 'what the build puts in the run folder'",
			"\t}",
			"}",
			"tasks.register('runClientGameTestAlone', JavaExec) {",
			"\tclasspath = sourceSets.main.runtimeClasspath",
			"\tmainClass = 'OneGame'",
			"\tworkingDir = runFolder",
			"\tdoFirst { runFolder.mkdirs() }",
			"}",
			""));
		Files.writeString(project.resolve("src/main/java/FakeGame.java"), FAKE_GAME);
		Files.writeString(project.resolve("src/main/java/OneGame.java"), ONE_GAME);
	}

	@AfterEach
	void tearDown() {
		Main.run(new String[] {"stop", "--force"}, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));
		System.clearProperty("spawnproof.home");
	}

	private GradleRunner runner(Map<String, String> extraEnv, String... args) {
		Map<String, String> env = new HashMap<>(System.getenv());
		env.remove("CI");
		env.put("SPAWNPROOF_HOME", home.toString());
		env.putAll(extraEnv);
		List<String> all = new ArrayList<>(List.of(args));
		all.add("--stacktrace");
		return GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath().withEnvironment(env).withArguments(all).forwardOutput();
	}

	private Map<String, Object> results() throws IOException {
		return Json.parseObject(Files.readString(project.resolve("run/spawnproof/results.json")));
	}

	private static List<String> names(Map<String, Object> results) {
		List<String> out = new ArrayList<>();
		for (Map<String, Object> t : Json.list(results, "tests")) out.add(Json.str(t, "name"));
		return out;
	}

	@Test
	void splitsTheTestsAcrossGamesAndMergesTheirResults() throws IOException {
		BuildResult result = runner(Map.of(), "runClientGameTest", "-Pspawnproof.games=2", "-PfakeFailing=c").buildAndFail();
		Map<String, Object> r = results();
		assertEquals("failed", r.get("status"), result.getOutput());
		assertEquals(List.of("a", "b", "c", "d"), names(r));
		List<Map<String, Object>> games = Json.list(r, "games");
		assertEquals(2, games.size(), "two games ran: " + games);
		for (Map<String, Object> g : games) assertFalse(Json.strings(g, "tests").isEmpty(), "each game took tests: " + games);
		assertEquals(3, Json.num(Json.obj(r, "counts"), "passed", -1));
		assertEquals(1, Json.num(Json.obj(r, "counts"), "failed", -1));

		String report = Files.readString(project.resolve("run/spawnproof/report.txt"));
		assertTrue(report.contains("## c: FAILED"), report);
		assertTrue(report.contains("across 2 games"), report);
		String junit = Files.readString(project.resolve("run/spawnproof/junit.xml"));
		assertTrue(junit.contains("<testsuites name=\"spawnproof\" tests=\"4\" failures=\"1\""), junit);
		assertTrue(Files.isRegularFile(project.resolve("run/spawnproof/screenshots/a/shot.png")), "the screenshots are brought together");
		List<Map<String, Object>> kept = KeptRuns.all();
		assertEquals(1, kept.size(), "the run is kept for the dashboard: " + kept);
		assertEquals("test run", kept.get(0).get("how"));
		assertEquals("mod", kept.get(0).get("project"));
		assertEquals(List.of("c"), Json.strings(kept.get(0), "notPassed"));
		assertTrue(Files.isRegularFile(KeptRuns.folder().resolve(Json.str(kept.get(0), "id")).resolve("screenshots/a/shot.png")));
		assertTrue(Files.isRegularFile(project.resolve("run/games/1/output.log")));

		String output = result.getOutput();
		assertTrue(output.contains("[spawnproof game 1]"), output);
		assertTrue(output.contains("[spawnproof] FAIL c > works"), output);
	}

	@Test
	void picksTestsFromTheCommandLineAndRerunsOnlyWhatFailed() throws IOException {
		runner(Map.of(), "runClientGameTest", "-Pspawnproof.games=2", "-PfakeFailing=b,d").buildAndFail();
		assertEquals(List.of("a", "b", "c", "d"), names(results()));

		// Only what failed, with b fixed in the meantime.
		runner(Map.of(), "runClientGameTest", "-Pspawnproof.onlyFailures", "-PfakeFailing=d").buildAndFail();
		Map<String, Object> rerun = results();
		assertEquals(List.of("b", "d"), names(rerun));
		assertTrue(Json.bool(Json.obj(rerun, "selection"), "onlyFailures"));

		runner(Map.of(), "runClientGameTest", "-Pspawnproof.onlyFailures").build();
		assertEquals(List.of("d"), names(results()), "b passed last time, so it isn't run again");

		BuildResult nothing = runner(Map.of(), "runClientGameTest", "-Pspawnproof.onlyFailures").build();
		assertTrue(nothing.getOutput().contains("Nothing failed in the last run"), nothing.getOutput());
		assertEquals(List.of(), names(results()));

		// The command line's tests reach the game (spawnproof.tests), and an on-request test runs when named.
		runner(Map.of(), "runClientGameTest", "-Pspawnproof.games=1", "-Pspawnproof.tests=c,a").build();
		assertEquals(List.of("a", "c"), names(results()), "in their listed order");
	}

	@Test
	void aGameThatCrashesTakesOnlyItsTestWithIt() throws IOException {
		BuildResult result = runner(Map.of(), "runClientGameTest", "-Pspawnproof.games=1", "-Pspawnproof.tests=a,crash,b,c").buildAndFail();
		Map<String, Object> r = results();
		assertEquals(List.of("a", "crash", "b", "c"), names(r));
		Map<String, Object> crash = Json.list(r, "tests").get(1);
		assertEquals("error", crash.get("status"), result.getOutput());
		assertTrue(Json.str(Json.obj(crash, "error"), "message").contains("exit code 3"), crash.toString());
		for (int i : new int[] {0, 2, 3}) assertEquals("passed", Json.list(r, "tests").get(i).get("status"), "the rest ran: " + r);
		assertEquals(2, Json.list(r, "games").size(), "a second game took over from the first: " + Json.list(r, "games"));
		assertTrue(result.getOutput().contains("in place of game 1"), result.getOutput());
	}

	@Test
	void onCiTheGamesRunWithoutTheQueue() throws IOException {
		runner(Map.of("CI", "true"), "runClientGameTest", "-Pspawnproof.games=2").build();
		assertEquals("passed", results().get("status"));
		assertEquals(4, names(results()).size());
		assertFalse(Files.exists(Dirs.socket()), "no daemon was started");
		assertTrue(KeptRuns.all().isEmpty(), "nobody looks at results kept on CI");
	}

	@Test
	void worksWithTheConfigurationCache() throws IOException {
		runner(Map.of(), "runClientGameTest", "-Pspawnproof.games=2", "--configuration-cache").build();
		Files.delete(project.resolve("run/spawnproof/results.json"));
		BuildResult again = runner(Map.of(), "runClientGameTest", "-Pspawnproof.games=2", "--configuration-cache").build();
		assertTrue(again.getOutput().contains("Reusing configuration cache"), again.getOutput());
		assertEquals(2, Json.list(results(), "games").size());
	}

	@Test
	void aGameOnItsOwnHasItsResultsKeptToo() throws IOException {
		runner(Map.of(), "runClientGameTestAlone").buildAndFail();
		List<Map<String, Object>> kept = KeptRuns.all();
		assertEquals(1, kept.size(), "kept though its task failed: " + kept);
		assertEquals("one game", kept.get(0).get("how"));
		assertEquals(":runClientGameTestAlone", kept.get(0).get("task"));
		assertTrue(Files.isRegularFile(KeptRuns.folder().resolve(Json.str(kept.get(0), "id")).resolve("screenshots/hat/front.png")));
	}

	@Test
	void theDaemonServesTheKeptRunsAndALiveStatus() throws IOException {
		runner(Map.of(), "runClientGameTest", "-Pspawnproof.games=2").build();
		String id = Json.str(KeptRuns.all().get(0), "id");

		List<Map<String, Object>> projects = Json.list(Client.call(Map.of("type", "projects"), false), "projects");
		assertEquals("mod", Json.str(projects.get(0), "name"), projects.toString());
		assertEquals(id, Json.str(Json.obj(projects.get(0), "lastRun"), "id"));
		assertEquals(project.toRealPath().toString(), Json.str(Json.list(projects.get(0), "checkouts").get(0), "dir"));

		List<Map<String, Object>> runs = Json.list(Client.call(Map.of("type", "runs", "project", "mod"), false), "runs");
		assertEquals(List.of(id), runs.stream().map(r -> Json.str(r, "id")).toList());

		Map<String, Object> run = Client.call(Map.of("type", "run", "id", id), false);
		assertEquals(4, Json.list(Json.obj(run, "results"), "tests").size());
		assertTrue(Files.isDirectory(Path.of(Json.str(run, "folder"))));
		IOException notOne = assertThrows(IOException.class, () -> Client.call(Map.of("type", "run", "id", "../../config"), false));
		assertTrue(notOne.getMessage().startsWith("no kept run"), notOne.getMessage());

		try (Wire wire = Wire.connect(Dirs.socket())) {
			assertEquals("welcome", wire.read().get("type"));
			wire.send(Map.of("type", "follow"));
			for (int i = 0; i < 2; i++) {
				Map<String, Object> status = wire.read();
				assertEquals("status", status.get("type"), "status after status, for as long as the dashboard listens");
				assertEquals(KeptRuns.folder().toAbsolutePath().toString(), status.get("runsFolder"));
			}
		}
	}

	@Test
	void withoutTheSettingTheGameRunsOnItsOwn() throws IOException {
		// The stand-in needs a run to talk to, so on its own it fails: what matters is that no run started it.
		BuildResult result = runner(Map.of(), "runClientGameTest").buildAndFail();
		assertFalse(result.getOutput().contains("running the tests across"), result.getOutput());
		assertFalse(Files.exists(project.resolve("run/games")));
	}
}
