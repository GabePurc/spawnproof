package io.github.gabepurc.spawnproof.fabric.mixin;

import io.github.gabepurc.spawnproof.fabric.Clock;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * On the frame clock, the client's frames move the clock, and the test framework holds the client until the server reaches
 * each tick, so a server waiting on that clock would wait for ever. It runs its tasks, chunks' included, and goes on, as in
 * a {@code /tick sprint}.
 */
@Mixin(MinecraftServer.class)
abstract class MinecraftServerMixin {
	@Unique
	private boolean spawnproof$running;

	@Inject(method = "waitUntilNextTick", at = @At("HEAD"), cancellable = true)
	private void spawnproof$noWait(CallbackInfo ci) {
		if (Clock.FAST) {
			spawnproof$running = true;
			((Clock.Tasks) (Object) this).spawnproof$runAllTasks();
			spawnproof$running = false;
			Thread.yield();
			ci.cancel();
		}
	}

	@Inject(method = "haveTime", at = @At("HEAD"), cancellable = true)
	private void spawnproof$haveTime(CallbackInfoReturnable<Boolean> cir) {
		if (Clock.FAST) cir.setReturnValue(spawnproof$running);
	}
}
