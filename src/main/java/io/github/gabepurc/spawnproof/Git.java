package io.github.gabepurc.spawnproof;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** The branch a folder is on, read from .git without running git: shown next to each game so you can tell whose it is. */
public final class Git {
	private Git() {}

	public static String branch(Path dir) {
		try {
			for (Path d = dir.toAbsolutePath(); d != null; d = d.getParent()) {
				Path dotGit = d.resolve(".git");
				if (Files.isDirectory(dotGit)) return head(dotGit);
				if (Files.isRegularFile(dotGit)) {
					// A worktree: ".git" is a file saying where its git folder is.
					String text = Files.readString(dotGit).trim();
					if (!text.startsWith("gitdir:")) return null;
					Path gitDir = d.resolve(text.substring("gitdir:".length()).trim());
					return head(gitDir);
				}
			}
		} catch (IOException | RuntimeException e) {
			return null;
		}
		return null;
	}

	private static String head(Path gitDir) throws IOException {
		Path head = gitDir.resolve("HEAD");
		if (!Files.isRegularFile(head)) return null;
		String text = Files.readString(head).trim();
		if (text.startsWith("ref: refs/heads/")) return text.substring("ref: refs/heads/".length());
		return text.length() >= 7 ? text.substring(0, 7) : text;
	}
}
