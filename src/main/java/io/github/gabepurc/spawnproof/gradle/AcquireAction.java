package io.github.gabepurc.spawnproof.gradle;

import io.github.gabepurc.spawnproof.Git;
import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Runs last before the game starts: waits for its slot, then points it at the hidden display. */
final class AcquireAction implements Action<Task> {
	private final Provider<LeaseService> service;
	private final Provider<String> role;
	private final Provider<Boolean> exclusive;
	private final Provider<Boolean> hidden;
	private final Provider<Duration> limit;
	private final String project;
	private final String dir;

	AcquireAction(Provider<LeaseService> service, Provider<String> role, Provider<Boolean> exclusive, Provider<Boolean> hidden,
		Provider<Duration> limit, String project, String dir) {
		this.service = service;
		this.role = role;
		this.exclusive = exclusive;
		this.hidden = hidden;
		this.limit = limit;
		this.project = project;
		this.dir = dir;
	}

	@Override
	public void execute(Task task) {
		String role = this.role.get();
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
