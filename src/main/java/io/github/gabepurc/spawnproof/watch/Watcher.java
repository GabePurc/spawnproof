package io.github.gabepurc.spawnproof.watch;

import io.github.gabepurc.spawnproof.Dirs;
import io.github.gabepurc.spawnproof.Json;
import io.github.gabepurc.spawnproof.daemon.DaemonConfig;
import io.github.gabepurc.spawnproof.run.MergedResults;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The daemon's part in testing pull requests. Every minute or so it asks GitHub for each watched repo's open pull requests,
 * and a commit it hasn't seen goes in that repo's line to be tested (PrTest), one at a time. Each step is told to GitHub as
 * the commit's "spawnproof" status, and what failed goes in a comment on the pull request, edited after each run.
 *
 * <p>Pull requests from forks aren't tested unless someone asks (spawnproof pr), since that would run a stranger's code on
 * this machine. What each pull request came to is kept in the repo's folder (prs.json), so a daemon that starts again
 * picks up where the last one left off: a test it didn't finish is run again.
 */
public final class Watcher {
	private final Path listFile = Dirs.config().resolve("watching.json");
	private final Path stateDir = Dirs.state().resolve("watch");
	private final DaemonConfig config;
	private final Consumer<String> log;
	private final String host = host();
	private GitHub github;
	/** Guarded by this, as is everything in each Repo but its watch and its look at GitHub. */
	private final Map<String, Repo> repos = new LinkedHashMap<>();
	private final Object wake = new Object();
	private volatile boolean stopping;
	private Thread poller;

	public Watcher(DaemonConfig config, Consumer<String> log) {
		this.config = config;
		this.log = log;
	}

	/** One watched repo. */
	private final class Repo {
		volatile Watch watch;
		final Path dir;
		final Map<Long, Pr> prs = new TreeMap<>();
		final Deque<Job> line = new ArrayDeque<>();
		Job current;
		PrTest test;
		long testStartedAt;
		boolean dropped;
		Thread worker;
		// The last look at GitHub, by the poller alone.
		String etag;
		List<Map<String, Object>> pulls = List.of();
		volatile String problem;
		volatile long lookedAt;

		Repo(Watch watch) {
			this.watch = watch;
			this.dir = stateDir.resolve(watch.repo());
		}

		String name() {
			return watch.repo();
		}
	}

	/** A commit to test. byHand: someone asked for it (spawnproof pr), which is how a fork's pull request gets tested. */
	private record Job(long pr, String sha, String title, String cloneUrl, boolean byHand) {}

	/**
	 * What a pull request came to, as far as this machine knows: waiting, testing, passed, failed, error (it couldn't be
	 * tested), fork (not tested on its own), gone (the commit went before its turn), or interrupted (the daemon stopped
	 * while it waited or ran: it's tested again).
	 */
	private static final class Pr {
		String sha;
		String state;
		String title;
		String summary;
		long at;
		Long commentId;
		String commentUrl;
		/** A report GitHub didn't take (it couldn't be reached): tried again at each look. */
		Map<String, Object> unsent;

		Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("sha", sha);
			m.put("state", state);
			m.put("title", title);
			m.put("summary", summary);
			m.put("at", at);
			m.put("commentId", commentId);
			m.put("commentUrl", commentUrl);
			m.put("unsent", unsent);
			return m;
		}

		static Pr from(Map<String, Object> m) {
			Pr p = new Pr();
			p.sha = Json.str(m, "sha");
			p.state = Json.str(m, "state");
			if ("waiting".equals(p.state) || "testing".equals(p.state)) p.state = "interrupted";
			p.title = Json.str(m, "title");
			p.summary = Json.str(m, "summary");
			p.at = Json.num(m, "at", 0);
			long id = Json.num(m, "commentId", -1);
			p.commentId = id > 0 ? id : null;
			p.commentUrl = Json.str(m, "commentUrl");
			Map<String, Object> unsent = Json.obj(m, "unsent");
			p.unsent = unsent.isEmpty() ? null : unsent;
			return p;
		}
	}

	// ---- The daemon's side.

	public void start() {
		for (Watch w : Watch.load(listFile)) {
			synchronized (this) {
				add(w);
			}
		}
		poller = new Thread(this::poll, "spawnproof-watch");
		poller.setDaemon(true);
		poller.start();
		if (!repos.isEmpty()) log.accept("watching " + String.join(", ", repos.keySet()));
	}

	/** Stops the tests running, without telling GitHub: they're run again when the daemon is back. */
	public void stop() {
		stopping = true;
		synchronized (wake) {
			wake.notifyAll();
		}
		List<Thread> workers = new ArrayList<>();
		synchronized (this) {
			for (Repo r : repos.values()) {
				if (r.test != null) r.test.stop("Spawnproof stopped");
				if (r.worker != null) workers.add(r.worker);
			}
			notifyAll();
		}
		long deadline = System.currentTimeMillis() + 40_000;
		for (Thread t : workers) {
			try {
				t.join(Math.max(1, deadline - System.currentTimeMillis()));
			} catch (InterruptedException e) {
				return;
			}
		}
	}

	/** True while anything is watched: the daemon stays up for it. */
	public synchronized boolean watching() {
		return !repos.isEmpty();
	}

	/** The repos watched, by name. */
	public synchronized List<String> watched() {
		return new ArrayList<>(repos.keySet());
	}

	/** The pull requests being tested right now, which a newer Spawnproof waits for before it replaces this one. */
	public synchronized List<String> testing() {
		List<String> out = new ArrayList<>();
		for (Repo r : repos.values()) if (r.current != null) out.add(r.name() + " PR #" + r.current.pr());
		return out;
	}

	/** Starts watching a repo, or changes what's run for one already watched. */
	public Map<String, Object> watch(Watch w) throws IOException {
		if (!Watch.validRepo(w.repo())) throw new IOException("'" + w.repo() + "' isn't a GitHub repo's name (owner/repo)");
		synchronized (this) {
			Repo r = repos.get(w.repo());
			if (r != null) r.watch = w;
			else add(w);
			Watch.save(listFile, watches());
		}
		log.accept("watch " + w.repo() + " (" + (w.path().isEmpty() ? "" : w.path() + ", ") + "spawnproof test " + String.join(" ", w.args()) + ")");
		wakePoller();
		return Map.of("type", "ok", "repo", w.repo(), "dir", stateDir.resolve(w.repo()).toString());
	}

	/** Stops watching a repo: its test stops, those waiting are let go, and its clone is deleted. */
	public Map<String, Object> unwatch(String name) throws IOException {
		Repo r;
		List<Job> waiting;
		synchronized (this) {
			r = repos.remove(name);
			if (r == null) throw new IOException(name + " isn't watched (spawnproof status lists what is)");
			r.dropped = true;
			if (r.test != null) r.test.stop("Spawnproof stopped watching the repo");
			waiting = new ArrayList<>(r.line);
			r.line.clear();
			Watch.save(listFile, watches());
			notifyAll();
		}
		log.accept("unwatch " + name);
		for (Job j : waiting) tell(r, j.sha(), "error", "Not tested: Spawnproof stopped watching the repo", null);
		Thread cleaner = new Thread(() -> {
			try {
				if (r.worker != null) r.worker.join(60_000);
				MergedResults.deleteTree(r.dir);
			} catch (IOException | InterruptedException e) {
				log.accept("couldn't delete " + r.dir + ": " + e);
			}
		}, "spawnproof-unwatch");
		cleaner.setDaemon(true);
		cleaner.start();
		return Map.of("type", "ok", "repo", name);
	}

	/** Tests a pull request now, a fork's too, even if its commit was tested before. */
	public Map<String, Object> testPr(String name, long number) throws IOException {
		Repo r;
		synchronized (this) {
			r = repos.get(name);
		}
		if (r == null) throw new IOException(name + " isn't watched: spawnproof watch in its folder starts watching it");
		Map<String, Object> pull = github().pull(name, number);
		if (!"open".equals(Json.str(pull, "state"))) throw new IOException("pull request #" + number + " is closed");
		String sha = Json.str(Json.obj(pull, "head"), "sha");
		synchronized (this) {
			if (r.current != null && r.current.pr() == number && r.current.sha().equals(sha)) {
				return Map.of("type", "ok", "message", "Pull request #" + number + " is being tested now.");
			}
		}
		enqueue(r, new Job(number, sha, Json.str(pull, "title"), Json.str(Json.obj(Json.obj(pull, "base"), "repo"), "clone_url"), true));
		int position;
		synchronized (this) {
			position = r.line.size() + (r.current != null ? 1 : 0);
		}
		return Map.of("type", "ok", "message", "Pull request #" + number + " (" + Json.str(pull, "title") + ") is "
			+ (position <= 1 ? "next" : "number " + position + " in line") + " to be tested on this machine.");
	}

	/** What's watched and how it's going, for spawnproof status. */
	public synchronized List<Map<String, Object>> describe() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Repo r : repos.values()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("repo", r.name());
			m.put("path", r.watch.path());
			m.put("args", r.watch.args());
			m.put("problem", r.problem);
			m.put("lookedAt", r.lookedAt);
			if (r.current != null) {
				Map<String, Object> t = new LinkedHashMap<>();
				t.put("pr", r.current.pr());
				t.put("sha", r.current.sha());
				t.put("title", r.current.title());
				t.put("startedAt", r.testStartedAt);
				m.put("testing", t);
			}
			List<Object> waiting = new ArrayList<>();
			for (Job j : r.line) waiting.add(j.pr());
			m.put("waiting", waiting);
			List<Object> prs = new ArrayList<>();
			for (Map.Entry<Long, Pr> e : r.prs.entrySet()) {
				Map<String, Object> p = new LinkedHashMap<>();
				p.put("pr", e.getKey());
				p.put("sha", e.getValue().sha);
				p.put("state", e.getValue().state);
				p.put("title", e.getValue().title);
				p.put("summary", e.getValue().summary);
				p.put("at", e.getValue().at);
				prs.add(p);
			}
			m.put("prs", prs);
			out.add(m);
		}
		return out;
	}

	// ---- Looking at GitHub.

	private void poll() {
		while (!stopping) {
			List<Repo> all;
			synchronized (this) {
				all = new ArrayList<>(repos.values());
			}
			for (Repo r : all) {
				if (stopping) return;
				look(r);
			}
			synchronized (wake) {
				try {
					wake.wait(config.watchSeconds() * 1000L);
				} catch (InterruptedException e) {
					return;
				}
			}
		}
	}

	private void wakePoller() {
		synchronized (wake) {
			wake.notifyAll();
		}
	}

	/** One look at a repo's open pull requests. */
	private void look(Repo r) {
		String name = r.name();
		try {
			resend(r);
			GitHub.Reply reply = github().openPulls(name, r.etag);
			if (reply.code() != 304) {
				r.pulls = reply.objects();
				r.etag = reply.etag();
			}
			Set<Long> open = new HashSet<>();
			for (Map<String, Object> pull : r.pulls) {
				long n = Json.num(pull, "number", -1);
				Map<String, Object> head = Json.obj(pull, "head");
				Map<String, Object> base = Json.obj(Json.obj(pull, "base"), "repo");
				String sha = Json.str(head, "sha");
				if (n < 0 || sha == null) continue;
				open.add(n);
				String title = Json.str(pull, "title");
				Pr known;
				synchronized (this) {
					known = r.prs.get(n);
				}
				if (known != null && sha.equals(known.sha) && !"interrupted".equals(known.state)) continue;
				boolean fork = !String.valueOf(Json.str(base, "full_name")).equalsIgnoreCase(String.valueOf(Json.str(Json.obj(head, "repo"), "full_name")));
				if (fork) {
					synchronized (this) {
						Pr p = keep(r, n, sha, title);
						p.state = "fork";
						p.summary = "From a fork: not tested unless asked (spawnproof pr " + n + ")";
						save(r);
					}
					continue;
				}
				// A commit not seen here may have been tested before this machine's records were lost.
				if (known == null || !sha.equals(known.sha)) {
					Map<String, Object> status = github().ourStatus(name, sha);
					String state = status == null ? null : Json.str(status, "state");
					if ("success".equals(state) || "failure".equals(state)) {
						synchronized (this) {
							Pr p = keep(r, n, sha, title);
							p.state = "success".equals(state) ? "passed" : "failed";
							p.summary = Json.str(status, "description");
							save(r);
						}
						continue;
					}
				}
				enqueue(r, new Job(n, sha, title, Json.str(base, "clone_url"), false));
			}
			List<Long> closed = new ArrayList<>();
			synchronized (this) {
				for (Long n : r.prs.keySet()) if (!open.contains(n)) closed.add(n);
				for (Long n : closed) {
					r.prs.remove(n);
					r.line.removeIf(j -> j.pr() == n);
					if (r.current != null && r.current.pr() == n) r.test.stop("the pull request was closed");
				}
				if (!closed.isEmpty()) save(r);
			}
			if (r.problem != null) log.accept(name + ": GitHub answers again");
			r.problem = null;
		} catch (IOException e) {
			if (!String.valueOf(e.getMessage()).equals(r.problem)) log.accept(name + ": " + e.getMessage());
			r.problem = e.getMessage();
		} catch (RuntimeException e) {
			log.accept(name + ": looking at its pull requests failed: " + e);
			r.problem = String.valueOf(e);
		}
		r.lookedAt = System.currentTimeMillis();
	}

	/** The pull request's record, started afresh for a new commit (keeping its comment, which is edited from now on). */
	private Pr keep(Repo r, long n, String sha, String title) {
		Pr p = r.prs.get(n);
		if (p == null) {
			p = new Pr();
			r.prs.put(n, p);
		}
		if (!sha.equals(p.sha)) {
			p.summary = null;
			p.unsent = null;
		}
		p.sha = sha;
		p.title = title;
		p.at = System.currentTimeMillis();
		return p;
	}

	/** Puts a commit in its repo's line. An older commit of the same pull request, waiting or being tested, gives way to it. */
	private void enqueue(Repo r, Job job) {
		synchronized (this) {
			if (r.dropped) return;
		}
		// Said before the job is in line, so it can't come after the worker's "testing".
		tell(r, job.sha(), "pending", "Waiting its turn on " + host, null);
		List<String> replaced = new ArrayList<>();
		synchronized (this) {
			if (r.dropped) return;
			for (Iterator<Job> it = r.line.iterator(); it.hasNext(); ) {
				Job j = it.next();
				if (j.pr() != job.pr()) continue;
				it.remove();
				if (!j.sha().equals(job.sha())) replaced.add(j.sha());
			}
			if (r.current != null && r.current.pr() == job.pr() && !r.current.sha().equals(job.sha())) {
				r.test.stop("a newer commit replaced it");
			}
			r.line.add(job);
			Pr p = keep(r, job.pr(), job.sha(), job.title());
			p.state = "waiting";
			save(r);
			if (r.worker == null) startWorker(r);
			notifyAll();
		}
		for (String sha : replaced) tell(r, sha, "error", "Not tested: a newer commit replaced it", null);
	}

	// ---- Testing.

	private void startWorker(Repo r) {
		r.worker = new Thread(() -> work(r), "spawnproof-test-" + r.name());
		r.worker.setDaemon(true);
		r.worker.start();
	}

	/** A repo's line: one test at a time, in the repo's own clone. */
	private void work(Repo r) {
		while (true) {
			Job job;
			PrTest test;
			synchronized (this) {
				while (r.line.isEmpty() && !stopping && !r.dropped) {
					try {
						wait();
					} catch (InterruptedException e) {
						return;
					}
				}
				if (stopping || r.dropped) return;
				job = r.line.poll();
				test = new PrTest(r.dir, r.watch, job.pr(), job.sha(), job.cloneUrl());
				r.current = job;
				r.test = test;
				r.testStartedAt = System.currentTimeMillis();
				Pr p = r.prs.get(job.pr());
				if (p != null && job.sha().equals(p.sha)) p.state = "testing";
				save(r);
			}
			tell(r, job.sha(), "pending", "Testing on " + host, null);
			log.accept("testing " + r.name() + " PR #" + job.pr() + " at " + job.sha().substring(0, Math.min(7, job.sha().length())));
			Report.Outcome outcome;
			try {
				outcome = test.run();
			} catch (InterruptedException e) {
				return;
			} catch (IOException | RuntimeException e) {
				outcome = Report.error("Spawnproof went wrong testing it: " + e.getMessage());
			}
			synchronized (this) {
				r.current = null;
				r.test = null;
			}
			// The daemon is stopping: GitHub is left waiting, and the next daemon tests it again.
			if (stopping) return;
			String stopped = test.stoppedBecause();
			if (outcome == null || stopped != null) {
				synchronized (this) {
					Pr p = r.prs.get(job.pr());
					if (p != null && job.sha().equals(p.sha)) {
						p.state = outcome == null ? "gone" : "error";
						p.summary = outcome == null ? "The commit went before its turn" : "Not finished: " + stopped;
						save(r);
					}
				}
				tell(r, job.sha(), "error", outcome == null ? "Not tested: the commit went before its turn" : "Not finished: " + stopped, null);
				if (stopped != null) log.accept(r.name() + " PR #" + job.pr() + " stopped: " + stopped);
				continue;
			}
			log.accept(r.name() + " PR #" + job.pr() + ": " + outcome.summary());
			report(r, job, outcome);
		}
	}

	/** Tells GitHub how a test went: the comment first (if one's needed), then the status, which links to it. */
	private void report(Repo r, Job job, Report.Outcome outcome) {
		Map<String, Object> unsent = new LinkedHashMap<>();
		unsent.put("sha", job.sha());
		unsent.put("state", outcome.state());
		unsent.put("description", Report.description(outcome, host));
		unsent.put("comment", Report.comment(outcome, job.sha(), host));
		// A passing run edits the comment from an earlier one that didn't pass, but doesn't add one.
		unsent.put("commentOnlyIfThere", outcome.passed());
		Pr p;
		synchronized (this) {
			p = r.prs.get(job.pr());
			if (p != null && job.sha().equals(p.sha)) {
				p.state = switch (outcome.state()) {
					case "success" -> "passed";
					case "failure" -> "failed";
					default -> "error";
				};
				p.summary = outcome.summary();
				p.at = System.currentTimeMillis();
				p.unsent = unsent;
				save(r);
			} else {
				p = null;
			}
		}
		if (p == null) {
			// A newer commit came along meanwhile: this one's status is still told, without the comment, which is the newer one's.
			tell(r, job.sha(), outcome.state(), Report.description(outcome, host), null);
			return;
		}
		send(r, job.pr(), p);
	}

	/** Reports GitHub didn't take earlier, tried again. */
	private void resend(Repo r) {
		List<Map.Entry<Long, Pr>> due = new ArrayList<>();
		synchronized (this) {
			for (Map.Entry<Long, Pr> e : r.prs.entrySet()) if (e.getValue().unsent != null) due.add(e);
		}
		for (Map.Entry<Long, Pr> e : due) send(r, e.getKey(), e.getValue());
	}

	private void send(Repo r, long pr, Pr p) {
		Map<String, Object> unsent;
		Long commentId;
		synchronized (this) {
			unsent = p.unsent;
			commentId = p.commentId;
		}
		if (unsent == null) return;
		String sha = Json.str(unsent, "sha");
		try {
			String url = null;
			String body = Json.str(unsent, "comment");
			boolean onlyIfThere = Json.bool(unsent, "commentOnlyIfThere");
			if (body != null) {
				Long id = commentId;
				if (id == null && onlyIfThere) {
					Map<String, Object> found = github().findComment(r.name(), pr);
					if (found != null) id = Json.num(found, "id", -1);
				}
				if (id != null || !onlyIfThere) {
					Map<String, Object> c = github().writeComment(r.name(), pr, id, body);
					long newId = Json.num(c, "id", -1);
					url = Json.str(c, "html_url");
					synchronized (this) {
						p.commentId = newId > 0 ? newId : null;
						p.commentUrl = url;
					}
				}
			}
			github().setStatus(r.name(), sha, Json.str(unsent, "state"), Json.str(unsent, "description"), url);
			synchronized (this) {
				if (p.unsent == unsent) p.unsent = null;
				save(r);
			}
		} catch (IOException e) {
			r.problem = "couldn't tell GitHub how PR #" + pr + " went (tried again in a minute): " + e.getMessage();
			log.accept(r.name() + ": " + r.problem);
		}
	}

	/** Sets a commit's status, as far as GitHub can be reached: a status it misses is only a step on the way. */
	private void tell(Repo r, String sha, String state, String description, String url) {
		try {
			github().setStatus(r.name(), sha, state, description, url);
		} catch (IOException e) {
			r.problem = "couldn't set a status on GitHub: " + e.getMessage();
		}
	}

	// ---- Keeping things.

	/** Adds a repo to the watched ones, with what's known of its pull requests. Called holding this. */
	private void add(Watch w) {
		Repo r = new Repo(w);
		Path file = r.dir.resolve("prs.json");
		if (Files.isRegularFile(file)) {
			try {
				Map<String, Object> saved = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
				for (Map.Entry<String, Object> e : Json.obj(saved, "prs").entrySet()) {
					if (e.getValue() instanceof Map<?, ?>) r.prs.put(Long.parseLong(e.getKey()), Pr.from(Json.obj(Json.obj(saved, "prs"), e.getKey())));
				}
			} catch (IOException | RuntimeException e) {
				log.accept(w.repo() + ": couldn't read " + file + " (" + e.getMessage() + "); starting afresh");
			}
		}
		repos.put(w.repo(), r);
	}

	private List<Watch> watches() {
		List<Watch> out = new ArrayList<>();
		for (Repo r : repos.values()) out.add(r.watch);
		return out;
	}

	/** Writes what's known of a repo's pull requests. Called holding this. */
	private void save(Repo r) {
		if (r.dropped) return;
		Map<String, Object> prs = new LinkedHashMap<>();
		for (Map.Entry<Long, Pr> e : r.prs.entrySet()) prs.put(Long.toString(e.getKey()), e.getValue().toMap());
		try {
			Watch.write(r.dir.resolve("prs.json"), Json.pretty(Map.of("prs", prs)) + "\n");
		} catch (IOException e) {
			log.accept(r.name() + ": couldn't save " + r.dir.resolve("prs.json") + ": " + e.getMessage());
		}
	}

	private synchronized GitHub github() {
		String api = config.githubApi();
		if (github == null || !github.api().equals(api)) github = new GitHub(api);
		return github;
	}

	/** This machine's name, said on GitHub so people know where the tests ran. */
	static String host() {
		try {
			Path file = Path.of("/etc/hostname");
			if (Files.isRegularFile(file)) {
				String name = Files.readString(file).trim();
				if (!name.isEmpty()) return name;
			}
		} catch (IOException | RuntimeException ignored) {
			// Ask Java instead.
		}
		try {
			return InetAddress.getLocalHost().getHostName();
		} catch (IOException | RuntimeException e) {
			return "this machine";
		}
	}
}
