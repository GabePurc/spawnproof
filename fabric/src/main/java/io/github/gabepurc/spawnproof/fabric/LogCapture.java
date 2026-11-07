package io.github.gabepurc.spawnproof.fabric;

import io.github.gabepurc.spawnproof.test.LogWatch;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Warnings and errors from the whole game's log, the client's and any server's in it: each running test keeps its own (in
 * its results), and {@link LogWatch}es collect them for a test to check. From Horsing Around's client tests, where the
 * server correcting a rider's moves ("moved wrongly", which a player sees as rubber-banding) failed a ride.
 */
final class LogCapture extends AbstractAppender {
	private final Results results;
	private final List<Watch> watches = new CopyOnWriteArrayList<>();

	private LogCapture(Results results) {
		super("spawnproof-log-capture", null, null, true, Property.EMPTY_ARRAY);
		this.results = results;
	}

	static LogCapture install(Results results) {
		LogCapture capture = new LogCapture(results);
		capture.start();
		LoggerContext context = (LoggerContext) LogManager.getContext(false);
		Configuration config = context.getConfiguration();
		config.getRootLogger().addAppender(capture, Level.WARN, null);
		context.updateLoggers();
		return capture;
	}

	@Override
	public void append(LogEvent event) {
		if (!event.getLevel().isMoreSpecificThan(Level.WARN)) return;
		String line = event.getLevel() + " [" + event.getLoggerName() + "] " + event.getMessage().getFormattedMessage();
		Throwable thrown = event.getThrown();
		if (thrown != null) line += " " + thrown;
		TestRecord test = results.current();
		if (test != null) test.logLine(line);
		for (Watch w : watches) w.add(line);
	}

	LogWatch watch() {
		Watch w = new Watch();
		watches.add(w);
		return w;
	}

	private final class Watch implements LogWatch {
		private final List<String> lines = new ArrayList<>();
		private final List<String> ignored = new CopyOnWriteArrayList<>();

		void add(String line) {
			for (String needle : ignored) if (line.contains(needle)) return;
			synchronized (lines) {
				lines.add(line);
			}
		}

		@Override
		public List<String> lines() {
			synchronized (lines) {
				return List.copyOf(lines);
			}
		}

		@Override
		public List<String> matching(String... needles) {
			List<String> out = new ArrayList<>();
			for (String line : lines()) {
				for (String needle : needles) {
					if (line.contains(needle)) {
						out.add(line);
						break;
					}
				}
			}
			return out;
		}

		@Override
		public LogWatch ignore(String... needles) {
			ignored.addAll(List.of(needles));
			return this;
		}

		@Override
		public void clear() {
			synchronized (lines) {
				lines.clear();
			}
		}

		@Override
		public void close() {
			watches.remove(this);
		}
	}
}
