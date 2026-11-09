package io.github.gabepurc.spawnproof.cli;

import io.github.gabepurc.spawnproof.Dirs;
import io.github.gabepurc.spawnproof.client.DaemonStarter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Gradle command `spawnproof test` runs: the mod's game task through its wrapper, as a test run across games. The
 * daemon builds the same command for the pull requests it tests, from the options `spawnproof watch` was given.
 */
public final class TestCommand {
	public static final String USAGE = "test [<tests>] [--games <n>] [--only-failures] [--skip <tests>] [--clock real] [--graphics normal] "
		+ "[--task <task>] [-- <gradle arguments>]";

	private TestCommand() {}

	/** What's wrong with the options, said as the usage line to show. */
	public static final class BadOptions extends Exception {
		BadOptions(String usage) {
			super(usage);
		}
	}

	/** The command for these options, run from {@code here}: the wrapper is looked for there and in the folders above it. */
	public static List<String> build(Path here, List<String> args) throws BadOptions, IOException {
		List<String> tests = new ArrayList<>();
		Map<String, String> options = new LinkedHashMap<>();
		boolean onlyFailures = false;
		List<String> gradleArgs = new ArrayList<>();
		for (int i = 0; i < args.size(); i++) {
			String a = args.get(i);
			if (a.equals("--")) {
				gradleArgs.addAll(args.subList(i + 1, args.size()));
				break;
			}
			switch (a) {
				case "--only-failures" -> onlyFailures = true;
				case "--games", "--skip", "--clock", "--graphics", "--task" -> {
					if (++i >= args.size()) throw new BadOptions(USAGE);
					options.put(a.substring(2), args.get(i));
				}
				default -> {
					if (a.startsWith("-")) throw new BadOptions(USAGE);
					tests.add(a);
				}
			}
		}
		String games = options.get("games");
		if (games != null && !games.matches("[1-9][0-9]*")) throw new BadOptions("test --games <n>, a number of games from 1 up");
		if (here == null) return List.of();

		Path wrapper = null;
		for (Path dir = here; dir != null && wrapper == null; dir = dir.getParent()) {
			Path candidate = dir.resolve(Dirs.isWindows() ? "gradlew.bat" : "gradlew");
			if (Files.isRegularFile(candidate)) wrapper = candidate;
		}
		if (wrapper == null) throw new IOException("no Gradle wrapper (gradlew) in " + here + " or above it: run spawnproof test in a mod's folder");

		List<String> command = new ArrayList<>();
		command.add(wrapper.toString());
		command.add(options.getOrDefault("task", "runClientGameTest"));
		command.add("-Pspawnproof.games" + (games != null ? "=" + games : ""));
		if (!tests.isEmpty()) command.add("-Pspawnproof.tests=" + String.join(",", tests));
		if (options.containsKey("skip")) command.add("-Pspawnproof.skip=" + options.get("skip"));
		if (options.containsKey("clock")) command.add("-Pspawnproof.clock=" + options.get("clock"));
		if (options.containsKey("graphics")) command.add("-Pspawnproof.graphics=" + options.get("graphics"));
		if (onlyFailures) command.add("-Pspawnproof.onlyFailures");
		command.addAll(gradleArgs);
		return command;
	}

	/** Checks the options without running anything (spawnproof watch keeps them for later). */
	public static void check(List<String> args) throws BadOptions {
		try {
			build(null, args);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Applies the plugin from Spawnproof's jar to the build, by an init script, so the command works in any mod's folder; a
	 * build with the plugin applied already uses this copy. False when running from classes rather than a jar (the tests),
	 * where the build uses its own copy of the plugin, if it has one.
	 */
	public static boolean applyPlugin(List<String> command) throws IOException {
		Path jar = DaemonStarter.classpath();
		if (Files.isDirectory(jar)) return false;
		Path script = Dirs.state().resolve("init").resolve("spawnproof-test.gradle");
		Files.createDirectories(script.getParent());
		Files.writeString(script, Main.initScript(jar, List.of("// Written by `spawnproof test`, which runs builds with it: it applies the plugin from Spawnproof's jar.")));
		command.addAll(2, List.of("-I", script.toString()));
		return true;
	}
}
