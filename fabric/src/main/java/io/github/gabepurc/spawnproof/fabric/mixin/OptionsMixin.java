package io.github.gabepurc.spawnproof.fabric.mixin;

import io.github.gabepurc.spawnproof.fabric.LightGame;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Light test games, as the options load. Before Fabric's test framework's own injection (hence the priority under its
 * default 1000): that one snapshots the options and restores the snapshot before every test, so these have to be in it.
 */
@Mixin(value = Options.class, priority = 900)
abstract class OptionsMixin {
	@Inject(method = "<init>", at = @At("RETURN"))
	private void spawnproof$lightGame(CallbackInfo ci) {
		LightGame.apply((Options) (Object) this);
	}
}
