package io.github.gabepurc.spawnproof.gradle;

import io.github.gabepurc.spawnproof.Git;
import io.github.gabepurc.spawnproof.Json;
import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs last before the game starts: waits for its slot, then points it at the hidden display. A game task that's a test
 * run instead (TestRunLauncher) gets the run's settings: the run waits for a slot for each of its games itself.
 */
final class AcquireAction implements Action<Task> {
	private final Provider<LeaseService> service;
	private final Provider<String> role;
	private final Provider<Boolean> exclusive;
	private final Provider<Boolean> hidden;
	private final Provider<Duration> limit;
	private final String project;
	private final String dir;
	private final Provider<Boolean> testRun;
	private final Provider<String> games;
	private final Provider<Boolean> onlyFailures;

	AcquireAction(Provider<LeaseService> service, Provider<String> role, Provider<Boolean> exclusive, Provider<Boolean> hidden,
		Provider<Duration> limit, String project, String dir, Provider<Boolean> testRun, Provider<String> games, Provider<Boolean> onlyFailures) {
		this.service = service;
		this.role = role;
		this.exclusive = exclusive;
		this.hidden = hidden;
		this.limit = limit;
		this.project = project;
		this.dir = dir;
		this.testRun = testRun;
		this.games = games;
		this.onlyFailures = onlyFailures;
	}

	@Override
	public void execute(Task task) {
		String role = this.role.get();
		if (testRun.get() && task instanceof JavaExec exec) {
			if (exec.getJavaLauncher().getOrNull() instanceof TestRunLauncher) {
				startTestRun(task, exec, role);
				return;
			}
			task.getLogger().warn("Spawnproof: {} runs its tests in one game: {}", task.getPath(), TestRunLauncher.whyNot());
		}
		if (Rules.NONE.equals(role)) return;
		boolean hide = hidden.get() && Rules.TEST.equals(role);
		if (System.getenv("SPAWNPROOF_LEASE") != null) {
			// Inside `spawnproof run`, which already holds a slot (and the display) for the whole command.
			String display = System.getenv("SPAWNPROOF_DISPLAY");
			if (hide && display != null) useDisplay(task, display);
			startClock(task);
			return;
		}
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("project", project);
		request.put("task", task.getPath());
		request.put("dir", dir);
		request.put("branch", Git.branch(Path.of(dir)));
		request.put("label", System.getenv("SPAWNPROOF_LABEL"));
		request.put("role", role);
		request.put("exclusive", exclusive.get());
		request.put("hidden", hide);
		request.put("pid", ProcessHandle.current().pid());
		String display = service.get().acquire(task, request, hide);
		if (hide && display != null) useDisplay(task, display);
		startClock(task);
	}

	/** The test run's settings, for TestRunLauncher's script to pass on (io.github.gabepurc.spawnproof.run.TestRun). */
	private void startTestRun(Task task, JavaExec exec, String role) {
		boolean queued = Rules.TEST.equals(role);
		boolean inRun = System.getenv("SPAWNPROOF_LEASE") != null;
		Map<String, Object> settings = new LinkedHashMap<>();
		settings.put("games", games.getOrElse(""));
		settings.put("onlyFailures", onlyFailures.get());
		// Inside `spawnproof run`, which already holds a slot and the display for the whole command.
		settings.put("queue", queued && !inRun);
		settings.put("hidden", queued && hidden.get());
		settings.put("display", inRun ? System.getenv("SPAWNPROOF_DISPLAY") : null);
		settings.put("exclusive", exclusive.get());
		settings.put("timeLimitMs", limit.isPresent() ? limit.get().toMillis() : 0);
		settings.put("project", project);
		settings.put("task", task.getPath());
		settings.put("dir", dir);
		settings.put("branch", Git.branch(Path.of(dir)));
		settings.put("label", System.getenv("SPAWNPROOF_LABEL"));
		exec.environment("SPAWNPROOF_RUN", Json.write(settings));
	}

	/** The task's own timeout, taken over by the plugin, starts now that the game does. */
	private void startClock(Task task) {
		Duration d = limit.getOrNull();
		if (d != null) service.get().timeLimit().start(task, d);
	}

	/** Production runs can't take an environment; HiddenLauncher's wrapper reads the display for them. */
	private static void useDisplay(Task task, String display) {
		if (task instanceof JavaExec exec) {
			exec.environment("DISPLAY", display);
			exec.getEnvironment().remove("WAYLAND_DISPLAY");
			exec.environment("XDG_SESSION_TYPE", "x11");
		}
	}
}
