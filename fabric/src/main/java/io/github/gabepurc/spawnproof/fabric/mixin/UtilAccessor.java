package io.github.gabepurc.spawnproof.fabric.mixin;

import net.minecraft.util.TimeSource;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The game's clock, which everything but the frame limiter reads ({@code Clock}). */
@Mixin(Util.class)
public interface UtilAccessor {
	@Accessor("timeSource")
	static void spawnproof$setTimeSource(TimeSource.NanoTimeSource source) {
		throw new AssertionError();
	}
}
