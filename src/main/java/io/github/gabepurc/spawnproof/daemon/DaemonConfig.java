package io.github.gabepurc.spawnproof.daemon;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** The machine's settings (config.properties), changed with `spawnproof config <key> <value>`. */
public final class DaemonConfig {
	/** How many test games run at once. */
	volatile int slots = 2;
	/** auto (weston, else Xvfb), weston, xvfb, or none (games show on your desktop). */
	volatile String display = "auto";
	volatile int width = 1920;
	volatile int height = 1080;
	/** While you play (runClient, play* tasks), new test games wait. */
	volatile boolean holdWhilePlaying = true;
	/** The daemon exits after this long with nothing to do. */
	volatile int idleMinutes = 60;
	/** The hidden display closes after this long with no game on it. */
	volatile int displayIdleMinutes = 15;
	/** How often a watched repo's pull requests are looked at for new commits. */
	volatile int watchSeconds = 60;
	/** How many past test runs of each task in each checkout keep their results, for the dashboard. */
	volatile int keepRuns = io.github.gabepurc.spawnproof.run.KeptRuns.DEFAULT_KEEP;
	/** Where GitHub's API is: another address for GitHub Enterprise, or for the tests' stand-in. */
	volatile String githubApi = "https://api.github.com";

	public static DaemonConfig load(Path file) {
		DaemonConfig c = new DaemonConfig();
		if (file == null || !Files.isRegularFile(file)) return c;
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			p.load(r);
		} catch (IOException e) {
			return c;
		}
		for (String key : p.stringPropertyNames()) c.set(key, p.getProperty(key).trim());
		return c;
	}

	public synchronized void save(Path file) throws IOException {
		Files.createDirectories(file.getParent());
		try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			w.write("# Spawnproof's settings for this machine. Change them with: spawnproof config <key> <value>\n");
			for (Map.Entry<String, Object> e : toMap().entrySet()) w.write(e.getKey() + "=" + e.getValue() + "\n");
		}
	}

	/** Changes one setting; returns what was wrong with it, or null. */
	public synchronized String set(String key, String value) {
		if (key == null || value == null) return "give a key and a value";
		try {
			switch (key) {
				case "slots" -> slots = range(value, 1, 64);
				case "display" -> {
					if (!value.matches("auto|weston|xvfb|none")) return "display is one of auto, weston, xvfb, none";
					display = value;
				}
				case "resolution" -> {
					String[] wh = value.toLowerCase().split("x");
					if (wh.length != 2) return "resolution looks like 1920x1080";
					width = range(wh[0], 320, 7680);
					height = range(wh[1], 240, 4320);
				}
				case "holdWhilePlaying" -> holdWhilePlaying = bool(value);
				case "idleMinutes" -> idleMinutes = range(value, 1, 7 * 24 * 60);
				case "displayIdleMinutes" -> displayIdleMinutes = range(value, 1, 7 * 24 * 60);
				case "watchSeconds" -> watchSeconds = range(value, 1, 3600);
				case "keepRuns" -> keepRuns = range(value, 1, 1000);
				case "githubApi" -> {
					if (!value.matches("https?://\\S+")) return "githubApi is an address, like https://api.github.com";
					githubApi = value.replaceAll("/+$", "");
				}
				default -> {
					return "unknown setting '" + key + "' (slots, display, resolution, holdWhilePlaying, idleMinutes, displayIdleMinutes, watchSeconds, "
						+ "keepRuns, githubApi)";
				}
			}
		} catch (IllegalArgumentException e) {
			return key + ": " + e.getMessage();
		}
		return null;
	}

	private static int range(String value, int min, int max) {
		int n;
		try {
			n = Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("'" + value + "' isn't a whole number");
		}
		if (n < min || n > max) throw new IllegalArgumentException(n + " is outside " + min + " to " + max);
		return n;
	}

	private static boolean bool(String value) {
		if (value.equalsIgnoreCase("true") || value.equals("on")) return true;
		if (value.equalsIgnoreCase("false") || value.equals("off")) return false;
		throw new IllegalArgumentException("'" + value + "' isn't true or false");
	}

	public synchronized Map<String, Object> toMap() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("slots", slots);
		m.put("display", display);
		m.put("resolution", width + "x" + height);
		m.put("holdWhilePlaying", holdWhilePlaying);
		m.put("idleMinutes", idleMinutes);
		m.put("displayIdleMinutes", displayIdleMinutes);
		m.put("watchSeconds", watchSeconds);
		m.put("keepRuns", keepRuns);
		m.put("githubApi", githubApi);
		return m;
	}

	public int watchSeconds() {
		return watchSeconds;
	}

	public int keepRuns() {
		return keepRuns;
	}

	public String githubApi() {
		return githubApi;
	}
}
