package io.github.gabepurc.spawnproof.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.util.Map;
import java.util.TreeMap;

/** spawnproofTasks: which of a project's tasks queue, and how, to check the rules without starting a game. */
@DisableCachingByDefault(because = "It only prints")
public abstract class SpawnproofTasksTask extends DefaultTask {
	@Input
	public abstract MapProperty<String, String> getGames();

	public SpawnproofTasksTask() {
		setGroup("spawnproof");
		setDescription("Lists this project's game tasks and how Spawnproof queues them.");
	}

	@TaskAction
	public void list() {
		Map<String, String> games = new TreeMap<>(getGames().get());
		games.values().removeIf(v -> v.startsWith(Rules.NONE));
		if (games.isEmpty()) {
			getLogger().quiet("No task in this project queues.");
			return;
		}
		int width = games.keySet().stream().mapToInt(String::length).max().orElse(0);
		for (Map.Entry<String, String> e : games.entrySet()) {
			getLogger().quiet("  {}  {}", String.format("%-" + width + "s", e.getKey()), e.getValue());
		}
	}
}
