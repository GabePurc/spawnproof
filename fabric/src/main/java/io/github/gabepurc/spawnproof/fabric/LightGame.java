package io.github.gabepurc.spawnproof.fabric;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.Options;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.sounds.SoundSource;

/**
 * Test games make no sound, and run light: the lowest graphics settings ({@code spawnproof.graphics=normal} keeps the usual
 * ones, for screenshots people will see). Applied as the options load, before anything else does.
 */
public final class LightGame {
	private LightGame() {}

	public static void apply(Options options) {
		if (!Settings.TEST_RUN) return;
		if (Clock.FAST) {
			// (Limits on the real clock only hold frames back. Vsync is worse: with the window out of sight the desktop may
			// show so few frames that the game does a few a second, spinning in the graphics driver.)
			options.framerateLimit().set(260);
			options.enableVsync().set(false);
		}
		// Every test game, whatever else it runs with: nobody wants them heard.
		options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
		if (!Settings.LOW_GRAPHICS) return;
		options.enableVsync().set(false);
		// (Render distance stays at the test framework's: tests build their scenes by command, which needs the chunks.)
		options.simulationDistance().set(5);
		options.entityDistanceScaling().set(0.5);
		options.mipmapLevels().set(0);
		options.maxAnisotropyBit().set(1); // (The lowest it takes: 0 logs an error.)
		options.biomeBlendRadius().set(0);
		options.ambientOcclusion().set(false);
		options.entityShadows().set(false);
		options.improvedTransparency().set(false);
		options.cutoutLeaves().set(false);
		options.particles().set(ParticleStatus.MINIMAL);
		options.cloudStatus().set(CloudStatus.OFF);
		options.weatherRadius().set(3);
		options.menuBackgroundBlurriness().set(0);
		options.chunkSectionFadeInTime().set(0.0);
	}
}
