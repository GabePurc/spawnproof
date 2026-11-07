package io.github.gabepurc.spawnproof.fabric.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Test games never take the cursor: one that did locked the mouse to the game while tests ran, and moving it threw them off.
 * Tests turn the view by setting it (Fabric's {@code TestInput.lookAt}), so they don't need it.
 */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
	@Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
	private void spawnproof$keepCursor(CallbackInfo ci) {
		ci.cancel();
	}
}
