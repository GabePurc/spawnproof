package io.github.gabepurc.spawnproof.daemon;

import io.github.gabepurc.spawnproof.Dirs;
import io.github.gabepurc.spawnproof.Json;
import io.github.gabepurc.spawnproof.Version;
import io.github.gabepurc.spawnproof.Wire;
import io.github.gabepurc.spawnproof.run.KeptRuns;
import io.github.gabepurc.spawnproof.watch.Watch;
import io.github.gabepurc.spawnproof.watch.Watcher;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The one process per user that hands out game slots and keeps the hidden display. Clients start it when they find none
 * (see Client); a lock file makes sure only one survives if two start at once, or while an old one is on its way out. It
 * exits after a while with nothing to do.
 *
 * <p>A game holds its slot by holding its connection: if its build dies, the connection closes and the slot frees itself.
 */
public final class Daemon {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final String BUILD = io.github.gabepurc.spawnproof.client.DaemonStarter.buildId();
	private final Path socket = Dirs.socket();
	private final DaemonConfig config = DaemonConfig.load(Dirs.configFile());
	private final History history;
	private final Scheduler scheduler;
	private final Displays displays;
	private final Watcher watcher = new Watcher(config, Daemon::log);
	private final ExecutorService grants = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "spawnproof-grant");
		t.setDaemon(true);
		return t;
	});
	private final AtomicInteger connections = new AtomicInteger();
	private final long startedAt = System.currentTimeMillis();
	private volatile long lastBusy = System.currentTimeMillis();
	private volatile boolean stopping;
	private ServerSocketChannel server;

	private Daemon(Path stateDir) {
		history = new History(stateDir.resolve("history.jsonl"));
		scheduler = new Scheduler(config.slots, config.holdWhilePlaying, history, System::currentTimeMillis);
		displays = new Displays(config, stateDir);
	}

	public static void main(String[] args) throws Exception {
		Path state = Dirs.state();
		Files.createDirectories(state);
		try (FileChannel lockFile = FileChannel.open(state.resolve("daemon.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
			FileLock lock = takeLock(lockFile);
			if (lock == null) {
				log("another daemon is running; this one exits");
				return;
			}
			new Daemon(state).serve();
		}
	}

	/**
	 * The lock that keeps one daemon running. Its holder may be a daemon that's running, which this one isn't needed beside,
	 * or one on its way out: replaced by a newer Spawnproof, it stops answering, then closes its hidden display before it
	 * lets go. That one is waited for. Null if another daemon has it.
	 */
	private static FileLock takeLock(FileChannel file) throws IOException, InterruptedException {
		long deadline = System.currentTimeMillis() + 30_000;
		while (true) {
			FileLock lock = file.tryLock();
			if (lock != null) return lock;
			if (answers(Dirs.socket()) || System.currentTimeMillis() > deadline) return null;
			Thread.sleep(100);
		}
	}

	private static boolean answers(Path socket) {
		try (Wire wire = Wire.connect(socket)) {
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	static synchronized void log(String message) {
		System.out.println(LocalDateTime.now().format(TIME) + " " + message);
		System.out.flush();
	}

	private void serve() throws IOException {
		Path runtime = socket.getParent();
		Files.createDirectories(runtime);
		try {
			Files.setPosixFilePermissions(runtime, PosixFilePermissions.fromString("rwx------"));
		} catch (UnsupportedOperationException | IOException ignored) {
			// Not a POSIX file system.
		}
		Files.deleteIfExists(socket);
		displays.killLeftovers();
		server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
		server.bind(UnixDomainSocketAddress.of(socket));
		Runtime.getRuntime().addShutdownHook(new Thread(this::cleanUp, "spawnproof-cleanup"));
		watcher.start();
		Thread keeper = new Thread(this::housekeeping, "spawnproof-housekeeping");
		keeper.setDaemon(true);
		keeper.start();
		log("Spawnproof " + Version.VERSION + " (" + BUILD + ") listening on " + socket + " (pid " + ProcessHandle.current().pid() + ", " + config.slots
			+ " slots, display " + config.display + ")");
		while (!stopping) {
			SocketChannel channel;
			try {
				channel = server.accept();
			} catch (ClosedChannelException e) {
				break;
			} catch (IOException e) {
				if (stopping) break;
				log("accept failed: " + e);
				continue;
			}
			Thread t = new Thread(() -> handle(new Wire(channel)), "spawnproof-client");
			t.setDaemon(true);
			t.start();
		}
		cleanUp();
	}

	private void housekeeping() {
		while (!stopping) {
			try {
				Thread.sleep(10_000);
			} catch (InterruptedException e) {
				return;
			}
			long now = System.currentTimeMillis();
			// A watched repo keeps it up: its pull requests can't be tested by a daemon that isn't running.
			boolean busy = !scheduler.isEmpty() || connections.get() > 0 || watcher.watching();
			if (busy) lastBusy = now;
			displays.stopIfIdle(scheduler.hiddenInUse(), config.displayIdleMinutes * 60_000L);
			if (!Files.exists(socket)) {
				log("the socket was removed; stopping");
				stop();
			} else if (!busy && now - lastBusy > config.idleMinutes * 60_000L) {
				log("nothing to do for " + config.idleMinutes + " minutes; stopping");
				stop();
			}
		}
	}

	private void stop() {
		stopping = true;
		try {
			if (server != null) server.close();
		} catch (IOException ignored) {
			// Closing.
		}
	}

	private synchronized void cleanUp() {
		watcher.stop();
		displays.stop();
		try {
			Files.deleteIfExists(socket);
		} catch (IOException ignored) {
			// Gone.
		}
	}

	private void handle(Wire wire) {
		connections.incrementAndGet();
		try {
			wire.send(Map.of("type", "welcome", "version", Version.VERSION, "build", BUILD, "protocol", Wire.PROTOCOL, "pid",
				ProcessHandle.current().pid()));
			Map<String, Object> request = wire.read();
			if (request == null) return;
			String type = String.valueOf(request.get("type"));
			switch (type) {
				case "acquire" -> serveLease(wire, request);
				case "status" -> wire.send(status());
				case "pause", "resume" -> {
					scheduler.setPaused(type.equals("pause"));
					log(type.equals("pause") ? "paused" : "resumed");
					wire.send(Map.of("type", "ok"));
				}
				case "set" -> {
					String problem = config.set(Json.str(request, "key"), Json.str(request, "value"));
					if (problem != null) {
						wire.send(Map.of("type", "error", "message", problem));
						return;
					}
					config.save(Dirs.configFile());
					scheduler.configure(config.slots, config.holdWhilePlaying);
					if (List.of("display", "resolution").contains(Json.str(request, "key"))) displays.restartWhenIdle(scheduler.hiddenInUse());
					log("set " + Json.str(request, "key") + " = " + Json.str(request, "value"));
					wire.send(Map.of("type", "ok", "config", config.toMap()));
				}
				case "stop" -> {
					boolean force = Json.bool(request, "force");
					if (!force && !scheduler.isEmpty()) {
						wire.send(Map.of("type", "error", "message", "games are running or waiting (stop --force stops anyway)"));
						return;
					}
					if (!force && !watcher.testing().isEmpty()) {
						wire.send(Map.of("type", "error", "message", "testing " + String.join(", ", watcher.testing()) + " (stop --force stops anyway; "
							+ "it's tested again when Spawnproof starts)"));
						return;
					}
					// A newer Spawnproof replacing this one says so, and picks the watching back up when it starts. Anyone else is
					// turned down while repos are watched: above all a build with an older Spawnproof, which would replace this daemon
					// with one that can't watch. Turned down, it uses this one as it is.
					if (!force && !Json.bool(request, "replace") && watcher.watching()) {
						wire.send(Map.of("type", "error", "message", "watching " + String.join(", ", watcher.watched()) + " to test its pull requests "
							+ "(spawnproof unwatch stops that; stop --force stops Spawnproof anyway, and the watching starts again with it)"));
						return;
					}
					wire.send(Map.of("type", "ok"));
					log("stopping on request");
					stop();
				}
				case "watch" -> wire.send(watcher.watch(new Watch(Json.str(request, "repo"), Json.str(request, "source"),
					String.valueOf(request.getOrDefault("path", "")), Json.strings(request, "args"), System.currentTimeMillis())));
				case "unwatch" -> wire.send(watcher.unwatch(Json.str(request, "repo")));
				case "test-pr" -> wire.send(watcher.testPr(Json.str(request, "repo"), Json.num(request, "pr", -1)));
				// For the dashboard; the command and an MCP server can ask the same.
				case "follow" -> follow(wire);
				case "projects" -> wire.send(Map.of("type", "projects", "projects",
					Projects.list(KeptRuns.all(), history.last(History.KEEP), Dirs.state().resolve("watch"))));
				case "runs" -> wire.send(Map.of("type", "runs", "runs",
					KeptRuns.list(Json.str(request, "project"), Json.str(request, "dir"), (int) Json.num(request, "limit", 50))));
				case "run" -> {
					Map<String, Object> run = KeptRuns.read(Json.str(request, "id"));
					if (run == null) {
						wire.send(Map.of("type", "error", "message", "no kept run " + Json.str(request, "id") + " (only the last " + config.keepRuns()
							+ " runs of each task in each checkout are kept)"));
						return;
					}
					Map<String, Object> m = new LinkedHashMap<>(run);
					m.put("type", "run");
					wire.send(m);
				}
				default -> wire.send(Map.of("type", "error", "message", "unknown request: " + type));
			}
		} catch (ClosedChannelException e) {
			// The client went away.
		} catch (IOException e) {
			// What went wrong with a request (watching a repo, say) goes back to whoever asked.
			trySend(wire, Map.of("type", "error", "message", String.valueOf(e.getMessage())));
		} catch (RuntimeException e) {
			log("a connection failed: " + e);
		} finally {
			wire.close();
			connections.decrementAndGet();
		}
	}

	private void serveLease(Wire wire, Map<String, Object> message) throws IOException {
		Lease.Request request = Lease.Request.from(message);
		Lease lease = scheduler.enqueue(request, new Lease.Listener() {
			@Override
			public void queued(Lease l, int position, String reason, List<String> running) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("type", "queued");
				m.put("id", l.id);
				m.put("position", position);
				m.put("reason", reason);
				m.put("running", running);
				trySend(wire, m);
			}

			@Override
			public void granted(Lease l) {
				// Off the scheduler's thread: starting the display can take a few seconds.
				grants.execute(() -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("type", "granted");
					m.put("id", l.id);
					if (l.request.hidden() && !l.request.play()) {
						try {
							Displays.Info display = displays.ensure();
							if (display != null) {
								l.display = display.name();
								m.put("display", display.name());
								m.put("displayKind", display.kind());
							} else {
								m.put("note", String.valueOf(displays.describe().get("note")));
							}
						} catch (RuntimeException e) {
							log("the hidden display failed: " + e);
							m.put("note", "the hidden display failed: " + e.getMessage());
						}
					}
					log("start #" + l.id + " " + l.describe() + (l.display != null ? " on " + l.display : ""));
					trySend(wire, m);
				});
			}
		});
		log("queue #" + lease.id + " " + lease.describe() + (request.play() ? " [playing]" : request.exclusive() ? " [alone]" : ""));
		try {
			while (true) {
				Map<String, Object> next = wire.read();
				if (next == null || "release".equals(next.get("type"))) break;
			}
		} finally {
			scheduler.release(lease);
			log("done #" + lease.id + " " + lease.describe());
		}
	}

	/**
	 * The status every second, for as long as the other side listens: the dashboard's live view. A connection that's open
	 * counts as something to do, so the daemon stays up while a dashboard is open.
	 */
	private void follow(Wire wire) throws IOException {
		while (!stopping) {
			wire.send(status());
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				return;
			}
		}
	}

		private static void trySend(Wire wire, Map<String, Object> message) {
		try {
			wire.send(message);
		} catch (IOException ignored) {
			// The client is gone; its lease is being released.
		}
	}

	private Map<String, Object> status() {
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("type", "status");
		s.put("version", Version.VERSION);
		s.put("build", BUILD);
		s.put("protocol", Wire.PROTOCOL);
		s.put("pid", ProcessHandle.current().pid());
		s.put("startedAt", startedAt);
		s.put("now", System.currentTimeMillis());
		s.putAll(scheduler.snapshot());
		for (Map<String, Object> running : Json.list(s, "running")) {
			long estimate = history.estimateMs(String.valueOf(running.get("project")), String.valueOf(running.get("task")));
			if (estimate > 0) running.put("estimateMs", estimate);
		}
		s.put("display", displays.describe());
		s.put("config", config.toMap());
		s.put("recent", history.last(5));
		s.put("watching", watcher.describe());
		s.put("testing", watcher.testing());
		s.put("runsFolder", KeptRuns.folder().toAbsolutePath().toString());
		return s;
	}
}
