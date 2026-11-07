package io.github.gabepurc.spawnproof.fabric;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How a test game runs, from system properties (a Gradle build passes them to its game task):
 *
 * <ul>
 * <li>{@code spawnproof.tests}: the tests to run, by name or tag, comma separated ({@code *} matches anything). Unset: every
 * test that runs by default.
 * <li>{@code spawnproof.skip}: tests to leave out, the same way.
 * <li>{@code spawnproof.clock}: {@code frames} (default: the game runs as fast as it draws) or {@code real}.
 * <li>{@code spawnproof.graphics}: {@code low} (default: the lowest settings) or {@code normal} (for screenshots people see).
 * <li>{@code spawnproof.output}: the results folder; {@code spawnproof/} in the game's folder by default. A test run gives
 * each of its games a folder of its own ({@code SPAWNPROOF_OUTPUT}), which comes first.
 * </ul>
 */
public final class Settings {
	/** A client game test run: Fabric's own switch, set by Loom's client game test runs. Nothing here acts without it. */
	public static final boolean TEST_RUN = System.getProperty("fabric.client.gametest") != null;
	public static final boolean FAST_CLOCK = !choice("spawnproof.clock", "frames", "real").equals("real");
	public static final boolean LOW_GRAPHICS = !choice("spawnproof.graphics", "low", "normal").equals("normal");

	private Settings() {}

	static List<String> tests() {
		return list("spawnproof.tests");
	}

	static List<String> skip() {
		return list("spawnproof.skip");
	}

	static Path output() {
		String dir = System.getenv("SPAWNPROOF_OUTPUT");
		if (dir != null && !dir.isBlank()) return Path.of(dir).toAbsolutePath();
		dir = System.getProperty("spawnproof.output");
		if (dir != null && !dir.isBlank()) return Path.of(dir).toAbsolutePath();
		return FabricLoader.getInstance().getGameDir().resolve("spawnproof").toAbsolutePath();
	}

	private static List<String> list(String property) {
		List<String> out = new ArrayList<>();
		String value = System.getProperty(property, "");
		for (String part : value.split(",")) {
			if (!part.isBlank()) out.add(part.trim().toLowerCase(Locale.ROOT));
		}
		return out;
	}

	private static String choice(String property, String byDefault, String other) {
		String value = System.getProperty(property, byDefault).trim().toLowerCase(Locale.ROOT);
		if (value.isEmpty()) return byDefault;
		if (!value.equals(byDefault) && !value.equals(other)) {
			System.out.println("[spawnproof] " + property + " is " + byDefault + " or " + other + ", not '" + value + "': using " + byDefault);
			return byDefault;
		}
		return value;
	}
}
