package io.github.gabepurc.spawnproof.watch;

import io.github.gabepurc.spawnproof.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A repo this machine watches, as `spawnproof watch` left it: which GitHub repo, the checkout it was run in, the folder in
 * the repo the tests run from, and the `spawnproof test` options to run them with. The list is kept in watching.json in
 * Spawnproof's config folder.
 */
public record Watch(String repo, String source, String path, List<String> args, long since) {
	/** owner/repo, as GitHub allows them. */
	static boolean validRepo(String repo) {
		if (repo == null || !repo.matches("[\\w.-]+/[\\w.-]+")) return false;
		for (String part : repo.split("/")) if (part.equals(".") || part.equals("..")) return false;
		return true;
	}

	Map<String, Object> toMap() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("repo", repo);
		m.put("source", source);
		m.put("path", path);
		m.put("args", args);
		m.put("since", since);
		return m;
	}

	static Watch from(Map<String, Object> m) {
		String path = Json.str(m, "path");
		return new Watch(Json.str(m, "repo"), Json.str(m, "source"), path == null ? "" : path, Json.strings(m, "args"),
			Json.num(m, "since", 0));
	}

	static List<Watch> load(Path file) {
		List<Watch> out = new ArrayList<>();
		if (!Files.isRegularFile(file)) return out;
		try {
			for (Map<String, Object> m : Json.list(Json.parseObject(Files.readString(file, StandardCharsets.UTF_8)), "watching")) {
				Watch w = from(m);
				if (validRepo(w.repo())) out.add(w);
			}
		} catch (IOException | RuntimeException e) {
			// Unreadable: nothing is watched until it's written again.
		}
		return out;
	}

	static void save(Path file, Collection<Watch> watches) throws IOException {
		List<Object> list = new ArrayList<>();
		for (Watch w : watches) list.add(w.toMap());
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("comment", "The repos whose pull requests Spawnproof tests on this machine. Change it with spawnproof watch and unwatch.");
		m.put("watching", list);
		write(file, Json.pretty(m) + "\n");
	}

	/** Writes a file whole or not at all, so a daemon stopped halfway never leaves half of one. */
	static void write(Path file, String text) throws IOException {
		Files.createDirectories(file.getParent());
		Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
		Files.writeString(temp, text, StandardCharsets.UTF_8);
		Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
