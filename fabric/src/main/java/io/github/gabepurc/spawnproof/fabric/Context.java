package io.github.gabepurc.spawnproof.fabric;

import io.github.gabepurc.spawnproof.test.LogWatch;
import io.github.gabepurc.spawnproof.test.TestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** The {@link TestContext} a test runs with. */
final class Context implements TestContext {
	private final ClientGameTestContext game;
	private final TestRecord record;
	private final Results results;
	private final LogCapture logs;

	Context(ClientGameTestContext game, TestRecord record, Results results, LogCapture logs) {
		this.game = game;
		this.record = record;
		this.results = results;
		this.logs = logs;
	}

	@Override
	public ClientGameTestContext game() {
		return game;
	}

	@Override
	public String name() {
		return record.test.name();
	}

	@Override
	public void section(String name) {
		record.section(name);
	}

	@Override
	public boolean check(String name, boolean ok) {
		return record.check(name, ok, null, null, null, null, null, null, false);
	}

	@Override
	public boolean check(String name, boolean ok, String detailIfFailed) {
		return record.check(name, ok, detailIfFailed, null, null, null, null, null, false);
	}

	@Override
	public boolean check(String name, double value, double min, double max) {
		return record.check(name, value >= min && value <= max, null, value, min, max, null, null, false);
	}

	@Override
	public boolean checkEqual(String name, Object expected, Object actual) {
		return record.check(name, Objects.equals(expected, actual), null, null, null, null, expected, actual, false);
	}

	@Override
	public void require(String name, boolean ok) {
		if (!record.check(name, ok, null, null, null, null, null, null, true)) throw new TestStopped("required check failed: " + name);
	}

	@Override
	public void fail(String reason) {
		throw new TestStopped(reason);
	}

	@Override
	public void note(String format, Object... args) {
		record.note(args.length == 0 ? format : String.format(Locale.ROOT, format, args));
	}

	@Override
	public Path screenshot(String name) {
		Path dir = results.dir().resolve("screenshots").resolve(record.test.name());
		Path saved = game.takeScreenshot(TestScreenshotOptions.of(fileName(name)).disableCounterPrefix().withDestinationDir(dir));
		record.screenshot(name, results.relative(saved));
		return saved;
	}

	@Override
	public Path file(String name) {
		Path path = results.dir().resolve("files").resolve(record.test.name()).resolve(fileName(name));
		try {
			Files.createDirectories(path.getParent());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		record.file(name, results.relative(path));
		return path;
	}

	@Override
	public LogWatch watchLog() {
		return logs.watch();
	}

	/** A name as a file name: anything but letters, digits, dots, dashes and underscores becomes an underscore. */
	private static String fileName(String name) {
		String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
		return safe.isEmpty() ? "_" : safe;
	}
}
