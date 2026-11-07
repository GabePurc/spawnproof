package io.github.gabepurc.spawnproof.fabric.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.sdl.SDLStdinc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * On Linux, a test game with an X display uses X11, also when started from a Wayland session's shell. Otherwise it tries
 * Wayland, which fails with some drivers (NVIDIA on Ubuntu): it falls back to another graphics backend or crashes,
 * depending on its mods, so results would depend on where the command was typed. It also lets Spawnproof's hidden display,
 * an X display, take a game whose environment it can't change (Loom's production runs).
 */
@Mixin(RenderSystem.class)
abstract class RenderSystemMixin {
	@Inject(method = "initBackendSystem", at = @At("HEAD"))
	private static void spawnproof$useX11(CallbackInfoReturnable<?> cir) {
		if (System.getProperty("os.name").startsWith("Linux") && System.getenv("DISPLAY") != null) {
			SDLStdinc.SDL_unsetenv_unsafe("WAYLAND_DISPLAY");
			SDLStdinc.SDL_setenv_unsafe("XDG_SESSION_TYPE", "x11", 1);
		}
	}
}
