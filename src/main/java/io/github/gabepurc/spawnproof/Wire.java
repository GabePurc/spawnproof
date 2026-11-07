package io.github.gabepurc.spawnproof;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/**
 * One JSON object per line over a Unix domain socket. Reads and writes go straight to the channel (not through
 * Channels.newInputStream, which locks writes out while a read waits), so one thread can wait for messages while another sends.
 */
public final class Wire implements Closeable {
	/** Bumped when a message changes in a way an older daemon or client would misread. */
	public static final int PROTOCOL = 1;

	private final SocketChannel channel;
	private final ByteBuffer in = ByteBuffer.allocate(8192).flip();
	private final ByteArrayOutputStream line = new ByteArrayOutputStream();
	private final Object writeLock = new Object();

	public Wire(SocketChannel channel) {
		this.channel = channel;
	}

	public static Wire connect(Path socket) throws IOException {
		SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
		try {
			channel.connect(UnixDomainSocketAddress.of(socket));
		} catch (IOException | RuntimeException e) {
			channel.close();
			throw e;
		}
		return new Wire(channel);
	}

	/** The next message, or null once the other side has closed. */
	public Map<String, Object> read() throws IOException {
		while (true) {
			while (in.hasRemaining()) {
				byte b = in.get();
				if (b == '\n') {
					String text = line.toString(StandardCharsets.UTF_8);
					line.reset();
					if (!text.isBlank()) return Json.parseObject(text);
					continue;
				}
				line.write(b);
				if (line.size() > (1 << 20)) throw new IOException("A message over 1 MB: not from Spawnproof");
			}
			in.clear();
			int n = channel.read(in);
			in.flip();
			if (n < 0) return null;
		}
	}

	public void send(Map<String, ?> message) throws IOException {
		ByteBuffer out = ByteBuffer.wrap((Json.write(message) + "\n").getBytes(StandardCharsets.UTF_8));
		synchronized (writeLock) {
			while (out.hasRemaining()) channel.write(out);
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
