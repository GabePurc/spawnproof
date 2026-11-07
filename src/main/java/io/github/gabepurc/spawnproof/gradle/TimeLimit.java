package io.github.gabepurc.spawnproof.gradle;

import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.provider.Provider;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A game task's Gradle timeout, counted from when its game starts instead of from when the task starts (which includes
 * waiting in line). Like Gradle's own, it interrupts the task's thread, which stops the game.
 */
final class TimeLimit implements AutoCloseable {
	private record Running(Object token, ScheduledFuture<?> future) {}

	private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "spawnproof-time-limit");
		t.setDaemon(true);
		return t;
	});
	private final Map<String, Running> running = new ConcurrentHashMap<>();

	void start(Task task, Duration limit) {
		String path = task.getPath();
		Thread thread = Thread.currentThread();
		Object token = new Object();
		Running pending = new Running(token, null);
		Running old = running.put(path, pending);
		if (old != null && old.future() != null) old.future().cancel(false);
		ScheduledFuture<?> future = clock.schedule(() -> {
			Running now = running.get(path);
			// Only this run of this task, still going: never a thread that has moved on to other work.
			if (now == null || now.token() != token || !running.remove(path, now)) return;
			task.getLogger().error("Spawnproof: {} ran past its timeout of {} (counted from when its game started), so it's stopped", path,
				limit);
			thread.interrupt();
		}, limit.toMillis(), TimeUnit.MILLISECONDS);
		running.replace(path, pending, new Running(token, future));
	}

	void stop(String path) {
		Running r = running.remove(path);
		if (r != null && r.future() != null) r.future().cancel(false);
	}

	@Override
	public void close() {
		running.values().forEach(r -> {
			if (r.future() != null) r.future().cancel(false);
		});
		running.clear();
		clock.shutdownNow();
	}

	/** The game finished: its time limit is done with. (A failed task is stopped by the service when it hears.) */
	static final class Stop implements Action<Task> {
		private final Provider<LeaseService> service;

		Stop(Provider<LeaseService> service) {
			this.service = service;
		}

		@Override
		public void execute(Task task) {
			service.get().timeLimit().stop(task.getPath());
		}
	}
}
