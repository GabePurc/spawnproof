package io.github.gabepurc.spawnproof.watch;

import io.github.gabepurc.spawnproof.Dirs;
import io.github.gabepurc.spawnproof.Json;
import io.github.gabepurc.spawnproof.Version;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What Spawnproof asks of GitHub: a watched repo's open pull requests, and telling a pull request how its tests went, as a
 * commit status and a comment. It signs in as you: with the token `gh auth token` gives, or one kept in
 * {@code github-token} in Spawnproof's config folder.
 */
public final class GitHub {
	/** The commit status's name, which a repo's branch protection can require. */
	public static final String CONTEXT = "spawnproof";
	/** How Spawnproof's comment on a pull request starts, so it finds its own comment again to edit it. */
	static final String MARKER = "<!-- spawnproof -->";
	private static final Pattern REPO_URL = Pattern.compile("github\\.com[:/]+([\\w.-]+)/([\\w.-]+?)(?:\\.git)?/?$");
	private static final long TOKEN_KEPT_MS = 10 * 60_000;

	private final String api;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
	private String token;
	private long tokenAt;

	public GitHub(String api) {
		this.api = api.replaceAll("/+$", "");
	}

	String api() {
		return api;
	}

	/** A reply: the status code, the body (parsed), and its ETag for asking again cheaply. */
	record Reply(int code, Object body, String etag) {
		@SuppressWarnings("unchecked")
		Map<String, Object> object() {
			return body instanceof Map ? (Map<String, Object>) body : Map.of();
		}

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> objects() {
			List<Map<String, Object>> out = new ArrayList<>();
			if (body instanceof List<?> list) for (Object o : list) if (o instanceof Map) out.add((Map<String, Object>) o);
			return out;
		}
	}

	/** "owner/repo" from a GitHub remote's address (https or ssh), or null if it isn't one. */
	public static String repoFromUrl(String url) {
		if (url == null) return null;
		Matcher m = REPO_URL.matcher(url.trim());
		return m.find() ? m.group(1) + "/" + m.group(2) : null;
	}

	/** Where a token can be kept for Spawnproof alone, instead of using gh's. */
	public static Path tokenFile() {
		return Dirs.config().resolve("github-token");
	}

	/** The token to sign in with, kept a few minutes so gh isn't asked for it every time. */
	synchronized String token() throws IOException {
		long now = System.currentTimeMillis();
		if (token != null && now - tokenAt < TOKEN_KEPT_MS) return token;
		token = findToken();
		tokenAt = now;
		return token;
	}

	private synchronized void forgetToken() {
		token = null;
	}

	private static String findToken() throws IOException {
		Path file = tokenFile();
		if (Files.isRegularFile(file)) {
			String t = Files.readString(file, StandardCharsets.UTF_8).trim();
			if (!t.isEmpty()) return t;
		}
		try {
			Process p = new ProcessBuilder("gh", "auth", "token").redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD).start();
			p.getOutputStream().close();
			String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
			if (p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0 && !out.isEmpty()) return out;
		} catch (IOException e) {
			// No gh on this machine.
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new InterruptedIOException("cancelled");
		}
		throw new IOException("Spawnproof isn't signed in to GitHub: run `gh auth login`, or put a token in " + file);
	}

	Reply get(String path, String etag) throws IOException {
		return send("GET", path, null, etag);
	}

	Reply send(String method, String path, Map<String, Object> body) throws IOException {
		return send(method, path, body, null);
	}

	private Reply send(String method, String path, Map<String, Object> body, String etag) throws IOException {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(api + path))
			.timeout(Duration.ofSeconds(30))
			.header("Accept", "application/vnd.github+json")
			.header("X-GitHub-Api-Version", "2022-11-28")
			.header("User-Agent", "spawnproof/" + Version.VERSION)
			.header("Authorization", "Bearer " + token());
		if (etag != null) b.header("If-None-Match", etag);
		if (body != null) {
			b.header("Content-Type", "application/json");
			b.method(method, HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
		} else {
			b.method(method, HttpRequest.BodyPublishers.noBody());
		}
		HttpResponse<String> response;
		try {
			response = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new InterruptedIOException("cancelled");
		} catch (IOException e) {
			throw new IOException("couldn't reach GitHub (" + api + "): " + e.getMessage(), e);
		}
		int code = response.statusCode();
		String text = response.body();
		Object parsed = null;
		if (text != null && !text.isBlank()) {
			try {
				parsed = Json.parse(text);
			} catch (RuntimeException e) {
				parsed = text;
			}
		}
		if (code == 304 || (code >= 200 && code < 300)) return new Reply(code, parsed, response.headers().firstValue("ETag").orElse(null));
		if (code == 401) forgetToken();
		String message = parsed instanceof Map<?, ?> m && m.get("message") != null ? String.valueOf(m.get("message")) : "HTTP " + code;
		if ((code == 403 || code == 429) && "0".equals(response.headers().firstValue("x-ratelimit-remaining").orElse(""))) {
			message = "GitHub's rate limit is used up for now; it resets within the hour";
		}
		throw new Problem(code, method + " " + path + ": " + message);
	}

	/** GitHub said no. */
	static final class Problem extends IOException {
		final int code;

		Problem(int code, String message) {
			super(message);
			this.code = code;
		}
	}

	/** Makes sure Spawnproof can tell this repo's pull requests how their tests went: it's signed in, and may write there. */
	public static void checkCanReport(String api, String repo) throws IOException {
		Map<String, Object> r = new GitHub(api).repo(repo);
		if (!Json.bool(Json.obj(r, "permissions"), "push")) {
			throw new IOException("your GitHub sign-in can't set statuses on " + repo + ": that takes write access to the repo");
		}
	}

	/** The repo, with what this sign-in may do in it (permissions.push is needed to set statuses). */
	Map<String, Object> repo(String repo) throws IOException {
		try {
			return get("/repos/" + repo, null).object();
		} catch (Problem e) {
			if (e.code == 404) throw new IOException("GitHub doesn't know " + repo + ", or your sign-in can't see it");
			throw e;
		}
	}

	Map<String, Object> pull(String repo, long number) throws IOException {
		try {
			return get("/repos/" + repo + "/pulls/" + number, null).object();
		} catch (Problem e) {
			if (e.code == 404) throw new IOException(repo + " has no pull request #" + number);
			throw e;
		}
	}

	/** The open pull requests; a 304 reply, when they're unchanged since the reply with this ETag, costs nothing. */
	Reply openPulls(String repo, String etag) throws IOException {
		return get("/repos/" + repo + "/pulls?state=open&per_page=100", etag);
	}

	/** Spawnproof's latest status on a commit, as {state, description}, or null if it has none. */
	Map<String, Object> ourStatus(String repo, String sha) throws IOException {
		for (Map<String, Object> s : get("/repos/" + repo + "/commits/" + sha + "/statuses?per_page=100", null).objects()) {
			if (CONTEXT.equals(Json.str(s, "context"))) return s;
		}
		return null;
	}

	/** Sets Spawnproof's status on a commit: pending, success, failure or error. */
	void setStatus(String repo, String sha, String state, String description, String url) throws IOException {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("state", state);
		body.put("context", CONTEXT);
		body.put("description", description.length() > 140 ? description.substring(0, 139) + "…" : description);
		if (url != null && url.startsWith("http")) body.put("target_url", url);
		send("POST", "/repos/" + repo + "/statuses/" + sha, body);
	}

	/** Spawnproof's comment on a pull request, as {id, html_url}, or null. */
	Map<String, Object> findComment(String repo, long pr) throws IOException {
		for (int page = 1; page <= 10; page++) {
			List<Map<String, Object>> comments = get("/repos/" + repo + "/issues/" + pr + "/comments?per_page=100&page=" + page, null).objects();
			for (Map<String, Object> c : comments) {
				String body = Json.str(c, "body");
				if (body != null && body.startsWith(MARKER)) return c;
			}
			if (comments.size() < 100) break;
		}
		return null;
	}

	/** Writes Spawnproof's comment on a pull request: edits the one it has, or adds one. Returns {id, html_url}. */
	Map<String, Object> writeComment(String repo, long pr, Long id, String body) throws IOException {
		if (id == null) {
			Map<String, Object> found = findComment(repo, pr);
			if (found != null) id = Json.num(found, "id", -1);
		}
		if (id != null && id > 0) {
			try {
				return send("PATCH", "/repos/" + repo + "/issues/comments/" + id, Map.of("body", body)).object();
			} catch (Problem e) {
				if (e.code != 404) throw e;
				// Someone deleted it: add another.
			}
		}
		return send("POST", "/repos/" + repo + "/issues/" + pr + "/comments", Map.of("body", body)).object();
	}
}
