package io.github.gabepurc.spawnproof.cli;

import io.github.gabepurc.spawnproof.Dirs;
import io.github.gabepurc.spawnproof.Git;
import io.github.gabepurc.spawnproof.Json;
import io.github.gabepurc.spawnproof.Version;
import io.github.gabepurc.spawnproof.client.Client;
import io.github.gabepurc.spawnproof.client.DaemonStarter;
import io.github.gabepurc.spawnproof.client.Text;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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
		  install [--init-script] Put `spawnproof` in ~/.local/bin; --init-script also queues every Gradle build's games
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
				case "install":
					return install(rest, out);
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
		if (args.contains("--init-script")) {
			String gradleHome = System.getenv("GRADLE_USER_HOME");
			Path initScript = (gradleHome != null ? Path.of(gradleHome) : Path.of(System.getProperty("user.home"), ".gradle"))
				.resolve("init.d").resolve("spawnproof.gradle");
			Files.createDirectories(initScript.getParent());
			Files.writeString(initScript, String.join("\n",
				"// Written by `spawnproof install --init-script`: every Gradle build on this machine queues its Minecraft test games",
				"// through Spawnproof (https://github.com/GabePurc/spawnproof). Delete this file to stop.",
				"initscript {",
				"\tdependencies {",
				"\t\tclasspath files('" + jar.toString().replace("\\", "\\\\").replace("'", "\\'") + "')",
				"\t}",
				"}",
				"beforeSettings { settings ->",
				"\tsettings.pluginManager.apply(io.github.gabepurc.spawnproof.gradle.SpawnproofPlugin)",
				"}",
				""));
			out.println("Installed " + initScript);
		}
		return 0;
	}
}
