package io.github.gabepurc.spawnproof.client;

import io.github.gabepurc.spawnproof.Dirs;
import io.github.gabepurc.spawnproof.Version;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
	 * Where the daemon's classes are: a copy of this jar in the state folder, named by its contents, or the classes folder
	 * itself when running from one (tests, an included build).
	 */
	public static synchronized Path classpath() throws IOException {
		Path source;
		try {
			source = Path.of(DaemonStarter.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		} catch (URISyntaxException | RuntimeException e) {
			throw new IOException("can't tell where Spawnproof's jar is", e);
		}
		if (Files.isDirectory(source)) return source;
		return installedJar(source);
	}

	/**
	 * Which build of Spawnproof this is: the copy's name (spawnproof-0.1.0-1a2b3c4d.jar), or "classes" from a folder. A daemon
	 * of another build is replaced once it's idle, so a rebuilt jar takes over even with the same version.
	 */
	public static String buildId() {
		try {
			return classpath().getFileName().toString();
		} catch (IOException e) {
			return "unknown";
		}
	}

	/**
	 * The copy is named by a hash of its contents: Gradle keeps the classes of an init script's jar loaded by path, so an
	 * updated jar must get a new path for Gradle to load it.
	 */
	static Path installedJar(Path source) throws IOException {
		Path lib = Dirs.state().resolve("lib");
		Files.createDirectories(lib);
		String hash;
		try {
			hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))).substring(0, 8);
		} catch (NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
		Path target = lib.resolve("spawnproof-" + Version.VERSION + "-" + hash + ".jar");
		if (!Files.isRegularFile(target)) {
			Path temp = Files.createTempFile(lib, "spawnproof-", ".jar.tmp");
			Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		return target;
	}
}
