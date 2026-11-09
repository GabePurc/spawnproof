package io.github.gabepurc.spawnproof.gradle;

import io.github.gabepurc.spawnproof.Dirs;
import io.github.gabepurc.spawnproof.client.Client;
import io.github.gabepurc.spawnproof.daemon.DaemonConfig;
import io.github.gabepurc.spawnproof.run.KeptRuns;
import org.gradle.api.GradleException;
import org.gradle.api.Task;
import org.gradle.api.logging.Logger;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;
import org.gradle.tooling.events.FinishEvent;
import org.gradle.tooling.events.OperationCompletionListener;
import org.gradle.tooling.events.task.TaskFinishEvent;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds this build's game slots. A slot is let go when its task finishes, passes or fails; whatever is left goes when the
 * build ends, and if the whole Gradle process dies, the daemon notices the connection close.
 */
public abstract class LeaseService implements BuildService<BuildServiceParameters.None>, OperationCompletionListener, AutoCloseable {
	private final Map<String, Client.Lease> leases = new ConcurrentHashMap<>();
	private final Map<String, Keep> toKeep = new ConcurrentHashMap<>();

	/** A test game's results folder, and whose they are, to keep once its task is done if the game wrote any. */
	private record Keep(Path results, Map<String, Object> who, long startedAt) {}
	private final TimeLimit timeLimit = new TimeLimit();
	private volatile boolean warnedUnavailable;
	private volatile boolean warnedNoDisplay;

	/** Waits for a slot; returns the hidden display to use, or null. Never fails the build over Spawnproof itself. */
	String acquire(Task task, Map<String, Object> request, boolean hidden) {
		Logger log = task.getLogger();
		String path = task.getPath();
		try {
			Client.Lease lease = Client.acquire(request, message -> log.lifecycle("Spawnproof: {} is {}", path, message));
			Client.Lease old = leases.put(path, lease);
			if (old != null) old.close();
			if (lease.display != null) {
				log.info("Spawnproof: {} draws on hidden display {} ({})", path, lease.display, lease.displayKind);
			} else if (hidden && lease.note != null && !warnedNoDisplay) {
				warnedNoDisplay = true;
				log.lifecycle("Spawnproof: {}", lease.note);
			}
			return lease.display;
		} catch (InterruptedIOException | ClosedByInterruptException e) {
			throw new GradleException("Cancelled while waiting for a game slot", e);
		} catch (IOException | RuntimeException e) {
			if (Thread.currentThread().isInterrupted()) throw new GradleException("Cancelled while waiting for a game slot", e);
			if (!warnedUnavailable) {
				warnedUnavailable = true;
				log.warn("Spawnproof: couldn't reach the game queue, so games in this build don't wait their turn ({})", e.getMessage());
			}
			return null;
		}
	}

	/** Keeps the results the game writes in {@code results} for the dashboard, once its task is done (KeptRuns). */
	void keepResultsOf(String path, Path results, Map<String, Object> who) {
		toKeep.put(path, new Keep(results, who, System.currentTimeMillis()));
	}

		TimeLimit timeLimit() {
		return timeLimit;
	}

	@Override
	public void onFinish(FinishEvent event) {
		if (event instanceof TaskFinishEvent finished) {
			String path = finished.getDescriptor().getTaskPath();
			timeLimit.stop(path);
			Client.Lease lease = leases.remove(path);
			if (lease != null) lease.close();
			Keep keep = toKeep.remove(path);
			if (keep != null) keep(keep);
		}
	}

	/** Passed or failed, the task's game may have written results; ones left from an earlier run aren't this run's. */
	private static void keep(Keep keep) {
		try {
			Path json = keep.results().resolve("results.json");
			if (!Files.isRegularFile(json) || Files.getLastModifiedTime(json).toMillis() < keep.startedAt()) return;
		} catch (IOException e) {
			return;
		}
		KeptRuns.keep(keep.results(), keep.who(), DaemonConfig.load(Dirs.configFile()).keepRuns());
	}

	@Override
	public void close() {
		timeLimit.close();
		leases.values().forEach(Client.Lease::close);
		leases.clear();
	}
}
