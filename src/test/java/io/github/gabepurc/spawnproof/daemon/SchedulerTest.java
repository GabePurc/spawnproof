package io.github.gabepurc.spawnproof.daemon;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerTest {
	private final List<String> events = new ArrayList<>();
	private long time = 1000;
	private final Scheduler scheduler = new Scheduler(2, true, new History(null), () -> time++);

	private Lease ask(String task) {
		return ask(task, false, false);
	}

	private Lease ask(String task, boolean exclusive, boolean play) {
		Lease.Request r = new Lease.Request("Mod", task, "/mod", "main", null, play, exclusive, true, 1);
		return scheduler.enqueue(r, new Lease.Listener() {
			@Override
			public void queued(Lease lease, int position, String reason, List<String> running) {
				events.add(task + " queued " + position);
			}

			@Override
			public void granted(Lease lease) {
				events.add(task + " granted");
			}
		});
	}

	@Test
	void runsUpToTheSlotsAndThenInOrder() {
		Lease a = ask("a");
		Lease b = ask("b");
		ask("c");
		ask("d");
		assertEquals(List.of("a granted", "b granted", "c queued 1", "d queued 2"), events);
		events.clear();
		scheduler.release(b);
		assertEquals(List.of("c granted", "d queued 1"), events);
		events.clear();
		scheduler.release(a);
		assertEquals(List.of("d granted"), events);
	}

	@Test
	void anExclusiveGameWaitsForTheOthersAndHoldsTheLineBehindIt() {
		Lease a = ask("a");
		Lease alone = ask("alone", true, false);
		ask("c");
		assertEquals(List.of("a granted", "alone queued 1", "c queued 2"), events);
		events.clear();
		scheduler.release(a);
		assertEquals(List.of("alone granted", "c queued 1"), events, "c must not slip past while the exclusive game runs");
		events.clear();
		scheduler.release(alone);
		assertEquals(List.of("c granted"), events);
	}

	@Test
	void pausingHoldsNewGamesButNotRunningOnes() {
		ask("a");
		scheduler.setPaused(true);
		ask("b");
		assertEquals(List.of("a granted", "b queued 1"), events);
		events.clear();
		scheduler.setPaused(false);
		assertEquals(List.of("b granted"), events);
	}

	@Test
	void playingNeverWaitsAndHoldsTests() {
		ask("a");
		ask("b");
		Lease play = ask("runClient", false, true);
		ask("c");
		assertEquals(List.of("a granted", "b granted", "runClient granted", "c queued 1"), events);
		events.clear();
		scheduler.configure(4, true);
		assertEquals(List.of(), events, "a free slot doesn't matter while someone plays");
		scheduler.release(play);
		assertEquals(List.of("c granted"), events);
	}

	@Test
	void leavingTheLineMovesEveryoneUp() {
		ask("a");
		ask("b");
		Lease c = ask("c");
		ask("d");
		events.clear();
		scheduler.release(c);
		assertEquals(List.of("d queued 1"), events);
	}

	@Test
	void releasingTwiceIsHarmless() {
		Lease a = ask("a");
		scheduler.release(a);
		scheduler.release(a);
		assertTrue(scheduler.isEmpty());
	}

	@Test
	void hiddenInUseOnlyWhileAHiddenGameRuns() {
		assertFalse(scheduler.hiddenInUse());
		Lease a = ask("a");
		assertTrue(scheduler.hiddenInUse());
		scheduler.release(a);
		assertFalse(scheduler.hiddenInUse());
	}
}
