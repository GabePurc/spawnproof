package io.github.gabepurc.spawnproof.client;

import io.github.gabepurc.spawnproof.Dirs;
import io.github.gabepurc.spawnproof.Version;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Starts the daemon in the background, from a copy of this jar so it doesn't hold on to a Gradle cache file. */
public final class DaemonStarter {
	private DaemonStarter() {}

	static void start() throws IOException {
		Path state = Dirs.state();
		Files.createDirectories(state);
		List<String> command = new ArrayList<>();
		// Its own session: Ctrl+C in the terminal that started a build must not take the daemon (and others' games) with it.
		if (!Dirs.isWindows() && Files.isExecutable(Path.of("/usr/bin/setsid"))) command.add("/usr/bin/setsid");
		command.add(java());
		command.addAll(List.of("-Xmx96m", "-Xss512k", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Dspawnproof.daemon=true",
			"-cp", classpath().toString(), "io.github.gabepurc.spawnproof.daemon.Daemon"));
		ProcessBuilder pb = new ProcessBuilder(command);
		pb.directory(state.toFile());
		// A home set by system property (tests) reaches the daemon as the variable.
		if (Dirs.override() != null) pb.environment().put("SPAWNPROOF_HOME", Dirs.override().toString());
		pb.redirectErrorStream(true);
		pb.redirectOutput(ProcessBuilder.Redirect.appendTo(Dirs.daemonLog().toFile()));
		pb.redirectInput(ProcessBuilder.Redirect.PIPE);
		Process p = pb.start();
		p.getOutputStream().close();
	}

	/** The java running this code (Gradle's, or the CLI's). */
	public static String java() {
		Path home = Path.of(System.getProperty("java.home"));
		Path exe = home.resolve("bin").resolve(Dirs.isWindows() ? "java.exe" : "java");
		return exe.toString();
	}

	/**
	 * Where the daemon's classes are: a copy of this jar in the state folder, or the classes folder itself when running from
	 * one (tests, an included build).
	 */
	public static Path classpath() throws IOException {
		Path source;
		try {
			source = Path.of(DaemonStarter.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		} catch (URISyntaxException | RuntimeException e) {
			throw new IOException("can't tell where Spawnproof's jar is", e);
		}
		if (Files.isDirectory(source)) return source;
		return installedJar(source);
	}

	static Path installedJar(Path source) throws IOException {
		Path lib = Dirs.state().resolve("lib");
		Files.createDirectories(lib);
		Path target = lib.resolve("spawnproof-" + Version.VERSION + ".jar");
		boolean same = Files.isRegularFile(target) && Files.size(target) == Files.size(source)
			&& !Files.getLastModifiedTime(source).toInstant().isAfter(Files.getLastModifiedTime(target).toInstant());
		if (!same) {
			Path temp = Files.createTempFile(lib, "spawnproof-", ".jar.tmp");
			Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		return target;
	}
}
