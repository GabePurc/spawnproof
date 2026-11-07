package io.github.gabepurc.spawnproof.gradle;

import org.gradle.api.provider.Property;

/**
 * {@code spawnproof { }} on one game task, for what the names can't say:
 * {@code tasks.named("runClientGameTest") { spawnproof.exclusive = clientTests.contains("perf") }}.
 */
public abstract class GameSettings {
	/** test, play or none. */
	public abstract Property<String> getRole();

	public abstract Property<Boolean> getExclusive();

	public abstract Property<Boolean> getHidden();
}
