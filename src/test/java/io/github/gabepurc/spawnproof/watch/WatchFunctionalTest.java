package io.github.gabepurc.spawnproof.watch;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.gabepurc.spawnproof.Json;
import io.github.gabepurc.spawnproof.Wire;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Watching a repo, end to end: the spawnproof command and a real daemon in a home of their own, a stand-in for GitHub's API,
 * and a mod repo whose Gradle wrapper is a stand-in too. Its one test fails when the commit has fail.txt; with broken.txt
 * the build fails before it, and with slow.txt it takes a minute. While the home has a file named hold, every build waits.
 */
class WatchFunctionalTest {
	@TempDir
	Path temp;
	private Path home;
	private Path bare;
	private Path work;
	private FakeGitHub github;

	private static final String GRADLEW = """
		#!/bin/sh
		# A stand-in for a mod's Gradle wrapper, running its one test.
		echo "fake gradle $*"
		if [ -f slow.txt ]; then sleep 60; fi
		while [ -f "$SPAWNPROOF_HOME/hold" ]; do sleep 0.1; done
		if [ -f broken.txt ]; then
			echo "* What went wrong:"
			echo "Compilation failed: the code doesn't build"
			echo "* Try:"
			exit 1
		fi
		out="$PWD/build/run/spawnproof"
		mkdir -p "$out"
		if [ -f fail.txt ]; then status=failed; passed=0; failed=1; ok=false; else status=passed; passed=1; failed=0; ok=true; fi
		cat > "$out/results.json" <<JSON
		{"format":"spawnproof-results","status":"$status","counts":{"tests":1,"passed":$passed,"failed":$failed,"errors":0,"notRun":0,"checks":1,"failedChecks":$failed},
		 "tests":[{"name":"rides","status":"$status","failedChecks":$failed,"checks":[{"name":"stays on","ok":$ok}]}]}
		JSON
		echo "$*" > "$out/args.txt"
		printf '{"task":":runClientGameTest","results":"%s"}\\n' "$out/results.json" >> "$SPAWNPROOF_RESULTS_LOG"
		[ "$status" = passed ]
		""";

	@BeforeEach
	void setUp() throws Exception {
		github = new FakeGitHub();
		home = temp.resolve("home");
		Files.createDirectories(home.resolve("config"));
		Files.writeString(home.resolve("config/config.properties"), "slots=2\ndisplay=none\nwatchSeconds=1\ngithubApi=" + github.url() + "\n");
		Files.writeString(home.resolve("config/github-token"), FakeGitHub.TOKEN + "\n");

		bare = temp.resolve("origin.git");
		git(temp, "init", "--quiet", "--bare", bare.toString());
		work = temp.resolve("mod");
		Files.createDirectories(work);
		git(work, "init", "--quiet");
		Files.writeString(work.resolve("gradlew"), GRADLEW);
		Files.setPosixFilePermissions(work.resolve("gradlew"), PosixFilePermissions.fromString("rwxr-xr-x"));
		Files.writeString(work.resolve(".gitignore"), "build/\n");
		git(work, "add", "-A");
		git(work, "commit", "--quiet", "-m", "A mod");
		git(work, "remote", "add", "origin", "https://github.com/test/mod.git");
		github.cloneUrl = bare.toString();
	}

	@AfterEach
	void tearDown() throws Exception {
		try {
			cli(work, "stop", "--force");
		} finally {
			github.stop();
		}
	}

	@Test
	void pullRequestsAreTestedAndToldHowTheyWent() throws Exception {
		try {
			// A pull request that passes.
			String b = commit("Ride better", null, null);
			pr(1, b, false);
			String said = cli(work, "watch", "--games", "2");
			assertTrue(said.contains("Watching test/mod."), said);
			Map<String, Object> passed = awaitStatus(b, "success");
			assertEquals("1 test passed, on " + Watcher.host() + " in ", Json.str(passed, "description").replaceAll("in \\d+s$", "in "));
			List<String> steps = github.statuses(b).stream().map(s -> Json.str(s, "state") + ": " + Json.str(s, "description")).toList();
			assertTrue(steps.get(0).startsWith("pending: Waiting its turn on "), steps.toString());
			assertTrue(steps.get(1).startsWith("pending: Testing on "), steps.toString());
			assertTrue(github.comments(1).isEmpty(), "a pull request that passes gets no comment");
			Path kept = home.resolve("state/watch/test/mod/results/pr-1/runClientGameTest");
			assertTrue(Files.readString(kept.resolve("args.txt")).contains("-Pspawnproof.games=2"), "the watch's options reach the build");

			// A new commit that fails: a comment says what, and the status links to it. Someone else's comment that starts like
			// Spawnproof's is left alone.
			github.comment(1, "someone", GitHub.MARKER + "\nQuoting Spawnproof");
			String c = commit("Fall off", "fail.txt", null);
			pr(1, c, false);
			Map<String, Object> failed = awaitStatus(c, "failure");
			assertTrue(Json.str(failed, "description").startsWith("1 of 1 test failed"), failed.toString());
			List<Map<String, Object>> comments = github.comments(1);
			assertEquals(1, comments.size());
			String comment = Json.str(comments.get(0), "body");
			assertTrue(comment.contains("FAIL rides > stays on"), comment);
			assertEquals(Json.str(comments.get(0), "html_url"), Json.str(failed, "target_url"));

			// One that doesn't build: what Gradle said goes in the same comment.
			String d = commit("Break it", "broken.txt", null);
			pr(1, d, false);
			awaitStatus(d, "failure");
			comment = Json.str(github.comments(1).get(0), "body");
			assertTrue(comment.contains("The build failed before the tests ran") && comment.contains("Compilation failed: the code doesn't build"), comment);

			// Fixed: the comment is edited to say so, not added to.
			String e = commit("Fix it", null, List.of("fail.txt", "broken.txt"));
			pr(1, e, false);
			awaitStatus(e, "success");
			comments = github.comments(1);
			assertEquals(1, comments.size());
			assertTrue(Json.str(comments.get(0), "body").contains("1 test passed"), comments.toString());

			// A commit replaced while it's tested: the old one stops, and the new one is tested.
			String f = commit("Slow down", "slow.txt", null);
			pr(1, f, false);
			await(() -> github.statuses(f).stream().anyMatch(s -> Json.str(s, "description").startsWith("Testing on")), "the slow commit to start");
			String g = commit("Speed up", null, List.of("slow.txt"));
			pr(1, g, false);
			Map<String, Object> replaced = awaitStatus(f, "error");
			assertEquals("Not finished: a newer commit replaced it", Json.str(replaced, "description"));
			awaitStatus(g, "success");

			// A fork's pull request waits until someone asks.
			String h = commit("From a stranger", null, null);
			pr(2, h, true);
			await(() -> cliQuiet(work, "status", "--json").contains("From a fork"), "the fork's pull request to be seen");
			assertTrue(github.statuses(h).isEmpty(), "a fork's pull request isn't tested on its own");
			said = cli(work, "pr", "2");
			assertTrue(said.contains("Pull request #2"), said);
			awaitStatus(h, "success");

			// Watching keeps Spawnproof running. A plain stop is turned down, and so is a build with an older Spawnproof, which
			// would replace it with a daemon that can't watch. A newer Spawnproof replacing it picks the watching back up.
			String[] stop = run(work, "stop");
			assertTrue(!stop[0].equals("0") && stop[1].contains("watching test/mod"), String.join(": ", stop));
			assertEquals("error", Json.str(tellDaemon(Map.of("type", "stop")), "type"), "an older Spawnproof's build doesn't replace it");
			assertEquals("ok", Json.str(tellDaemon(Map.of("type", "stop", "replace", true)), "type"));
			await(() -> cliQuiet(work, "status").contains("isn't running"), "the daemon to stop");
			int before = github.statuses(g).size();
			said = cli(work, "pr", "1");
			assertTrue(said.contains("Pull request #1"), "the new daemon still watches test/mod: " + said);
			await(() -> {
				List<Map<String, Object>> now = github.statuses(g);
				return now.size() > before && "success".equals(Json.str(now.get(now.size() - 1), "state"));
			}, "#1 to be tested again");

			// Merged while it's tested: the test goes on, and the commit gets its status.
			Path hold = home.resolve("hold");
			Files.writeString(hold, "");
			String j = commit("Merge me", null, null);
			pr(3, j, false);
			awaitTesting(j);
			github.close(3, true);
			await(() -> github.lookups(3) > 0, "Spawnproof to see #3 was merged");
			Files.delete(hold);
			awaitStatus(j, "success");
			await(() -> !cliQuiet(work, "status", "--json").contains("Pull request 3"), "#3 to be forgotten once tested");
			assertTrue(github.statuses(j).stream().noneMatch(s -> "error".equals(Json.str(s, "state"))), github.statuses(j).toString());

			// Closed without merging, while it waits and while it's tested: GitHub hears neither will be finished.
			Files.writeString(hold, "");
			String k = commit("Close me", null, null);
			pr(4, k, false);
			awaitTesting(k);
			String l = commit("Close me too", null, null);
			pr(5, l, false);
			await(() -> github.statuses(l).stream().anyMatch(s -> Json.str(s, "description").startsWith("Waiting its turn")), "#5 to wait");
			github.close(5, false);
			assertEquals("Not tested: the pull request was closed", Json.str(awaitStatus(l, "error"), "description"));
			github.close(4, false);
			assertEquals("Not finished: the pull request was closed", Json.str(awaitStatus(k, "error"), "description"));
			Files.delete(hold);

			// Stopped while it's tested, and replaced while Spawnproof is down: the old commit hears it won't be finished.
			Files.writeString(hold, "");
			String m = commit("Interrupt me", null, null);
			pr(6, m, false);
			awaitTesting(m);
			restart(() -> pr(6, commit("Replace me", null, null), false), hold);
			String n = git(work, "rev-parse", "HEAD");
			assertEquals("Not finished: a newer commit replaced it", Json.str(awaitStatus(m, "error"), "description"));
			awaitStatus(n, "success");

			// A fork's pull request asked for and left unfinished is finished when Spawnproof is back.
			Files.writeString(hold, "");
			String o = commit("From a stranger, slowly", null, null);
			pr(7, o, true);
			cli(work, "pr", "7");
			awaitTesting(o);
			restart(() -> {}, hold);
			awaitStatus(o, "success");

			String status = cli(work, "status");
			assertTrue(status.contains("Watching (pull requests tested here)") && status.contains("test/mod") && status.contains("#1 passed"), status);

			said = cli(work, "unwatch");
			assertTrue(said.contains("Stopped watching test/mod"), said);
			assertFalse(Files.exists(home.resolve("state/watch/test/mod")), "the clone is moved aside at once");
			await(() -> isEmpty(home.resolve("state/watch/.unwatched")), "the clone to be deleted");
			assertTrue(Files.readString(home.resolve("config/watching.json")).contains("\"watching\": []"), "nothing is watched");

			// Watched again straight away: a fresh clone, tested as before.
			cli(work, "watch", "--games", "2");
			String q = commit("Watched again", null, null);
			pr(1, q, false);
			awaitStatus(q, "success");
			Map<String, Object> theirs = github.allComments(1).stream().filter(x -> !FakeGitHub.LOGIN.equals(Json.str(Json.obj(x, "user"), "login")))
				.findFirst().orElseThrow();
			assertEquals(GitHub.MARKER + "\nQuoting Spawnproof", Json.str(theirs, "body"), "someone else's comment is left as it was");
		} catch (AssertionError | Exception e) {
			Path log = home.resolve("state/daemon.log");
			System.out.println("---- daemon.log\n" + (Files.exists(log) ? Files.readString(log) : "(none)"));
			throw e;
		}
	}

	// ---- The mod's repo.

	/** Commits on top of the last commit, adding a file and removing some, and puts it in the bare repo for GitHub to have. */
	private String commit(String message, String add, List<String> remove) throws Exception {
		if (add != null) Files.writeString(work.resolve(add), message + "\n");
		if (remove != null) for (String r : remove) Files.deleteIfExists(work.resolve(r));
		git(work, "add", "-A");
		git(work, "commit", "--quiet", "--allow-empty", "-m", message);
		String sha = git(work, "rev-parse", "HEAD");
		git(work, "push", "--quiet", "--force", bare.toString(), "HEAD:refs/heads/main");
		return sha;
	}

	/** Opens a pull request at this commit, or moves it there: GitHub keeps each one's commit as refs/pull/<n>/head. */
	private void pr(long number, String sha, boolean fork) throws Exception {
		git(work, "push", "--quiet", "--force", bare.toString(), sha + ":refs/pull/" + number + "/head");
		github.pull(number, sha, fork);
	}

	private static String git(Path dir, String... args) throws Exception {
		List<String> command = new ArrayList<>(List.of("git"));
		command.addAll(List.of(args));
		ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
		pb.environment().putAll(Map.of("GIT_AUTHOR_NAME", "Test", "GIT_AUTHOR_EMAIL", "test@example.com", "GIT_COMMITTER_NAME", "Test",
			"GIT_COMMITTER_EMAIL", "test@example.com"));
		Process p = pb.start();
		String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
		if (p.waitFor() != 0) throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
		return out;
	}

	// ---- The spawnproof command, run in a folder.

	private String cli(Path dir, String... args) throws Exception {
		String[] result = run(dir, args);
		if (!result[0].equals("0")) fail("spawnproof " + String.join(" ", args) + " exited " + result[0] + ":\n" + result[1]);
		return result[1];
	}

	private String cliQuiet(Path dir, String... args) {
		try {
			return run(dir, args)[1];
		} catch (Exception e) {
			return "";
		}
	}

	private String[] run(Path dir, String... args) throws Exception {
		List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
			System.getProperty("java.class.path"), "io.github.gabepurc.spawnproof.cli.Main"));
		command.addAll(List.of(args));
		ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
		pb.environment().put("SPAWNPROOF_HOME", home.toString());
		Process p = pb.start();
		p.getOutputStream().close();
		String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (!p.waitFor(60, TimeUnit.SECONDS)) {
			p.destroyForcibly();
			fail("spawnproof " + String.join(" ", args) + " didn't finish");
		}
		return new String[] {Integer.toString(p.exitValue()), out};
	}

	/** Sends the daemon one request straight down its socket, as any version of Spawnproof would, and returns its answer. */
	private Map<String, Object> tellDaemon(Map<String, Object> request) throws IOException {
		try (Wire wire = Wire.connect(home.resolve("run/daemon.sock"))) {
			wire.read(); // its welcome
			wire.send(request);
			return wire.read();
		}
	}

	/** Stops Spawnproof while a build waits on the hold file, does something while it's down, and starts it again. */
	private void restart(ThrowingRunnable whileDown, Path hold) throws Exception {
		cli(work, "stop", "--force");
		await(() -> cliQuiet(work, "status").contains("isn't running"), "the daemon to stop");
		whileDown.run();
		Files.deleteIfExists(hold);
		cli(work, "watch", "--games", "2");
	}

	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	private static boolean isEmpty(Path dir) {
		try (java.util.stream.Stream<Path> files = Files.list(dir)) {
			return files.findAny().isEmpty();
		} catch (IOException e) {
			return !Files.exists(dir);
		}
	}

	// ---- Waiting.

	private void awaitTesting(String sha) throws InterruptedException {
		await(() -> github.statuses(sha).stream().anyMatch(s -> Json.str(s, "description").startsWith("Testing on")), sha.substring(0, 7) + " to be tested");
	}

	private Map<String, Object> awaitStatus(String sha, String state) throws InterruptedException {
		await(() -> github.statuses(sha).stream().anyMatch(s -> state.equals(Json.str(s, "state"))), sha.substring(0, 7) + " to be " + state);
		return github.statuses(sha).stream().filter(s -> state.equals(Json.str(s, "state"))).reduce((a, b) -> b).orElseThrow();
	}

	private static void await(BooleanSupplier condition, String what) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 60_000;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) fail("waited a minute for " + what);
			Thread.sleep(200);
		}
	}

	/** Enough of GitHub's API for a watched repo: its pull requests, statuses and comments, kept in memory. */
	private static final class FakeGitHub {
		static final String TOKEN = "test-token";
		static final String LOGIN = "spawnproof-tester";
		private static final Pattern STATUSES = Pattern.compile("/repos/test/mod/(?:statuses|commits)/(\\w+)(/statuses)?");
		private static final Pattern COMMENTS = Pattern.compile("/repos/test/mod/issues/(\\d+)/comments");
		private static final Pattern COMMENT = Pattern.compile("/repos/test/mod/issues/comments/(\\d+)");
		private static final Pattern PULL = Pattern.compile("/repos/test/mod/pulls/(\\d+)");
		private final HttpServer server;
		volatile String cloneUrl;
		private final Map<Long, Map<String, Object>> pulls = new LinkedHashMap<>();
		private int version;
		private final List<Map<String, Object>> statuses = new ArrayList<>();
		private final List<Map<String, Object>> comments = new ArrayList<>();
		private long nextId = 100;
		private final Map<Long, Integer> lookups = new LinkedHashMap<>();

		FakeGitHub() throws IOException {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", this::handle);
			server.start();
		}

		String url() {
			return "http://127.0.0.1:" + server.getAddress().getPort();
		}

		void stop() {
			server.stop(0);
		}

		synchronized void pull(long number, String sha, boolean fork) {
			Map<String, Object> p = new LinkedHashMap<>();
			p.put("number", number);
			p.put("title", "Pull request " + number);
			p.put("state", "open");
			p.put("head", Map.of("sha", sha, "repo", Map.of("full_name", fork ? "stranger/mod" : "test/mod")));
			p.put("base", Map.of("repo", Map.of("full_name", "test/mod", "clone_url", cloneUrl)));
			pulls.put(number, p);
			version++;
		}

		synchronized List<Map<String, Object>> statuses(String sha) {
			return statuses.stream().filter(s -> sha.equals(s.get("sha"))).toList();
		}

		/** Spawnproof's comments on a pull request. */
		synchronized List<Map<String, Object>> comments(long pr) {
			return allComments(pr).stream().filter(c -> LOGIN.equals(Json.str(Json.obj(c, "user"), "login"))).toList();
		}

		synchronized List<Map<String, Object>> allComments(long pr) {
			return comments.stream().filter(c -> Long.valueOf(pr).equals(c.get("pr"))).toList();
		}

		/** Someone else's comment. */
		synchronized void comment(long pr, String login, String body) {
			comments.add(newComment(pr, login, body));
		}

		private Map<String, Object> newComment(long pr, String login, String body) {
			long id = nextId++;
			Map<String, Object> c = new LinkedHashMap<>();
			c.put("id", id);
			c.put("pr", pr);
			c.put("user", Map.of("login", login));
			c.put("body", body);
			c.put("html_url", "https://github.com/test/mod/pull/" + pr + "#issuecomment-" + id);
			return c;
		}

		/** Closes a pull request, merged or not. */
		synchronized void close(long number, boolean merged) {
			Map<String, Object> p = pulls.get(number);
			p.put("state", "closed");
			p.put("merged", merged);
			version++;
		}

		/** How many times Spawnproof has asked about this one pull request. */
		synchronized int lookups(long number) {
			return lookups.getOrDefault(number, 0);
		}

		private void handle(HttpExchange x) throws IOException {
			String method = x.getRequestMethod();
			String path = x.getRequestURI().getPath();
			String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			if (!("Bearer " + TOKEN).equals(x.getRequestHeaders().getFirst("Authorization"))) {
				reply(x, 401, Map.of("message", "Bad credentials"));
				return;
			}
			synchronized (this) {
				Matcher m;
				if (method.equals("GET") && path.equals("/user")) {
					reply(x, 200, Map.of("login", LOGIN));
				} else if (method.equals("GET") && path.equals("/repos/test/mod")) {
					reply(x, 200, Map.of("full_name", "test/mod", "permissions", Map.of("push", true), "clone_url", cloneUrl));
				} else if (method.equals("GET") && path.equals("/repos/test/mod/pulls")) {
					String etag = "\"v" + version + "\"";
					if (etag.equals(x.getRequestHeaders().getFirst("If-None-Match"))) {
						x.sendResponseHeaders(304, -1);
						x.close();
						return;
					}
					x.getResponseHeaders().add("ETag", etag);
					reply(x, 200, pulls.values().stream().filter(p -> "open".equals(p.get("state"))).toList());
				} else if (method.equals("GET") && (m = PULL.matcher(path)).matches()) {
					long number = Long.parseLong(m.group(1));
					lookups.merge(number, 1, Integer::sum);
					Map<String, Object> p = pulls.get(number);
					reply(x, p == null ? 404 : 200, p == null ? Map.of("message", "Not Found") : p);
				} else if ((m = STATUSES.matcher(path)).matches()) {
					String sha = m.group(1);
					if (method.equals("POST")) {
						Map<String, Object> s = new LinkedHashMap<>(Json.parseObject(body));
						s.put("sha", sha);
						statuses.add(s);
						reply(x, 201, s);
					} else {
						List<Map<String, Object>> newestFirst = new ArrayList<>(statuses(sha));
						java.util.Collections.reverse(newestFirst);
						reply(x, 200, newestFirst);
					}
				} else if ((m = COMMENTS.matcher(path)).matches()) {
					long pr = Long.parseLong(m.group(1));
					if (method.equals("POST")) {
						Map<String, Object> c = newComment(pr, LOGIN, Json.str(Json.parseObject(body), "body"));
						comments.add(c);
						reply(x, 201, c);
					} else {
						reply(x, 200, allComments(pr));
					}
				} else if (method.equals("PATCH") && (m = COMMENT.matcher(path)).matches()) {
					long id = Long.parseLong(m.group(1));
					Map<String, Object> c = comments.stream().filter(k -> Long.valueOf(id).equals(k.get("id"))).findFirst().orElse(null);
					if (c == null) {
						reply(x, 404, Map.of("message", "Not Found"));
						return;
					}
					if (!LOGIN.equals(Json.str(Json.obj(c, "user"), "login"))) {
						reply(x, 403, Map.of("message", "Resource not accessible by integration"));
						return;
					}
					c.put("body", Json.str(Json.parseObject(body), "body"));
					reply(x, 200, c);
				} else {
					reply(x, 404, Map.of("message", "Not Found: " + method + " " + path));
				}
			}
		}

		private static void reply(HttpExchange x, int code, Object body) throws IOException {
			byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
			x.getResponseHeaders().add("Content-Type", "application/json");
			x.sendResponseHeaders(code, bytes.length);
			x.getResponseBody().write(bytes);
			x.close();
		}
	}
}
