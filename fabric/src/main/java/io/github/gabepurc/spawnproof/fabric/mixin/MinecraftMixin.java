package io.github.gabepurc.spawnproof.fabric.mixin;

import io.github.gabepurc.spawnproof.fabric.Clock;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Starts the frame clock once the game has set the clock it runs on, and tells it of each frame and tick. */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Inject(method = "<init>", at = @At("RETURN"))
	private void spawnproof$installClock(CallbackInfo ci) {
		Clock.install();
	}

	@Inject(method = "runTick", at = @At("HEAD"))
	private void spawnproof$frame(CallbackInfo ci) {
		Clock.frame();
	}

	@Inject(method = "tick", at = @At("HEAD"))
	private void spawnproof$tick(CallbackInfo ci) {
		Clock.ticks++;
	}
}
