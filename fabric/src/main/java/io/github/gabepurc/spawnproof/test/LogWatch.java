package io.github.gabepurc.spawnproof.test;

import java.util.List;

/**
 * The game log's warnings and errors, from when it was started ({@link TestContext#watchLog()}) until it's closed: from the
 * client and from any server in the same game (singleplayer's, or a dedicated one a test starts). Each line reads
 * {@code WARN [logger] message}, with the exception's text if one was logged.
 */
public interface LogWatch extends AutoCloseable {
	/** Every warning and error so far, less the ignored ones. */
	List<String> lines();

	/** The lines so far containing any of {@code needles}. */
	List<String> matching(String... needles);

	/** Leaves out lines containing any of these from now on: another mod's online service failing, say. */
	LogWatch ignore(String... needles);

	/** Forgets the lines so far. */
	void clear();

	@Override
	void close();
}
