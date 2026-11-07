package io.github.gabepurc.spawnproof.fabric;

import io.github.gabepurc.spawnproof.fabric.mixin.UtilAccessor;
import net.minecraft.util.Util;

import java.util.Locale;

/**
 * Test games spend most of a run waiting: a tick is 50 ms of the game's clock however little of that it needs. On a frame
 * clock ({@code spawnproof.clock=frames}, the default) the game's clock counts drawn frames instead of real time, each frame
 * the time it takes in a game at its default limit. So a tick gets the same frames on a loaded machine or an idle one, and a
 * run goes as fast as the machine can draw them. Ticks are still ticks, in lockstep with the server, which stops waiting for
 * the next one once its tasks are done ({@code MinecraftServerMixin}), as in a {@code /tick sprint}.
 *
 * <p>From Horsing Around's client tests, where it made runs several times faster.
 */
public final class Clock {
	public static final boolean FAST = Settings.TEST_RUN && Settings.FAST_CLOCK;
	private static final long NANOS_PER_TICK = 50_000_000L;
	/**
	 * 5.9 frames a tick, what a game at its default limit measures: not a whole number, so frames fall at different moments
	 * of each tick and in time cover all of it, as they do in play.
	 */
	private static final long FRAME_NANOS_TIMES_59 = 10L * NANOS_PER_TICK;
	/** A game drawing no frames for this long is stuck: it says where, and stops. */
	private static final long STALL_MILLIS = 120_000L;

	public static volatile long ticks;
	private static long start;
	private static volatile long frames;

	private Clock() {}

	/** Server event loops get this from {@code BlockableEventLoopMixin}: the method is protected, on the server's superclass. */
	public interface Tasks {
		void spawnproof$runAllTasks();
	}

	/** After the game has set its own clock, which it does while it starts up; carries on from that clock's reading. */
	public static void install() {
		if (!FAST) return;
		start = Util.getNanos();
		UtilAccessor.spawnproof$setTimeSource(Clock::nanos);
		watchForStall();
	}

	/** A game whose frames stop waits for ever with its window up: have it say where it stuck and leave, failing the run. */
	private static void watchForStall() {
		Thread watch = new Thread(() -> {
			long seen = -1L;
			while (true) {
				try {
					Thread.sleep(STALL_MILLIS);
				} catch (InterruptedException e) {
					return;
				}
				if (frames == seen) {
					System.out.println("[spawnproof] no frame for " + STALL_MILLIS / 1000L + " s, so the game is stopped. Threads:");
					Thread.getAllStackTraces().forEach((thread, stack) -> {
						System.out.println(thread.getName());
						for (StackTraceElement line : stack) System.out.println("\tat " + line);
					});
					Runner.stalled(STALL_MILLIS);
					Runtime.getRuntime().halt(1);
				}
				seen = frames;
			}
		}, "Spawnproof stall watch");
		watch.setDaemon(true);
		watch.start();
	}

	/** Called by the render thread alone. */
	public static void frame() {
		frames = frames + 1;
	}

	public static long nanos() {
		return FAST ? start + frames * FRAME_NANOS_TIMES_59 / 59L : System.nanoTime();
	}

	static String name() {
		return FAST ? "frame clock" : "real time";
	}

	/** "frame clock, 83 ticks a second": ticks against real time, since a moment. */
	static String pace(long sinceMillis, long ticksThen) {
		return String.format(Locale.ROOT, "%s, %.0f ticks a second", name(), ticksPerSecond(sinceMillis, ticksThen));
	}

	static double ticksPerSecond(long sinceMillis, long ticksThen) {
		double seconds = (System.currentTimeMillis() - sinceMillis) / 1000.0;
		return seconds > 0 ? (ticks - ticksThen) / seconds : 0;
	}
}
