package io.github.gabepurc.spawnproof.gradle;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * {@code spawnproof { }} in settings.gradle (every project) or a build script (that project). Task names may use * and may be
 * paths (":26.3:runSmoke"). Most builds need none of it: client game tests, production client runs and smoke tests queue by
 * themselves, and runClient and play* count as playing.
 */
public abstract class SpawnproofExtension {
	/** Off to run games directly. Defaults to on, except on CI (the CI variable) or with -Pspawnproof.off or SPAWNPROOF=off. */
	public abstract Property<Boolean> getEnabled();

	/** Whether test games draw on the hidden display (default true; -Pspawnproof.visible shows them for one build). */
	public abstract Property<Boolean> getHidden();

	public abstract ListProperty<String> getTestTasks();

	public abstract ListProperty<String> getPlayTasks();

	public abstract ListProperty<String> getExclusiveTasks();

	public abstract ListProperty<String> getIgnoredTasks();

	/** Tasks that are test games: they wait for a slot and draw on the hidden display. */
	public void tests(String... tasks) {
		getTestTasks().addAll(tasks);
	}

	/** Tasks that are you playing: they never wait, and hold new test games back while they run. */
	public void play(String... tasks) {
		getPlayTasks().addAll(tasks);
	}

	/** Test games that need the machine to themselves (performance runs, recordings). */
	public void exclusive(String... tasks) {
		getExclusiveTasks().addAll(tasks);
	}

	/** Tasks to leave alone. */
	public void ignore(String... tasks) {
		getIgnoredTasks().addAll(tasks);
	}
}
