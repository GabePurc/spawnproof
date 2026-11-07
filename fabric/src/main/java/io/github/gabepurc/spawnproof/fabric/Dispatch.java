package io.github.gabepurc.spawnproof.fabric;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The test run this game is one of, when Spawnproof splits a mod's tests across several games ({@code spawnproof test}, or
 * {@code -Pspawnproof.games}). The run starts each game with {@code SPAWNPROOF_DISPATCH} naming its socket. The game says
 * which tests it found, then asks for one test at a time until there are none left. Whichever game is free takes the next
 * one, so a game that starts late or stops early leaves its share to the others.
 *
 * <p>The game sends a JSON object per line; the run answers each {@code next} with a line of its own, {@code run <test>} or
 * {@code stop}.
 */
final class Dispatch implements AutoCloseable {
	private final SocketChannel channel;
	private final ByteBuffer in = ByteBuffer.allocate(4096).flip();
	final int game;

	private Dispatch(SocketChannel channel, int game) {
		this.channel = channel;
		this.game = game;
	}

	/** The run that started this game, or null for a game that runs its tests on its own. */
	static Dispatch fromEnvironment() {
		String socket = System.getenv("SPAWNPROOF_DISPATCH");
		if (socket == null || socket.isBlank()) return null;
		int game;
		try {
			game = Integer.parseInt(System.getenv().getOrDefault("SPAWNPROOF_GAME", "1"));
		} catch (NumberFormatException e) {
			game = 1;
		}
		try {
			SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
			channel.connect(UnixDomainSocketAddress.of(socket));
			return new Dispatch(channel, game);
		} catch (IOException e) {
			// Running every test on its own here would run them twice over, alongside the run's other games.
			throw new IllegalStateException("Spawnproof: this game was started by a test run that's gone (" + socket + "): " + e.getMessage(), e);
		}
	}

	/** Every test this game found, which of them its settings pick, and anything wrong with the run as a whole. */
	void hello(List<Candidate> all, List<Candidate> picked, List<String> problems, String blocked, Map<String, Object> run) throws IOException {
		List<Map<String, Object>> tests = new ArrayList<>();
		for (Candidate c : all) {
			Map<String, Object> t = new LinkedHashMap<>();
			t.put("name", c.name());
			t.put("class", c.className());
			t.put("mod", c.mod());
			t.put("kind", c.kind().label());
			t.put("runByDefault", c.runByDefault());
			t.put("picked", picked.contains(c));
			tests.add(t);
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", "hello");
		m.put("game", game);
		m.put("tests", tests);
		m.put("problems", problems);
		m.put("blocked", blocked);
		m.put("run", run);
		send(m);
	}

	/** The next test to run, or null when there are none left (or the run has gone away, which comes to the same). */
	String next() {
		try {
			send(Map.of("type", "next"));
			String answer = readLine();
			if (answer == null || !answer.startsWith("run ")) return null;
			return answer.substring(4);
		} catch (IOException e) {
			Runner.say("lost the test run (" + e.getMessage() + "), so this game stops here");
			return null;
		}
	}

	/** A test is done, its results in {@code part}. {@code leaving} says why this game can't take another, if it can't. */
	void done(String test, Path part, String leaving) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", "done");
		m.put("test", test);
		m.put("part", part == null ? null : part.toString());
		m.put("leaving", leaving);
		try {
			send(m);
		} catch (IOException e) {
			// The run reads the part from this game's folder when it's gone.
		}
	}

	private synchronized void send(Map<String, Object> message) throws IOException {
		ByteBuffer out = ByteBuffer.wrap((Json.write(message) + "\n").getBytes(StandardCharsets.UTF_8));
		while (out.hasRemaining()) channel.write(out);
	}

	private String readLine() throws IOException {
		ByteArrayOutputStream line = new ByteArrayOutputStream();
		while (true) {
			while (in.hasRemaining()) {
				byte b = in.get();
				if (b == '\n') return line.toString(StandardCharsets.UTF_8);
				line.write(b);
			}
			in.clear();
			int n = channel.read(in);
			in.flip();
			if (n < 0) return null;
		}
	}

	@Override
	public void close() {
		try {
			channel.close();
		} catch (IOException ignored) {
			// Closing anyway.
		}
	}
}
