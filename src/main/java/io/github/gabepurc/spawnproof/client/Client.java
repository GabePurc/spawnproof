package io.github.gabepurc.spawnproof.client;

import io.github.gabepurc.spawnproof.Dirs;
import io.github.gabepurc.spawnproof.Json;
import io.github.gabepurc.spawnproof.Version;
import io.github.gabepurc.spawnproof.Wire;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Talks to the daemon: one request per connection, except a lease, which holds its connection until the game is done. */
public final class Client {
	private Client() {}

	/** A connection with the daemon's welcome read. */
	public static final class Connection implements Closeable {
		final Wire wire;
		public final String version;
		public final long protocol;

		Connection(Wire wire, Map<String, Object> welcome) {
			this.wire = wire;
			this.version = Json.str(welcome, "version");
			this.protocol = Json.num(welcome, "protocol", -1);
		}

		@Override
		public void close() {
			wire.close();
		}
	}

	/** A game slot, held until closed. */
	public static final class Lease implements AutoCloseable {
		private final Connection connection;
		public final long id;
		/** The hidden display to draw on (":5"), or null to show on the desktop. */
		public final String display;
		public final String displayKind;
		/** Why there's no hidden display, when there isn't. */
		public final String note;

		Lease(Connection connection, Map<String, Object> granted) {
			this.connection = connection;
			this.id = Json.num(granted, "id", -1);
			this.display = Json.str(granted, "display");
			this.displayKind = Json.str(granted, "displayKind");
			this.note = Json.str(granted, "note");
		}

		@Override
		public void close() {
			try {
				connection.wire.send(Map.of("type", "release"));
			} catch (IOException ignored) {
				// Closing the connection releases it anyway.
			}
			connection.close();
		}
	}

	/** Connects, starting the daemon if none answers and start is set. */
	public static Connection connect(boolean start) throws IOException {
		try {
			return open();
		} catch (IOException first) {
			if (!start) throw first;
		}
		DaemonStarter.start();
		long deadline = System.currentTimeMillis() + 20_000;
		IOException last = null;
		while (System.currentTimeMillis() < deadline) {
			try {
				return open();
			} catch (IOException e) {
				last = e;
				sleep(100);
			}
		}
		throw new IOException("Spawnproof's daemon didn't start; see " + Dirs.daemonLog(), last);
	}

	private static Connection open() throws IOException {
		if (!Files.exists(Dirs.socket())) throw new IOException("no daemon socket at " + Dirs.socket());
		Wire wire = Wire.connect(Dirs.socket());
		try {
			Map<String, Object> welcome = wire.read();
			if (welcome == null || !"welcome".equals(welcome.get("type"))) throw new IOException("the daemon didn't say hello");
			return new Connection(wire, welcome);
		} catch (IOException | RuntimeException e) {
			wire.close();
			throw e;
		}
	}

	/** Sends one request and returns the reply; an error reply becomes an IOException. */
	public static Map<String, Object> call(Map<String, Object> request, boolean start) throws IOException {
		try (Connection c = connect(start)) {
			return call(c, request);
		}
	}

	private static Map<String, Object> call(Connection c, Map<String, Object> request) throws IOException {
		c.wire.send(request);
		Map<String, Object> reply = c.wire.read();
		if (reply == null) throw new IOException("the daemon closed the connection");
		if ("error".equals(reply.get("type"))) throw new IOException(Json.str(reply, "message"));
		return reply;
	}

	/** Waits for a game slot. progress hears where in line the game is whenever that changes. */
	public static Lease acquire(Map<String, Object> request, Consumer<String> progress) throws IOException {
		Connection c = connectCurrent(progress);
		try {
			Map<String, Object> message = new LinkedHashMap<>(request);
			message.put("type", "acquire");
			c.wire.send(message);
			String told = null;
			while (true) {
				Map<String, Object> m = c.wire.read();
				if (m == null) throw new IOException("Spawnproof's daemon closed the connection");
				String type = String.valueOf(m.get("type"));
				if (type.equals("granted")) return new Lease(c, m);
				if (type.equals("error")) throw new IOException(Json.str(m, "message"));
				if (type.equals("queued")) {
					String text = Text.queued(m);
					if (!text.equals(told)) progress.accept(text);
					told = text;
				}
			}
		} catch (IOException | RuntimeException e) {
			c.close();
			throw e;
		}
	}

	/**
	 * A connection to a daemon of this version. A daemon of another version is replaced once it has nothing running; while
	 * it does, it's used as it is if it speaks the same protocol, and waited for if not.
	 */
	static Connection connectCurrent(Consumer<String> progress) throws IOException {
		boolean told = false;
		for (int attempt = 0; ; attempt++) {
			Connection c = connect(true);
			if (Version.VERSION.equals(c.version) || attempt >= 20) return c;
			Map<String, Object> status;
			try (c) {
				status = call(c, Map.of("type", "status"));
			}
			if (idle(status)) {
				try {
					call(Map.of("type", "stop"), false);
					waitForExit();
				} catch (IOException e) {
					// It got busy, or another client replaced it first: look again.
				}
				continue;
			}
			if (Json.num(status, "protocol", -1) == Wire.PROTOCOL) return connect(false);
			if (!told) progress.accept("waiting for Spawnproof " + Json.str(status, "version") + " to finish its games, to replace it with " + Version.VERSION);
			told = true;
			sleep(5_000);
		}
	}

	private static boolean idle(Map<String, Object> status) {
		for (String key : List.of("running", "waiting", "playing")) if (!Json.list(status, key).isEmpty()) return false;
		return true;
	}

	private static void waitForExit() throws java.io.InterruptedIOException {
		long deadline = System.currentTimeMillis() + 10_000;
		while (System.currentTimeMillis() < deadline) {
			try (Connection ignored = open()) {
				sleep(100);
			} catch (IOException gone) {
				return;
			}
		}
	}

	private static void sleep(long ms) throws java.io.InterruptedIOException {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new java.io.InterruptedIOException("cancelled");
		}
	}
}
