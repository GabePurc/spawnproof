package io.github.gabepurc.spawnproof.client;

import io.github.gabepurc.spawnproof.Json;

import java.util.List;
import java.util.Map;

/** What people read: queue updates in a build's output, and `spawnproof status`. */
public final class Text {
	private Text() {}

	/** "2nd in line: all 2 game slots are busy. Running: Horsing Around :26.3:runClientGameTest (main)". */
	public static String queued(Map<String, Object> m) {
		long position = Json.num(m, "position", 0);
		StringBuilder s = new StringBuilder(position <= 1 ? "next in line" : ordinal(position) + " in line");
		String reason = Json.str(m, "reason");
		if (reason != null) s.append(": ").append(reason);
		List<String> running = Json.strings(m, "running");
		if (!running.isEmpty()) s.append(". Running: ").append(String.join(", ", running));
		return s.toString();
	}

	public static String status(Map<String, Object> s) {
		long now = Json.num(s, "now", System.currentTimeMillis());
		StringBuilder out = new StringBuilder();
		long slots = Json.num(s, "slots", 0);
		out.append("Spawnproof ").append(Json.str(s, "version")).append(" · ").append(slots).append(slots == 1 ? " slot" : " slots");
		out.append(" · ").append(display(Json.obj(s, "display"))).append('\n');
		if (Json.bool(s, "paused")) out.append("Paused: running games finish, no new ones start (spawnproof resume).\n");

		List<Map<String, Object>> playing = Json.list(s, "playing");
		if (!playing.isEmpty()) {
			out.append("\nPlaying").append(Json.bool(s, "holdWhilePlaying") ? " (tests wait until the game closes)" : "").append('\n');
			for (Map<String, Object> l : playing) out.append(row(l, now, "startedAt")).append('\n');
		}
		List<Map<String, Object>> running = Json.list(s, "running");
		out.append("\nRunning").append(running.isEmpty() ? ": nothing" : "").append('\n');
		for (Map<String, Object> l : running) {
			StringBuilder r = new StringBuilder(row(l, now, "startedAt"));
			long estimate = Json.num(l, "estimateMs", -1);
			long elapsed = now - Json.num(l, "startedAt", now);
			if (estimate > 0) {
				r.append(estimate > elapsed ? ", about " + duration(estimate - elapsed) + " left" : ", running longer than usual (" + duration(estimate) + ")");
			}
			if (Json.bool(l, "exclusive")) r.append(", alone");
			if (Json.str(l, "display") != null) r.append(", hidden on ").append(Json.str(l, "display"));
			out.append(r).append('\n');
		}
		List<Map<String, Object>> waiting = Json.list(s, "waiting");
		if (!waiting.isEmpty()) {
			out.append("\nWaiting");
			String reason = Json.str(waiting.get(0), "reason");
			if (reason != null) out.append(": ").append(reason);
			out.append('\n');
			int i = 1;
			for (Map<String, Object> l : waiting) out.append(row(l, now, "queuedAt").replaceFirst("^  ", String.format("%2d. ", i++))).append(" waiting").append('\n');
		}
		List<Map<String, Object>> recent = Json.list(s, "recent");
		if (!recent.isEmpty()) {
			out.append("\nRecently\n");
			for (Map<String, Object> l : recent) {
				out.append("  ").append(who(l)).append(", ").append(duration(Json.num(l, "durationMs", 0)));
				long waited = Json.num(l, "waitedMs", 0);
				if (waited >= 1000) out.append(" after waiting ").append(duration(waited));
				out.append(", ended ").append(duration(now - Json.num(l, "endedAt", now))).append(" ago\n");
			}
		}
		return out.toString().stripTrailing();
	}

	private static String row(Map<String, Object> l, long now, String since) {
		return "  #" + Json.num(l, "id", 0) + " " + who(l) + ", " + duration(now - Json.num(l, since, now));
	}

	private static String who(Map<String, Object> l) {
		StringBuilder s = new StringBuilder();
		s.append(Json.str(l, "project")).append(' ').append(Json.str(l, "task"));
		String label = Json.str(l, "label");
		String branch = Json.str(l, "branch");
		if (label != null || branch != null) s.append(" (").append(label != null ? label : branch).append(')');
		return s.toString();
	}

	private static String display(Map<String, Object> d) {
		if (Json.bool(d, "up")) {
			String renderer = Json.str(d, "renderer");
			return "hidden display " + Json.str(d, "name") + " (" + Json.str(d, "kind") + (renderer != null ? ", " + renderer : "") + ")";
		}
		String note = Json.str(d, "note");
		return "hidden display " + Json.str(d, "mode") + (note != null ? ": " + note : "");
	}

	public static String duration(long ms) {
		long seconds = Math.max(0, ms / 1000);
		if (seconds < 60) return seconds + "s";
		long minutes = seconds / 60;
		if (minutes < 60) return minutes + "m " + String.format("%02ds", seconds % 60);
		return minutes / 60 + "h " + String.format("%02dm", minutes % 60);
	}

	static String ordinal(long n) {
		long mod100 = n % 100;
		String suffix = mod100 >= 11 && mod100 <= 13 ? "th" : switch ((int) (n % 10)) {
			case 1 -> "st";
			case 2 -> "nd";
			case 3 -> "rd";
			default -> "th";
		};
		return n + suffix;
	}
}
