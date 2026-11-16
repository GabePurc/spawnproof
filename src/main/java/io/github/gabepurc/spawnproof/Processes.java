package io.github.gabepurc.spawnproof;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** The builds and games Spawnproof starts: stopping one, and reading the end of its console. */
public final class Processes {
	private Processes() {}

	/**
	 * Stops a process and anything it started (a build's Gradle and games, a game's dedicated server): politely, then for
	 * good if it hasn't gone in {@code graceSeconds}. Returns once it's gone.
	 */
	public static void stop(Process p, int graceSeconds) {
		p.descendants().forEach(ProcessHandle::destroy);
		p.destroy();
		try {
			if (!p.waitFor(graceSeconds, TimeUnit.SECONDS)) {
				p.descendants().forEach(ProcessHandle::destroyForcibly);
				p.destroyForcibly();
			}
		} catch (InterruptedException e) {
			p.destroyForcibly();
			Thread.currentThread().interrupt();
		}
	}

	/** The last lines of a file, read from its end so a long console isn't read whole. */
	public static List<String> tail(Path file, int lines) throws IOException {
		try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
			long length = f.length();
			long from = Math.max(0, length - 400_000);
			f.seek(from);
			byte[] bytes = new byte[(int) (length - from)];
			f.readFully(bytes);
			List<String> all = new String(bytes, StandardCharsets.UTF_8).lines().toList();
			if (from > 0 && !all.isEmpty()) all = all.subList(1, all.size());
			return all.subList(Math.max(0, all.size() - lines), all.size());
		}
	}
}
