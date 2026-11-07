package io.github.gabepurc.spawnproof.run;

import io.github.gabepurc.spawnproof.client.Client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.Writer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One game of a test run, on a thread of its own: it waits for a slot in the machine's queue, starts the game in a folder of
 * its own, and waits for it to close. Its fields are the run's to read and change, under the run's lock.
 */
final class Game implements Runnable {
	enum State { WAITING, RUNNING, ENDED, CANCELLED }

	final int number;
	final Path dir;
	private final TestRun run;
	final Thread thread;

	State state = State.WAITING;
	long startedAt;
	long endedAt;
	Integer exitCode;
	/** The test it's running, handed out and not yet done. */
	String current;
	final List<String> ran = new ArrayList<>();
	/** It said hello: it got as far as Spawnproof's runner. */
	boolean hello;
	/** It was told there's nothing left: it stops as it should. */
	boolean toldToStop;
	/** Why it can't take more tests (a test left a world open), as it said. */
	String leaving;
	/** Why the run stopped it, when it did. */
	String stoppedBecause;
	private boolean connected;
	private boolean disconnected;
	private volatile Process process;
	private final Deque<String> lastLines = new ArrayDeque<>();

	Game(TestRun run, int number, Path dir) {
		this.run = run;
		this.number = number;
		this.dir = dir;
		this.thread = new Thread(this, "spawnproof-game-" + number);
		thread.setDaemon(true);
	}

	Path output() {
		return dir.resolve("spawnproof");
	}

	@Override
	public void run() {
		Client.Lease lease = null;
		try {
			lease = run.queued() ? acquire() : null;
			if (!run.starting(this)) return;
			run.copyRunFolder(dir);
			ProcessBuilder pb = new ProcessBuilder(run.command()).directory(dir.toFile()).redirectErrorStream(true);
			Map<String, String> env = pb.environment();
			env.remove("SPAWNPROOF_RUN");
			env.put("SPAWNPROOF_DISPATCH", run.socket().toString());
			env.put("SPAWNPROOF_GAME", Integer.toString(number));
			env.put("SPAWNPROOF_OUTPUT", output().toString());
			String display = lease != null && lease.display != null ? lease.display : run.outerDisplay();
			if (run.hidden() && display != null) {
				env.put("DISPLAY", display);
				env.remove("WAYLAND_DISPLAY");
				env.put("XDG_SESSION_TYPE", "x11");
			} else if (run.hidden() && lease != null && lease.note != null) {
				run.noteNoDisplay(lease.note);
			}
			Process p;
			synchronized (run) {
				// (Started under the run's lock, so the run's shutdown hook can't miss it.)
				p = pb.start();
				process = p;
				startedAt = System.currentTimeMillis();
			}
			p.getOutputStream().close();
			Thread pump = new Thread(() -> pump(p), "spawnproof-game-" + number + "-output");
			pump.setDaemon(true);
			pump.start();
			run.say("game " + number + " started, in " + run.relative(dir));
			long limit = run.timeLimitMs();
			if (limit > 0 && !p.waitFor(limit, TimeUnit.MILLISECONDS)) {
				String why = "ran past the task's timeout of " + MergedResults.duration(limit) + " (counted from when it started), so it was stopped";
				synchronized (run) {
					stoppedBecause = why;
				}
				run.say("game " + number + " " + why);
				stop(p);
			}
			int code = p.waitFor();
			pump.join(5_000);
			awaitDisconnect();
			synchronized (run) {
				exitCode = code;
			}
		} catch (InterruptedException | InterruptedIOException | ClosedByInterruptException e) {
			// Cancelled: its slot came too late to be needed, or the run is stopping.
		} catch (IOException e) {
			synchronized (run) {
				if (stoppedBecause == null) stoppedBecause = "couldn't start: " + e.getMessage();
			}
			run.say("game " + number + " couldn't start: " + e.getMessage());
		} finally {
			if (lease != null) lease.close();
			Thread.interrupted();
			run.ended(this);
		}
	}

	/** Waits for a slot. Null if the queue can't be reached: the game then runs anyway, as games do without Spawnproof. */
	private Client.Lease acquire() throws IOException {
		try {
			return Client.acquire(run.leaseRequest(this), message -> run.say("game " + number + " is " + message));
		} catch (InterruptedIOException | ClosedByInterruptException e) {
			throw e;
		} catch (IOException | RuntimeException e) {
			if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("cancelled");
			run.noteNoQueue(e.getMessage());
			return null;
		}
	}

	/** The game's console: all of it to output.log in its folder, Spawnproof's own lines to the build's output too. */
	private void pump(Process p) {
		try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
			Writer log = Files.newBufferedWriter(dir.resolve("output.log"), StandardCharsets.UTF_8)) {
			String line;
			while ((line = in.readLine()) != null) {
				log.write(line);
				log.write('\n');
				int at = line.indexOf("[spawnproof] ");
				if (at >= 0) run.echo("[spawnproof game " + number + "] " + line.substring(at + 13));
				synchronized (lastLines) {
					lastLines.addLast(line);
					if (lastLines.size() > 30) lastLines.removeFirst();
				}
			}
			log.flush();
		} catch (IOException ignored) {
			// The game's gone.
		}
	}

	/** The end of its console, to show when it stopped before it should have. */
	List<String> lastLines() {
		synchronized (lastLines) {
			return new ArrayList<>(lastLines);
		}
	}

	/** Stops the game and anything it started (a dedicated server for a test), politely and then not. */
	void stop() {
		Process p;
		synchronized (run) {
			p = process;
		}
		if (p != null) stop(p);
	}

	private static void stop(Process p) {
		p.descendants().forEach(ProcessHandle::destroy);
		p.destroy();
		try {
			if (!p.waitFor(10, TimeUnit.SECONDS)) {
				p.descendants().forEach(ProcessHandle::destroyForcibly);
				p.destroyForcibly();
			}
		} catch (InterruptedException e) {
			p.destroyForcibly();
			Thread.currentThread().interrupt();
		}
	}

	boolean alive() {
		Process p = process;
		return p != null && p.isAlive();
	}

	void connected() {
		synchronized (run) {
			connected = true;
		}
	}

	void disconnected() {
		synchronized (run) {
			disconnected = true;
			run.notifyAll();
		}
	}

	/**
	 * The game is gone, but what it said last may still be on its way: its connection closes once that's read. (A game that
	 * never connected has nothing to say.)
	 */
	private void awaitDisconnect() throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5_000;
		synchronized (run) {
			while (connected && !disconnected && System.currentTimeMillis() < deadline) run.wait(200);
		}
	}
}
