package io.github.gabepurc.spawnproof.gradle;

import org.gradle.api.provider.ListProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Which tasks are games, and how they queue: the task's own settings, then the build's lists, then the names. */
final class Rules {
	static final String TEST = "test";
	static final String PLAY = "play";
	static final String NONE = "none";

	private Rules() {}

	static String role(String name, String path, boolean productionRun, GameSettings task, SpawnproofExtension project, SpawnproofExtension shared) {
		if (task.getRole().isPresent()) {
			String role = task.getRole().get().toLowerCase(Locale.ROOT);
			return role.equals(TEST) || role.equals(PLAY) ? role : NONE;
		}
		for (SpawnproofExtension e : extensions(project, shared)) {
			if (matches(e.getIgnoredTasks(), name, path)) return NONE;
			if (matches(e.getPlayTasks(), name, path)) return PLAY;
			if (matches(e.getTestTasks(), name, path) || matches(e.getExclusiveTasks(), name, path)) return TEST;
		}
		return byName(name, productionRun);
	}

	/**
	 * Loom's and ModDevGradle's names. Playing: runClient (also runFabricClient and the like, in multi-loader builds) and
	 * play*. Tests: production client runs, and any other run with client or smoke in its name (runClientGameTest,
	 * runAuditClient, runSmoke).
	 */
	static String byName(String name, boolean productionRun) {
		String lower = name.toLowerCase(Locale.ROOT);
		if (name.startsWith("play") && (name.length() == 4 || Character.isUpperCase(name.charAt(4)))) return PLAY;
		if (lower.matches("run(fabric|forge|neoforge|quilt)?client")) return PLAY;
		if (productionRun || lower.contains("client") || lower.contains("smoke")) return TEST;
		return NONE;
	}

	static boolean exclusive(String name, String path, GameSettings task, SpawnproofExtension project, SpawnproofExtension shared, boolean forced) {
		if (task.getExclusive().isPresent()) return task.getExclusive().get();
		for (SpawnproofExtension e : extensions(project, shared)) if (matches(e.getExclusiveTasks(), name, path)) return true;
		return forced;
	}

	static boolean hidden(GameSettings task, SpawnproofExtension project, SpawnproofExtension shared, boolean visibleForced) {
		if (visibleForced) return false;
		if (task.getHidden().isPresent()) return task.getHidden().get();
		for (SpawnproofExtension e : extensions(project, shared)) if (e.getHidden().isPresent()) return e.getHidden().get();
		return true;
	}

	static Boolean enabled(SpawnproofExtension project, SpawnproofExtension shared) {
		for (SpawnproofExtension e : extensions(project, shared)) if (e.getEnabled().isPresent()) return e.getEnabled().get();
		return null;
	}

	private static List<SpawnproofExtension> extensions(SpawnproofExtension project, SpawnproofExtension shared) {
		List<SpawnproofExtension> out = new ArrayList<>(2);
		if (project != null) out.add(project);
		if (shared != null && shared != project) out.add(shared);
		return out;
	}

	static boolean matches(ListProperty<String> patterns, String name, String path) {
		for (String pattern : patterns.getOrElse(List.of())) {
			String target = pattern.startsWith(":") ? path : name;
			if (glob(pattern).matcher(target).matches()) return true;
		}
		return false;
	}

	static Pattern glob(String pattern) {
		StringBuilder regex = new StringBuilder();
		for (String part : pattern.split("\\*", -1)) {
			if (regex.length() > 0) regex.append(".*");
			regex.append(Pattern.quote(part));
		}
		return Pattern.compile(regex.toString());
	}
}
