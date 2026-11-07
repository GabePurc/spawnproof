package io.github.gabepurc.spawnproof.test;

import io.github.gabepurc.spawnproof.fabric.Clock;

/**
 * The clock test games run on. A test game doesn't wait for real time: its clock counts drawn frames, each the time a frame
 * takes in a game at its default limit, so it runs as fast as the machine can draw. {@code -Dspawnproof.clock=real} runs it
 * at normal speed.
 *
 * <p>The game reads this clock through {@code Util.getNanos()}. Code that times itself on {@link System#nanoTime()} doesn't
 * see it: in a test game it would run at real speed while the game runs faster. Read {@link #nanos()} there instead (or
 * redirect the call in a test mixin).
 */
public final class GameClock {
	private GameClock() {}

	/** The game's clock in nanoseconds: frames in a test game, {@link System#nanoTime()} otherwise. */
	public static long nanos() {
		return Clock.nanos();
	}

	/** Whether this game runs on drawn frames, not real time. */
	public static boolean onFrames() {
		return Clock.FAST;
	}

	/** Client ticks since the game started. */
	public static long ticks() {
		return Clock.ticks;
	}
}
