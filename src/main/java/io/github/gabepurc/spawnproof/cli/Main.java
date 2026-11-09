package io.github.gabepurc.spawnproof.cli;

import io.github.gabepurc.spawnproof.Dirs;
import io.github.gabepurc.spawnproof.Git;
import io.github.gabepurc.spawnproof.Json;
import io.github.gabepurc.spawnproof.Version;
import io.github.gabepurc.spawnproof.client.Client;
import io.github.gabepurc.spawnproof.client.DaemonStarter;
import io.github.gabepurc.spawnproof.client.Text;
import io.github.gabepurc.spawnproof.daemon.DaemonConfig;
import io.github.gabepurc.spawnproof.watch.GitHub;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The spawnproof command. */
public final class Main {
	private static final String USAGE = """
		Usage: spawnproof <command>

		  status [--json]        What's running, waiting and playing, and the hidden display
		  pause | resume         Hold new games (running ones finish) / let them start again
		  slots <n>              How many test games run at once
		  display <mode>         auto (weston, else Xvfb), weston, xvfb, or none (games show on the desktop)
		  config [<key> <value>] Show or change a setting (slots, display, resolution, holdWhilePlaying, ...)
		  run [options] -- <command...>
		                         Run any command as a queued game: it waits its turn and gets the hidden display
		      --exclusive          needs the machine to itself
		      --visible            show on the desktop instead
		      --play               you're playing: never waits, and holds test games back
		      --label <text>       shown in status (also SPAWNPROOF_LABEL)
		  test [<tests>] [options] [-- <gradle arguments>]
		                         In a mod's folder: run its client game tests across games, the longest first by past
		                         runs, with one set of results
		      --games <n>          how many games (default: the queue's game slots)
		      --only-failures      rerun only the tests that failed last time
		      --skip <tests>       tests to leave out
		      --clock real         run at normal speed (default: as fast as the games draw)
		      --graphics normal    normal settings, for screenshots people will see
		      --task <task>        the game task (default: runClientGameTest)
		  watch [<test options>] In a mod's folder: test each new commit on its GitHub pull requests on this machine, with
		                         spawnproof test and these options, and put the result on the pull request as a check
		  unwatch [<owner/repo>] Stop watching (this folder's repo, or the one named)
		  pr <number>            Test a pull request of this folder's repo now: one from a fork, or one to run again
		  install [--init-script] [--service]
		                         Put `spawnproof` in ~/.local/bin; --init-script also queues every Gradle build's games;
		                         --service starts the daemon when you log in (systemd), for watched repos
		  dashboard              Open the dashboard app: projects, live games, past results with screenshots
		  start                  Start the daemon if it isn't running (the first game starts it anyway)
		  stop [--force]         Stop the daemon (it starts again with the next game)
		  version
		""";

	private Main() {}

	public static void main(String[] args) {
		System.exit(run(args, System.out, System.err));
	}

	public static int run(String[] args, PrintStream out, PrintStream err) {
		String command = args.length == 0 ? "status" : args[0];
		List<String> rest = Arrays.asList(args).subList(Math.min(1, args.length), args.length);
		try {
			switch (command) {
				case "status":
					return status(rest, out);
				case "pause":
				case "resume":
					Client.call(Map.of("type", command), true);
					out.println(command.equals("pause") ? "Paused: running games finish, no new ones start until `spawnproof resume`." : "Resumed.");
					return 0;
				case "slots":
					return set("slots", one(rest, "slots <n>"), out);
				case "display":
					return set("display", one(rest, "display <auto|weston|xvfb|none>"), out);
				case "config":
					if (rest.isEmpty()) {
						for (Map.Entry<String, Object> e : Json.obj(Client.call(Map.of("type", "status"), true), "config").entrySet()) {
							out.println(e.getKey() + " = " + e.getValue());
						}
						out.println("(" + Dirs.configFile() + ")");
						return 0;
					}
					if (rest.size() != 2) throw new UsageException("config <key> <value>");
					return set(rest.get(0), rest.get(1), out);
				case "stop":
					try {
						Client.call(Map.of("type", "stop", "force", rest.contains("--force")), false);
						out.println("Stopped.");
					} catch (IOException e) {
						if (!Files.exists(Dirs.socket())) {
							out.println("Spawnproof isn't running.");
							return 0;
						}
						throw e;
					}
					return 0;
				case "run":
					return runCommand(rest, err);
				case "test":
					return test(rest, err);
				case "install":
					return install(rest, out);
				case "watch":
					return watch(rest, out, err);
				case "unwatch":
					return unwatch(rest, out);
				case "pr":
					return testPr(rest, out, err);
				case "dashboard":
					return dashboard(out);
				case "start": {
					// This version: an older one is replaced once its games are done, as a build would replace it.
					Map<String, Object> status = Client.callCurrent(Map.of("type", "status"), message -> err.println("spawnproof: " + message));
					out.println("Spawnproof " + Json.str(status, "version") + " is running (pid " + Json.num(status, "pid", -1) + ").");
					return 0;
				}
				case "version":
				case "--version":
					out.println(Version.VERSION);
					return 0;
				case "help":
				case "-h":
				case "--help":
					out.print(USAGE);
					return 0;
				default:
					throw new UsageException(null);
			}
		} catch (UsageException e) {
			if (e.getMessage() != null) err.println("Usage: spawnproof " + e.getMessage());
			else err.print(USAGE);
			return 2;
		} catch (IOException e) {
			err.println("spawnproof: " + e.getMessage());
			return 1;
		}
	}

	private static final class UsageException extends Exception {
		UsageException(String usage) {
			super(usage);
		}
	}

	private static String one(List<String> rest, String usage) throws UsageException {
		if (rest.size() != 1) throw new UsageException(usage);
		return rest.get(0);
	}

	private static int status(List<String> rest, PrintStream out) throws IOException {
		Map<String, Object> status;
		try {
			status = Client.call(Map.of("type", "status"), false);
		} catch (IOException e) {
			if (rest.contains("--json")) out.println("{\"type\":\"status\",\"running\":[],\"waiting\":[],\"playing\":[],\"daemon\":false}");
			else out.println("Spawnproof isn't running; it starts with the first game.");
			return 0;
		}
		out.println(rest.contains("--json") ? Json.write(status) : Text.status(status));
		return 0;
	}

	private static int set(String key, String value, PrintStream out) throws IOException {
		Client.call(Map.of("type", "set", "key", key, "value", value), true);
		out.println(key + " = " + value);
		return 0;
	}

	private static int runCommand(List<String> args, PrintStream err) throws IOException, UsageException {
		boolean exclusive = false;
		boolean visible = false;
		boolean play = false;
		String label = System.getenv("SPAWNPROOF_LABEL");
		int i = 0;
		for (; i < args.size(); i++) {
			String a = args.get(i);
			if (a.equals("--")) {
				i++;
				break;
			}
			if (!a.startsWith("--")) break;
			switch (a) {
				case "--exclusive" -> exclusive = true;
				case "--visible" -> visible = true;
				case "--play" -> play = true;
				case "--label" -> {
					if (++i >= args.size()) throw new UsageException("run --label <text> -- <command...>");
					label = args.get(i);
				}
				default -> throw new UsageException("run [--exclusive] [--visible] [--play] [--label <text>] -- <command...>");
			}
		}
		List<String> command = new ArrayList<>(args.subList(i, args.size()));
		if (command.isEmpty()) throw new UsageException("run [options] -- <command...>");
		if (System.getenv("SPAWNPROOF_LEASE") != null) {
			// Already inside a queued game (a run within a run): waiting again could wait on itself.
			return exec(command, null, null);
		}
		Path dir = Path.of("").toAbsolutePath();
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("project", dir.getFileName() == null ? dir.toString() : dir.getFileName().toString());
		String task = String.join(" ", command);
		request.put("task", task.length() > 80 ? task.substring(0, 77) + "..." : task);
		request.put("dir", dir.toString());
		request.put("branch", Git.branch(dir));
		request.put("label", label);
		request.put("role", play ? "play" : "test");
		request.put("exclusive", exclusive);
		request.put("hidden", !visible);
		request.put("pid", ProcessHandle.current().pid());
		try (Client.Lease lease = Client.acquire(request, message -> err.println("spawnproof: " + message))) {
			if (lease.note != null && !visible && !play) err.println("spawnproof: " + lease.note);
			return exec(command, lease.display, Long.toString(lease.id));
		}
	}

	private static int exec(List<String> command, String display, String leaseId) throws IOException {
		ProcessBuilder pb = new ProcessBuilder(command).inheritIO();
		Map<String, String> env = pb.environment();
		if (leaseId != null) env.put("SPAWNPROOF_LEASE", leaseId);
		if (display != null) {
			env.put("SPAWNPROOF_DISPLAY", display);
			env.put("DISPLAY", display);
			env.remove("WAYLAND_DISPLAY");
			env.put("XDG_SESSION_TYPE", "x11");
		}
		Process p = pb.start();
		try {
			return p.waitFor();
		} catch (InterruptedException e) {
			p.destroy();
			Thread.currentThread().interrupt();
			return 130;
		}
	}

	private static int install(List<String> args, PrintStream out) throws IOException {
		Path jar = DaemonStarter.classpath();
		if (Files.isDirectory(jar)) throw new IOException("install runs from Spawnproof's jar (java -jar spawnproof.jar install)");
		// Earlier copies: the command and the init script move to this one.
		try (var others = Files.list(jar.getParent())) {
			for (Path old : (Iterable<Path>) others::iterator) {
				String name = old.getFileName().toString();
				if (name.startsWith("spawnproof-") && name.endsWith(".jar") && !old.equals(jar)) Files.deleteIfExists(old);
			}
		}
		if (Dirs.isWindows()) {
			out.println("On Windows, run it as: java -jar " + jar + " <command>");
		} else {
			Path bin = Path.of(System.getProperty("user.home"), ".local", "bin", "spawnproof");
			Files.createDirectories(bin.getParent());
			Files.writeString(bin, "#!/bin/sh\n# Written by `spawnproof install`.\nexec \"" + DaemonStarter.java() + "\" -cp \"" + jar
				+ "\" io.github.gabepurc.spawnproof.cli.Main \"$@\"\n");
			Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"));
			out.println("Installed " + bin);
			String path = System.getenv("PATH");
			if (path == null || !Arrays.asList(path.split(":")).contains(bin.getParent().toString())) {
				out.println("(" + bin.getParent() + " isn't on your PATH yet.)");
			}
		}
		if (args.contains("--service") || Files.isRegularFile(serviceFile())) installService(out);
		if (args.contains("--init-script")) {
			String gradleHome = System.getenv("GRADLE_USER_HOME");
			Path initScript = (gradleHome != null ? Path.of(gradleHome) : Path.of(System.getProperty("user.home"), ".gradle"))
				.resolve("init.d").resolve("spawnproof.gradle");
			Files.createDirectories(initScript.getParent());
			Files.writeString(initScript, initScript(jar, List.of(
				"// Written by `spawnproof install --init-script`: every Gradle build on this machine queues its Minecraft test games",
				"// through Spawnproof (https://github.com/GabePurc/spawnproof). Delete this file to stop.")));
			out.println("Installed " + initScript);
		}
		return 0;
	}

	/** An init script that applies the plugin from this jar to a build (beside any copy it applies itself: the first wins). */
	static String initScript(Path jar, List<String> comment) {
		List<String> lines = new ArrayList<>(comment);
		lines.addAll(List.of(
			"initscript {",
			"\tdependencies {",
			"\t\tclasspath files('" + jar.toString().replace("\\", "\\\\").replace("'", "\\'") + "')",
			"\t}",
			"}",
			"beforeSettings { settings ->",
			"\tsettings.pluginManager.apply(io.github.gabepurc.spawnproof.gradle.SpawnproofPlugin)",
			"}",
			""));
		return String.join("\n", lines);
	}

	/** spawnproof test: the mod's game task through its Gradle wrapper, as a test run across games (see TestCommand). */
	private static int test(List<String> args, PrintStream err) throws IOException, UsageException {
		Path here = Path.of("").toAbsolutePath();
		List<String> command;
		try {
			command = TestCommand.build(here, args);
		} catch (TestCommand.BadOptions e) {
			throw new UsageException(e.getMessage());
		}
		err.println("spawnproof: " + String.join(" ", command));
		if (!TestCommand.applyPlugin(command)) {
			err.println("spawnproof: (running from classes, not a jar: the build uses its own copy of the plugin, if it has one)");
		}
		Process p = new ProcessBuilder(command).inheritIO().directory(here.toFile()).start();
		try {
			return p.waitFor();
		} catch (InterruptedException e) {
			p.destroy();
			Thread.currentThread().interrupt();
			return 130;
		}
	}

	/** The GitHub repo this folder is a checkout of (its origin), the checkout's top folder, and this folder in it. */
	private record Here(String repo, Path top, Path dir) {
		/** This folder from the checkout's top, with / between folders: "" at the top. */
		String path() {
			return top.relativize(dir).toString().replace('\\', '/');
		}
	}

	private static Here here() throws IOException {
		Path dir = Path.of("").toAbsolutePath().toRealPath();
		String top = git(dir, "rev-parse", "--show-toplevel");
		if (top == null) throw new IOException(dir + " isn't in a git checkout: run this in a mod's folder");
		String url = git(dir, "remote", "get-url", "origin");
		String repo = GitHub.repoFromUrl(url);
		if (repo == null) throw new IOException("this checkout's origin (" + (url == null ? "none" : url) + ") isn't a GitHub repo");
		return new Here(repo, Path.of(top).toRealPath(), dir);
	}

	/** What a git command prints, or null if it fails. */
	private static String git(Path dir, String... args) throws IOException {
		List<String> command = new ArrayList<>(List.of("git"));
		command.addAll(List.of(args));
		Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		p.getOutputStream().close();
		String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
		try {
			return p.waitFor() == 0 && !out.isEmpty() ? out : null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new java.io.InterruptedIOException("cancelled");
		}
	}

	/**
	 * spawnproof dashboard: opens the dashboard app, a program of its own (made with Tauri, in {@code dashboard/}), after
	 * starting the daemon it talks to. It's told where the daemon's socket is, so it finds the same one this command does.
	 */
	private static int dashboard(PrintStream out) throws IOException {
		Path app = dashboardApp();
		if (app == null) {
			out.println("The dashboard app isn't installed. Until releases carry it, build it from a clone of Spawnproof: in dashboard/, run\n"
				+ "  cargo build --release\n"
				+ "then copy target/release/spawnproof-dashboard to ~/.local/bin (or point SPAWNPROOF_DASHBOARD at it).");
			return 1;
		}
		Client.callCurrent(Map.of("type", "status"), message -> out.println("spawnproof: " + message));
		List<String> command = new ArrayList<>();
		// Its own session, as the daemon has: closing this terminal mustn't close the window.
		if (!Dirs.isWindows() && Files.isExecutable(Path.of("/usr/bin/setsid"))) command.add("/usr/bin/setsid");
		command.add(app.toString());
		ProcessBuilder pb = new ProcessBuilder(command);
		pb.environment().put("SPAWNPROOF_SOCKET", Dirs.socket().toString());
		// For starting the daemon again if it stops while the app is open: this command, from this jar.
		pb.environment().put("SPAWNPROOF_START", String.join("\n", startCommand()));
		pb.redirectErrorStream(true);
		pb.redirectOutput(ProcessBuilder.Redirect.appendTo(Dirs.state().resolve("dashboard.log").toFile()));
		pb.redirectInput(ProcessBuilder.Redirect.PIPE);
		pb.start().getOutputStream().close();
		out.println("Opened the dashboard.");
		return 0;
	}

	/** `spawnproof start` from this jar, with this java: the app runs it, one argument a line, to start the daemon. */
	private static List<String> startCommand() throws IOException {
		List<String> command = new ArrayList<>(List.of(DaemonStarter.java()));
		if (Dirs.override() != null) command.add("-Dspawnproof.home=" + Dirs.override());
		command.addAll(List.of("-cp", DaemonStarter.classpath().toString(), Main.class.getName(), "start"));
		return command;
	}

	/** SPAWNPROOF_DASHBOARD, or spawnproof-dashboard on the PATH or in ~/.local/bin; null if there's none. */
	static Path dashboardApp() {
		String set = System.getenv("SPAWNPROOF_DASHBOARD");
		if (set != null && !set.isBlank()) return Files.isExecutable(Path.of(set)) ? Path.of(set) : null;
		String name = Dirs.isWindows() ? "spawnproof-dashboard.exe" : "spawnproof-dashboard";
		List<Path> places = new ArrayList<>();
		for (String dir : String.valueOf(System.getenv("PATH")).split(java.io.File.pathSeparator)) {
			if (!dir.isBlank()) places.add(Path.of(dir));
		}
		places.add(Path.of(System.getProperty("user.home"), ".local", "bin"));
		for (Path dir : places) {
			Path app = dir.resolve(name);
			if (Files.isRegularFile(app) && Files.isExecutable(app)) return app;
		}
		return null;
	}

	/** A request to the daemon that an older one wouldn't know. */
	private static Map<String, Object> callNew(Map<String, Object> request, PrintStream err) throws IOException {
		try {
			return Client.callCurrent(request, message -> err.println("spawnproof: " + message));
		} catch (IOException e) {
			if (String.valueOf(e.getMessage()).startsWith("unknown request")) {
				throw new IOException("the Spawnproof running now is older than this one and can't do that. It's replaced once its games are done: "
					+ "try again then");
			}
			throw e;
		}
	}

	/** spawnproof watch: the daemon tests each new commit on this repo's pull requests, as spawnproof test here would. */
	private static int watch(List<String> args, PrintStream out, PrintStream err) throws IOException, UsageException {
		try {
			TestCommand.check(args);
		} catch (TestCommand.BadOptions e) {
			throw new UsageException("watch [<tests>] [--games <n>] [--skip <tests>] [--clock real] [--graphics normal] [--task <task>] [-- <gradle arguments>]");
		}
		Here here = here();
		try {
			TestCommand.build(here.dir(), args);
		} catch (TestCommand.BadOptions e) {
			throw new UsageException(e.getMessage());
		}
		GitHub.checkCanReport(DaemonConfig.load(Dirs.configFile()).githubApi(), here.repo());
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("type", "watch");
		request.put("repo", here.repo());
		request.put("source", here.top().toString());
		request.put("path", here.path());
		request.put("args", args);
		Map<String, Object> reply = callNew(request, err);
		String test = "spawnproof test" + (args.isEmpty() ? "" : " " + String.join(" ", args)) + (here.path().isEmpty() ? "" : " in " + here.path());
		out.println("Watching " + here.repo() + ". Each new commit on its pull requests is tested on this machine (" + test + "), and the result "
			+ "goes on the pull request as the \"spawnproof\" check.");
		out.println("- To require it before merging: on GitHub, add \"spawnproof\" to the branch's required status checks.");
		out.println("- Pull requests from forks are tested only when you ask: spawnproof pr <number>.");
		out.println("- The tests run in a clone of its own: " + Json.str(reply, "dir"));
		if (!Files.isRegularFile(serviceFile())) {
			out.println("- Spawnproof stays running while it watches anything. To start it when you log in, too: spawnproof install --service");
		}
		return 0;
	}

	private static int unwatch(List<String> args, PrintStream out) throws IOException, UsageException {
		if (args.size() > 1) throw new UsageException("unwatch [<owner/repo>]");
		String repo = args.isEmpty() ? here().repo() : args.get(0);
		try {
			Client.call(Map.of("type", "unwatch", "repo", repo), false);
		} catch (IOException e) {
			if (!Files.exists(Dirs.socket())) throw new IOException("Spawnproof isn't running, so it isn't watching anything");
			throw e;
		}
		out.println("Stopped watching " + repo + ". Its clone is being deleted.");
		return 0;
	}

	private static int testPr(List<String> args, PrintStream out, PrintStream err) throws IOException, UsageException {
		if (args.size() != 1 || !args.get(0).replaceFirst("^#", "").matches("[1-9][0-9]*")) throw new UsageException("pr <number>");
		long number = Long.parseLong(args.get(0).replaceFirst("^#", ""));
		Map<String, Object> reply = callNew(Map.of("type", "test-pr", "repo", here().repo(), "pr", number), err);
		out.println(Json.str(reply, "message") + " spawnproof status shows how it goes.");
		return 0;
	}

	private static Path serviceFile() {
		String xdg = System.getenv("XDG_CONFIG_HOME");
		Path config = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".config");
		return config.resolve("systemd/user/spawnproof.service");
	}

	/**
	 * A systemd user service that starts the daemon when you log in, so watched repos are looked after without a build to
	 * start it. Installing again points it at the new jar. A daemon already running stays; the service's starts with the
	 * next login, or when that one stops.
	 */
	private static void installService(PrintStream out) throws IOException {
		if (!Dirs.isLinux()) throw new IOException("--service is for Linux (systemd) so far");
		Path systemctl = null;
		for (String dir : List.of("/usr/bin", "/bin", "/usr/local/bin")) if (Files.isExecutable(Path.of(dir, "systemctl"))) systemctl = Path.of(dir, "systemctl");
		if (systemctl == null) throw new IOException("--service needs systemd, and there's no systemctl here");
		Path unit = serviceFile();
		Files.createDirectories(unit.getParent());
		Files.createDirectories(Dirs.state());
		Files.writeString(unit, serviceUnit());
		systemctl(systemctl, "daemon-reload");
		systemctl(systemctl, "enable", "spawnproof.service");
		boolean running;
		try (Client.Connection ignored = Client.connect(false)) {
			running = true;
		} catch (IOException e) {
			running = false;
		}
		if (!running) systemctl(systemctl, "start", "spawnproof.service");
		out.println("Installed " + unit + ": Spawnproof starts when you log in" + (running ? " (the one running now carries on until then)." : ", and is running now."));
	}

	/** The service: the daemon as a build would start it, logging where it always does. */
	static String serviceUnit() throws IOException {
		List<String> command = new ArrayList<>();
		for (String part : DaemonStarter.daemonCommand()) command.add(part.matches("[\\w./:=+-]+") ? part : "\"" + part.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
		return String.join("\n",
			"# Written by `spawnproof install --service`. Stop it with: systemctl --user disable --now spawnproof",
			"[Unit]",
			"Description=Spawnproof: queues Minecraft test games, and tests the pull requests of watched repos",
			"Documentation=https://github.com/GabePurc/spawnproof",
			"",
			"[Service]",
			"ExecStart=" + String.join(" ", command),
			"WorkingDirectory=" + Dirs.state(),
			"StandardOutput=append:" + Dirs.daemonLog(),
			"StandardError=inherit",
			"Restart=on-failure",
			"RestartSec=10",
			"",
			"[Install]",
			"WantedBy=default.target",
			"");
	}

	private static void systemctl(Path systemctl, String... args) throws IOException {
		List<String> command = new ArrayList<>(List.of(systemctl.toString(), "--user"));
		command.addAll(List.of(args));
		Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
		p.getOutputStream().close();
		String said = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
		try {
			if (p.waitFor() != 0) throw new IOException("systemctl --user " + String.join(" ", args) + " failed" + (said.isEmpty() ? "" : ": " + said));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new java.io.InterruptedIOException("cancelled");
		}
	}
}
