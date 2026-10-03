package dev.hydrogen.mc.mixin;

import dev.hydrogen.core.Hydrogen;
import dev.hydrogen.mc.DeferredSections;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Motion-vector chunk deferral.
 *
 * A distant section behind the player does not need to be remeshed during a
 * frame that is already over budget. The request is remembered and replayed once
 * frames recover, so nothing is lost, and important rebuilds are never touched.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererDeferMixin {
	@Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"), cancellable = true)
	private void hydrogen$deferBehindPlayer(int x, int y, int z, boolean important, CallbackInfo ci) {
		Hydrogen h = Hydrogen.get();

		if (h == null || !h.enabled() || important) {
			return;
		}

		double cx = (x << 4) + 8;
		double cy = (y << 4) + 8;
		double cz = (z << 4) + 8;

		if (h.cone().shouldDefer(cx, cy, cz, h.lastFrameMs()) && DeferredSections.offer(x, y, z)) {
			ci.cancel();
		}
	}
}
