package io.github.gabepurc.spawnproof.daemon;

import io.github.gabepurc.spawnproof.Dirs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The hidden display test games draw on, so they never open a window on your desktop. Started when a game first needs it,
 * shared by every game, closed once no game has used it for a while.
 *
 * <p>weston: a headless Wayland compositor whose Xwayland gives games an X display; it can draw on a GPU. Xvfb: an X server
 * in memory, drawing in software (slower, but everywhere).
 */
final class Displays {
	record Info(String name, String kind) {}

	private static final Path X_SOCKETS = Path.of("/tmp/.X11-unix");
	private final DaemonConfig config;
	private final Path stateDir;
	private Process process;
	private String name;
	private String kind;
	private String renderer;
	private String note = "starts with the first hidden game";
	private long lastUse;

	Displays(DaemonConfig config, Path stateDir) {
		this.config = config;
		this.stateDir = stateDir;
	}

	/** The display to use, starting it if needed; null when there's none (the game shows on the desktop, and note says why). */
	synchronized Info ensure() {
		lastUse = System.currentTimeMillis();
		if (process != null && process.isAlive() && name != null && Files.exists(xSocket(name))) return new Info(name, kind);
		stopLocked();
		if (!Dirs.isLinux()) {
			note = "hidden displays work on Linux only";
			return null;
		}
		List<String> order = switch (config.display) {
			case "auto" -> List.of("weston", "xvfb");
			case "weston" -> List.of("weston");
			case "xvfb" -> List.of("xvfb");
			default -> List.of();
		};
		if (order.isEmpty()) {
			note = "off: games show on the desktop (spawnproof config display auto)";
			return null;
		}
		List<String> problems = new ArrayList<>();
		for (String option : order) {
			String missing = missingTools(option);
			if (missing != null) {
				problems.add(option + ": " + missing);
				continue;
			}
			try {
				if (option.equals("weston")) startWeston();
				else startXvfb();
				Files.createDirectories(Dirs.displayFile().getParent());
				Files.writeString(Dirs.displayFile(), name);
				renderer = probeRenderer(name);
				note = null;
				Daemon.log("hidden display " + name + " up (" + kind + (renderer != null ? ", " + renderer : "") + ")");
				return new Info(name, kind);
			} catch (IOException e) {
				problems.add(option + ": " + e.getMessage());
				stopLocked();
			}
		}
		note = "no hidden display, so games show on the desktop: " + String.join("; ", problems);
		Daemon.log(note);
		return null;
	}

	synchronized void stopIfIdle(boolean inUse, long idleMs) {
		long now = System.currentTimeMillis();
		if (inUse) {
			lastUse = now;
		} else if (process != null && now - lastUse > idleMs) {
			Daemon.log("closing hidden display " + name + ": no game has used it for a while");
			stopLocked();
			note = "closed while idle; starts again with the next hidden game";
		}
	}

	/** Called on a settings change: an idle display closes now so the next game gets the new kind or size. */
	synchronized void restartWhenIdle(boolean inUse) {
		if (!inUse && process != null) stopLocked();
	}

	synchronized void stop() {
		stopLocked();
	}

	synchronized Map<String, Object> describe() {
		Map<String, Object> m = new LinkedHashMap<>();
		boolean up = process != null && process.isAlive();
		m.put("mode", config.display);
		m.put("up", up);
		if (up) {
			m.put("name", name);
			m.put("kind", kind);
			if (renderer != null) m.put("renderer", renderer);
		}
		if (note != null) m.put("note", note);
		return m;
	}

	/** A display left running by a daemon that died: close it, it would only hold the GPU. */
	void killLeftovers() {
		Path pidFile = stateDir.resolve("display.pid");
		try {
			if (Files.isRegularFile(pidFile)) {
				long pid = Long.parseLong(Files.readString(pidFile).trim());
				ProcessHandle.of(pid).ifPresent(p -> {
					String command = p.info().command().orElse("");
					if (command.contains("weston") || command.contains("Xvfb")) {
						Daemon.log("closing a hidden display left by an earlier daemon (pid " + pid + ")");
						p.descendants().forEach(ProcessHandle::destroy);
						p.destroy();
					}
				});
			}
			Files.deleteIfExists(pidFile);
			Files.deleteIfExists(Dirs.displayFile());
		} catch (IOException | RuntimeException ignored) {
			// Nothing to clean.
		}
	}

	private void stopLocked() {
		try {
			Files.deleteIfExists(Dirs.displayFile());
			Files.deleteIfExists(stateDir.resolve("display.pid"));
		} catch (IOException ignored) {
			// Gone already.
		}
		if (process != null) {
			Process p = process;
			p.descendants().forEach(ProcessHandle::destroy);
			p.destroy();
			try {
				if (!p.waitFor(5, TimeUnit.SECONDS)) {
					p.descendants().forEach(ProcessHandle::destroyForcibly);
					p.destroyForcibly();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		process = null;
		name = null;
		kind = null;
		renderer = null;
	}

	private void startXvfb() throws IOException {
		int n = freeDisplayNumber();
		Process p = launch(List.of(find("Xvfb"), ":" + n, "-screen", "0", config.width + "x" + config.height + "x24", "-nolisten", "tcp",
			"-noreset"), "xvfb.log");
		waitForSocket(p, n, "xvfb.log");
		process = p;
		name = ":" + n;
		kind = "Xvfb, software";
	}

	private void startWeston() throws IOException {
		Set<Integer> before = xDisplays();
		Path ini = stateDir.resolve("weston.ini");
		Files.writeString(ini, String.join("\n",
			"# Written by Spawnproof for its hidden display.",
			"[core]",
			"idle-time=0",
			"[shell]",
			"panel-position=none",
			"locking=false",
			"background-color=0xff1e1e1e",
			""));
		Process p = launch(List.of(find("weston"), "--backend=headless", "--renderer=gl", "--width=" + config.width,
			"--height=" + config.height, "--socket=spawnproof-" + ProcessHandle.current().pid(), "--xwayland", "--idle-time=0",
			"--config=" + ini), "weston.log");
		// Weston opens an X display for Xwayland; find it in the log, or as the socket that wasn't there before.
		long deadline = System.currentTimeMillis() + 20_000;
		Pattern listening = Pattern.compile("(?i)(?:xserver|xwayland)[^\\n]*?display :(\\d+)");
		while (System.currentTimeMillis() < deadline) {
			if (!p.isAlive()) throw new IOException("weston stopped: " + tail("weston.log"));
			Integer n = null;
			Matcher m = listening.matcher(read("weston.log"));
			if (m.find()) n = Integer.parseInt(m.group(1));
			if (n == null) {
				Set<Integer> now = xDisplays();
				now.removeAll(before);
				if (now.size() == 1) n = now.iterator().next();
			}
			if (n != null && Files.exists(xSocket(":" + n))) {
				process = p;
				name = ":" + n;
				kind = "weston";
				return;
			}
			sleep(100);
		}
		p.destroy();
		throw new IOException("weston opened no X display in 20 s: " + tail("weston.log"));
	}

	private Process launch(List<String> command, String logName) throws IOException {
		Files.createDirectories(stateDir);
		ProcessBuilder pb = new ProcessBuilder(command);
		Map<String, String> env = pb.environment();
		// It must not open inside your desktop session.
		env.remove("WAYLAND_DISPLAY");
		env.remove("WAYLAND_SOCKET");
		env.remove("DISPLAY");
		pb.redirectErrorStream(true);
		pb.redirectOutput(stateDir.resolve(logName).toFile());
		pb.redirectInput(ProcessBuilder.Redirect.PIPE);
		pb.directory(stateDir.toFile());
		Process p = pb.start();
		p.getOutputStream().close();
		Files.writeString(stateDir.resolve("display.pid"), Long.toString(p.pid()));
		return p;
	}

	private void waitForSocket(Process p, int n, String logName) throws IOException {
		long deadline = System.currentTimeMillis() + 10_000;
		while (System.currentTimeMillis() < deadline) {
			if (!p.isAlive()) throw new IOException("stopped: " + tail(logName));
			if (Files.exists(xSocket(":" + n))) return;
			sleep(50);
		}
		p.destroy();
		throw new IOException("display :" + n + " didn't open in 10 s: " + tail(logName));
	}

	/** What draws the games ("AMD Radeon Graphics (radeonsi, ...)"), if glxinfo is installed to ask. */
	private String probeRenderer(String display) {
		String glxinfo = findOrNull("glxinfo");
		if (glxinfo == null) return null;
		try {
			ProcessBuilder pb = new ProcessBuilder(glxinfo, "-B").redirectErrorStream(true);
			pb.environment().put("DISPLAY", display);
			pb.environment().remove("WAYLAND_DISPLAY");
			Process p = pb.start();
			p.getOutputStream().close();
			byte[] out = p.getInputStream().readAllBytes();
			if (!p.waitFor(20, TimeUnit.SECONDS)) p.destroyForcibly();
			for (String line : new String(out, StandardCharsets.UTF_8).split("\n")) {
				int at = line.indexOf("OpenGL renderer string:");
				if (at >= 0) return line.substring(at + "OpenGL renderer string:".length()).trim();
			}
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) Thread.currentThread().interrupt();
		}
		return null;
	}

	private static String missingTools(String option) {
		if (option.equals("weston")) {
			if (findOrNull("weston") == null) return "weston isn't installed";
			if (findOrNull("Xwayland") == null) return "Xwayland isn't installed";
			return null;
		}
		return findOrNull("Xvfb") == null ? "Xvfb isn't installed" : null;
	}

	private static String find(String program) throws IOException {
		String path = findOrNull(program);
		if (path == null) throw new IOException(program + " isn't installed");
		return path;
	}

	static String findOrNull(String program) {
		String path = System.getenv("PATH");
		if (path == null) path = "/usr/local/bin:/usr/bin:/bin";
		for (String dir : path.split(File.pathSeparator)) {
			if (dir.isEmpty()) continue;
			Path candidate = Path.of(dir, program);
			if (Files.isExecutable(candidate)) return candidate.toString();
		}
		return null;
	}

	private static Path xSocket(String display) {
		return X_SOCKETS.resolve("X" + display.substring(1));
	}

	private static Set<Integer> xDisplays() {
		Set<Integer> out = new HashSet<>();
		File[] files = X_SOCKETS.toFile().listFiles();
		if (files == null) return out;
		for (File f : files) {
			if (f.getName().matches("X\\d+")) out.add(Integer.parseInt(f.getName().substring(1)));
		}
		return out;
	}

	private static int freeDisplayNumber() throws IOException {
		for (int n = 90; n < 400; n++) {
			if (!Files.exists(X_SOCKETS.resolve("X" + n)) && !Files.exists(Path.of("/tmp/.X" + n + "-lock"))) return n;
		}
		throw new IOException("no free X display number between :90 and :399");
	}

	private String read(String logName) {
		try {
			return Files.readString(stateDir.resolve(logName), StandardCharsets.UTF_8);
		} catch (IOException e) {
			return "";
		}
	}

	private String tail(String logName) {
		String text = read(logName).trim();
		if (text.isEmpty()) return "(no output; see " + stateDir.resolve(logName) + ")";
		String[] lines = text.split("\n");
		return String.join(" | ", java.util.Arrays.copyOfRange(lines, Math.max(0, lines.length - 3), lines.length));
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
