package io.github.gabepurc.spawnproof.watch;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A folder in a mod's checkout, as `spawnproof watch` and the dashboard's Watch see it: the GitHub repo it's a checkout of
 * (its origin), the checkout's top folder, and the folder itself.
 */
public record Checkout(String repo, Path top, Path dir) {
	/** The folder from the checkout's top, with / between folders: "" at the top. */
	public String path() {
		return top.relativize(dir).toString().replace('\\', '/');
	}

	public static Checkout of(Path folder) throws IOException {
		Path dir = folder.toAbsolutePath().toRealPath();
		String top = git(dir, "rev-parse", "--show-toplevel");
		if (top == null) throw new IOException(dir + " isn't in a git checkout");
		String url = git(dir, "remote", "get-url", "origin");
		String repo = GitHub.repoFromUrl(url);
		if (repo == null) throw new IOException("the origin of the checkout in " + dir + " (" + (url == null ? "none" : url) + ") isn't a GitHub repo");
		return new Checkout(repo, Path.of(top).toRealPath(), dir);
	}

	/** What a git command prints, or null if it fails. */
	private static String git(Path dir, String... args) throws IOException {
		List<String> command = new ArrayList<>(List.of("git"));
		command.addAll(List.of(args));
		Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		p.getOutputStream().close();
		String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
		try {
			return p.waitFor() == 0 && !out.isEmpty() ? out : null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new InterruptedIOException("cancelled");
		}
	}
}
