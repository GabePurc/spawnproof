package io.github.gabepurc.spawnproof.daemon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Who runs next. Strictly first come, first served: nobody overtakes the head of the line, so a game that needs the machine
 * to itself can't be starved by smaller ones slipping past. Listeners are called after the lock is let go.
 */
public final class Scheduler {
	private final LongSupplier clock;
	private final History history;
	private final List<Lease> waiting = new ArrayList<>();
	private final List<Lease> running = new ArrayList<>();
	private final List<Lease> playing = new ArrayList<>();
	/** Hands out ids when there's no history to go on from (tests). */
	private long nextId = 1;
	private int slots;
	private boolean holdWhilePlaying;
	private boolean paused;

	public Scheduler(int slots, boolean holdWhilePlaying, History history, LongSupplier clock) {
		this.slots = Math.max(1, slots);
		this.holdWhilePlaying = holdWhilePlaying;
		this.history = history;
		this.clock = clock;
	}

	public Lease enqueue(Lease.Request request, Lease.Listener listener) {
		List<Runnable> calls = new ArrayList<>();
		Lease lease;
		synchronized (this) {
			lease = new Lease(history != null ? history.nextId() : nextId++, request, clock.getAsLong(), listener);
			if (request.play()) {
				lease.startedAt = lease.queuedAt;
				playing.add(lease);
				calls.add(() -> listener.granted(lease));
			} else {
				waiting.add(lease);
			}
			pump(calls);
		}
		calls.forEach(Runnable::run);
		return lease;
	}

	/** The game is done, or its build went away; waiting or running, it leaves. Releasing twice is harmless. */
	public void release(Lease lease) {
		List<Runnable> calls = new ArrayList<>();
		synchronized (this) {
			boolean ran = running.remove(lease) || playing.remove(lease);
			waiting.remove(lease);
			if (ran && history != null) history.record(lease, clock.getAsLong());
			pump(calls);
		}
		calls.forEach(Runnable::run);
	}

	public void setPaused(boolean paused) {
		List<Runnable> calls = new ArrayList<>();
		synchronized (this) {
			this.paused = paused;
			pump(calls);
		}
		calls.forEach(Runnable::run);
	}

	public void configure(int slots, boolean holdWhilePlaying) {
		List<Runnable> calls = new ArrayList<>();
		synchronized (this) {
			this.slots = Math.max(1, slots);
			this.holdWhilePlaying = holdWhilePlaying;
			pump(calls);
		}
		calls.forEach(Runnable::run);
	}

	public synchronized boolean isEmpty() {
		return waiting.isEmpty() && running.isEmpty() && playing.isEmpty();
	}

	/** Whether a running game is on the hidden display (it must stay up). */
	public synchronized boolean hiddenInUse() {
		for (Lease l : running) if (l.request.hidden()) return true;
		return false;
	}

	public synchronized Map<String, Object> snapshot() {
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("slots", slots);
		s.put("paused", paused);
		s.put("holdWhilePlaying", holdWhilePlaying);
		s.put("running", maps(running));
		s.put("playing", maps(playing));
		List<Map<String, Object>> w = maps(waiting);
		if (!waiting.isEmpty()) w.get(0).put("reason", blockedBy(waiting.get(0)));
		s.put("waiting", w);
		return s;
	}

	private static List<Map<String, Object>> maps(List<Lease> leases) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Lease l : leases) out.add(l.toMap());
		return out;
	}

	/** Why the head of the line can't start yet, or null if it can. */
	private String blockedBy(Lease head) {
		if (paused) return "the queue is paused (spawnproof resume)";
		if (holdWhilePlaying && !playing.isEmpty()) return "someone is playing: tests wait until the game closes";
		for (Lease l : running) {
			if (l.request.exclusive()) return "a game that needs the machine to itself is running";
		}
		if (head.request.exclusive() && !running.isEmpty()) return "this game needs the machine to itself: waiting for the others to finish";
		if (running.size() >= slots) return slots == 1 ? "the game slot is busy" : "all " + slots + " game slots are busy";
		return null;
	}

	private void pump(List<Runnable> calls) {
		while (!waiting.isEmpty() && blockedBy(waiting.get(0)) == null) {
			Lease next = waiting.remove(0);
			next.startedAt = clock.getAsLong();
			running.add(next);
			calls.add(() -> next.listener.granted(next));
		}
		if (waiting.isEmpty()) return;
		String reason = blockedBy(waiting.get(0));
		List<String> busy = new ArrayList<>();
		for (Lease l : running) busy.add(l.describe());
		for (Lease l : playing) busy.add(l.describe() + " [playing]");
		for (int i = 0; i < waiting.size(); i++) {
			Lease l = waiting.get(i);
			int position = i + 1;
			if (l.toldPosition == position && reason.equals(l.toldReason)) continue;
			l.toldPosition = position;
			l.toldReason = reason;
			List<String> snapshot = List.copyOf(busy);
			calls.add(() -> l.listener.queued(l, position, reason, snapshot));
		}
	}
}
