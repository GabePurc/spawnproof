package io.github.gabepurc.spawnproof.fabric;

/**
 * Thrown by {@code require} and {@code fail}: the test stops there, failed. An error, not an exception, so a test's own
 * {@code catch (Exception e)} doesn't swallow it.
 */
final class TestStopped extends AssertionError {
	TestStopped(String reason) {
		super(reason);
	}
}
