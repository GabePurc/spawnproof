package io.github.gabepurc.spawnproof.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Game ids go on from one daemon to the next, so an id in the log or the history names one game only. */
class GameIdTest {
	@TempDir
	Path state;

	private long time = 1000;

	/** A daemon's scheduler on the state folder's history, as a new daemon starts with. */
	private Scheduler daemon() {
		return new Scheduler(2, true, new History(state.resolve("history.jsonl")), () -> time++);
	}

	private static Lease ask(Scheduler scheduler) {
		Lease.Request r = new Lease.Request("Mod", "runClientGameTest", "/mod", "main", null, false, false, true, 1);
		return scheduler.enqueue(r, new Lease.Listener() {
			@Override
			public void queued(Lease lease, int position, String reason, List<String> running) {}

			@Override
			public void granted(Lease lease) {}
		});
	}

	@Test
	void noIdRepeatsAcrossRestarts() {
		List<Long> ids = new ArrayList<>();
		for (int restart = 0; restart < 3; restart++) {
			Scheduler scheduler = daemon();
			for (int game = 0; game < 3; game++) {
				Lease lease = ask(scheduler);
				ids.add(lease.id);
				scheduler.release(lease);
			}
		}
		assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L), ids);
	}

	@Test
	void aGameThatLeftTheLineWithoutRunningKeepsItsId() {
		Scheduler first = daemon();
		Lease a = ask(first);
		Lease b = ask(first);
		Lease waiting = ask(first); // Two slots: this one waits, then its build goes away before it runs.
		first.release(waiting);
		first.release(a);
		first.release(b);
		assertEquals(4, ask(daemon()).id, "the history only has games 1 and 2, but 3 was handed out too");
	}

	@Test
	void goesOnFromAHistoryWrittenBeforeIdsCarriedOver() throws IOException {
		// An older daemon numbered from 1 after each restart, and wrote no last id.
		Files.write(state.resolve("history.jsonl"), List.of(
			"{\"id\":50,\"project\":\"Mod\",\"task\":\"runClientGameTest\"}",
			"{\"id\":51,\"project\":\"Mod\",\"task\":\"runClientGameTest\"}",
			"{\"id\":1,\"project\":\"Mod\",\"task\":\"runClientGameTest\"}",
			"{\"id\":2,\"project\":\"Mod\",\"task\":\"runClientGameTest\"}"), StandardCharsets.UTF_8);
		assertEquals(52, ask(daemon()).id);
	}
}
