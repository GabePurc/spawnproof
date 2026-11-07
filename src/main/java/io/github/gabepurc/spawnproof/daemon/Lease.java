package io.github.gabepurc.spawnproof.daemon;

import io.github.gabepurc.spawnproof.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One game's place in the queue, from asking until its build lets go (or dies: closing the connection lets go too). */
public final class Lease {
	/** What a game asks for. A play lease is someone playing: it never waits, and holds new test games back while it lasts. */
	public record Request(String project, String task, String dir, String branch, String label, boolean play, boolean exclusive,
		boolean hidden, long pid) {
		public static Request from(Map<String, Object> m) {
			return new Request(
				orElse(Json.str(m, "project"), "?"),
				orElse(Json.str(m, "task"), "?"),
				Json.str(m, "dir"),
				Json.str(m, "branch"),
				Json.str(m, "label"),
				"play".equals(Json.str(m, "role")),
				Json.bool(m, "exclusive"),
				!Boolean.FALSE.equals(m.get("hidden")),
				Json.num(m, "pid", -1));
		}

		private static String orElse(String v, String fallback) {
			return v == null || v.isBlank() ? fallback : v;
		}
	}

	public interface Listener {
		/** Still waiting: where in line, and why the line isn't moving. */
		void queued(Lease lease, int position, String reason, List<String> running);

		void granted(Lease lease);
	}

	public final long id;
	public final Request request;
	public final long queuedAt;
	final Listener listener;
	volatile long startedAt;
	volatile String display;
	// Guarded by the scheduler: the last position told to the client, so it only hears about changes.
	int toldPosition = -1;
	String toldReason;

	Lease(long id, Request request, long queuedAt, Listener listener) {
		this.id = id;
		this.request = request;
		this.queuedAt = queuedAt;
		this.listener = listener;
	}

	/** "Horsing Around :26.3:runClientGameTest (100-hats)". */
	public String describe() {
		StringBuilder s = new StringBuilder(request.project()).append(' ').append(request.task());
		String where = request.label() != null ? request.label() : request.branch();
		if (where != null) s.append(" (").append(where).append(')');
		return s.toString();
	}

	public Map<String, Object> toMap() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", id);
		m.put("project", request.project());
		m.put("task", request.task());
		m.put("dir", request.dir());
		m.put("branch", request.branch());
		m.put("label", request.label());
		m.put("role", request.play() ? "play" : "test");
		m.put("exclusive", request.exclusive());
		m.put("hidden", request.hidden());
		m.put("pid", request.pid());
		m.put("queuedAt", queuedAt);
		if (startedAt > 0) m.put("startedAt", startedAt);
		if (display != null) m.put("display", display);
		return m;
	}
}
