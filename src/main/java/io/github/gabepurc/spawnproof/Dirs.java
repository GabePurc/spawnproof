package io.github.gabepurc.spawnproof;

import java.nio.file.Path;

/**
 * Where Spawnproof keeps things. SPAWNPROOF_HOME (or -Dspawnproof.home) puts everything under one folder: the tests use it to
 * run a daemon of their own, apart from the machine's.
 */
public final class Dirs {
	private Dirs() {}

	/** SPAWNPROOF_HOME, or -Dspawnproof.home; null for the usual places. */
	public static Path override() {
		String home = System.getenv("SPAWNPROOF_HOME");
		if (home == null || home.isBlank()) home = System.getProperty("spawnproof.home");
		return home == null || home.isBlank() ? null : Path.of(home).toAbsolutePath();
	}

	private static Path userHome() {
		return Path.of(System.getProperty("user.home"));
	}

	private static String env(String name) {
		String v = System.getenv(name);
		return v == null || v.isBlank() ? null : v;
	}

	public static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
	}

	public static boolean isMac() {
		return System.getProperty("os.name", "").toLowerCase().startsWith("mac");
	}

	public static boolean isLinux() {
		return System.getProperty("os.name", "").toLowerCase().startsWith("linux");
	}

	/** Logs, history, the daemon's lock and its copy of this jar. */
	public static Path state() {
		Path home = override();
		if (home != null) return home.resolve("state");
		if (isWindows()) return Path.of(env("LOCALAPPDATA") != null ? env("LOCALAPPDATA") : userHome().toString(), "spawnproof");
		if (isMac()) return userHome().resolve("Library/Application Support/spawnproof");
		String xdg = env("XDG_STATE_HOME");
		return (xdg != null ? Path.of(xdg) : userHome().resolve(".local/state")).resolve("spawnproof");
	}

	/** The settings file's folder. */
	public static Path config() {
		Path home = override();
		if (home != null) return home.resolve("config");
		if (isWindows() || isMac()) return state();
		String xdg = env("XDG_CONFIG_HOME");
		return (xdg != null ? Path.of(xdg) : userHome().resolve(".config")).resolve("spawnproof");
	}

	/** The socket and the hidden display's name: things that only mean something while this login lasts. */
	public static Path runtime() {
		Path home = override();
		if (home != null) return home.resolve("run");
		String xdg = isLinux() ? env("XDG_RUNTIME_DIR") : null;
		return xdg != null ? Path.of(xdg, "spawnproof") : state().resolve("run");
	}

	public static Path socket() {
		return runtime().resolve("daemon.sock");
	}

	/** Holds the hidden display's name (":5") while it is up; launch wrappers read it. */
	public static Path displayFile() {
		return runtime().resolve("display");
	}

	public static Path configFile() {
		return config().resolve("config.properties");
	}

	public static Path daemonLog() {
		return state().resolve("daemon.log");
	}
}
