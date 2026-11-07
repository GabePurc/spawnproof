package io.github.gabepurc.spawnproof.fabric.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import io.github.gabepurc.spawnproof.fabric.Clock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * On the frame clock: the game holds its frames down when it sees it's minimized or nobody has touched it for a while, and
 * frames are this clock's time: 10 a second leaves about 2 ticks a second. So test games run at full speed with their window
 * minimized, covered, or on another workspace.
 */
@Mixin(FramerateLimitTracker.class)
abstract class FramerateLimitTrackerMixin {
	@Inject(method = "getThrottleReason", at = @At("HEAD"), cancellable = true)
	private void spawnproof$neverThrottle(CallbackInfoReturnable<FramerateLimitTracker.FramerateThrottleReason> cir) {
		if (Clock.FAST) cir.setReturnValue(FramerateLimitTracker.FramerateThrottleReason.NONE);
	}
}
