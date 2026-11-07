package io.github.gabepurc.spawnproof.fabric.mixin;

import io.github.gabepurc.spawnproof.fabric.Clock;
import net.minecraft.client.Minecraft;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * On the frame clock, the client sometimes blocks on the clock with no frames drawn (loading a world waits a moment that
 * way), and no frames would mean the clock never moves: each idle wait there counts as a frame. Also lets the server run its
 * tasks ({@code MinecraftServerMixin}).
 */
@Mixin(BlockableEventLoop.class)
public abstract class BlockableEventLoopMixin implements Clock.Tasks {
	@Shadow
	protected abstract void runAllTasks();

	@Override
	public void spawnproof$runAllTasks() {
		runAllTasks();
	}

	@Inject(method = "waitForTasks", at = @At("HEAD"))
	private void spawnproof$idleFrame(CallbackInfo ci) {
		if (Clock.FAST && (Object) this instanceof Minecraft) Clock.frame();
	}
}
