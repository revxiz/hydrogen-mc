package dev.hydrogen.mc.mixin;

import dev.hydrogen.core.Hydrogen;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies the VRAM eviction cap to the render distance the game actually uses,
 * the same way a server's view distance limit is applied. The player's own
 * setting in options.txt is never touched, so nothing is lost if the game closes
 * while the cap is active.
 */
@Mixin(Options.class)
public abstract class RenderDistanceCapMixin {
	@Inject(method = "getEffectiveRenderDistance", at = @At("RETURN"), cancellable = true)
	private void hydrogen$applyCap(CallbackInfoReturnable<Integer> cir) {
		Hydrogen h = Hydrogen.get();

		if (h == null) {
			return;
		}

		int cap = h.eviction().renderDistanceCap();

		if (cap > 0 && cir.getReturnValueI() > cap) {
			cir.setReturnValue(cap);
		}
	}
}
