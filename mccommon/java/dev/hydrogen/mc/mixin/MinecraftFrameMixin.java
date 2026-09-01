package dev.hydrogen.mc.mixin;

import dev.hydrogen.core.Hydrogen;
import dev.hydrogen.core.cpu.ThreadRole;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frame boundary and render thread binding.
 *
 * {@code runTick} is the whole client frame including the swap, so wrapping it
 * gives the wall time the player actually waits. Both hooks are identical on
 * every supported version.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftFrameMixin {
	@Unique
	private long hydrogen$frameStart;

	@Inject(method = "run", at = @At("HEAD"))
	private void hydrogen$bindRenderThread(CallbackInfo ci) {
		Hydrogen h = Hydrogen.get();

		if (h != null) {
			h.bindCurrentThread(ThreadRole.RENDER);
		}
	}

	@Inject(method = "runTick(Z)V", at = @At("HEAD"))
	private void hydrogen$frameBegin(boolean renderLevel, CallbackInfo ci) {
		hydrogen$frameStart = System.nanoTime();
	}

	@Inject(method = "runTick(Z)V", at = @At("RETURN"))
	private void hydrogen$frameEnd(boolean renderLevel, CallbackInfo ci) {
		Hydrogen h = Hydrogen.get();

		if (h != null && hydrogen$frameStart != 0L) {
			h.onFrameEnd(System.nanoTime() - hydrogen$frameStart);
		}
	}
}
