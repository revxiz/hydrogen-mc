package dev.hydrogen.mc.mixin;

import dev.hydrogen.core.Hydrogen;
import dev.hydrogen.core.chunk.ConePriority;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Physics culling for particles the camera cannot see.
 *
 * Vanilla runs a block collision sweep for every live particle every tick,
 * visible or not. Behind a wall or behind your head that work changes nothing on
 * screen. Skipping {@code move} drops the collision sweep and the position
 * integration while {@code tick} still runs, so particles keep ageing and still
 * expire exactly on schedule.
 *
 * Only particles behind the camera are culled by default. A particle in front of
 * you but currently off screen may well be moving into view, and freezing that
 * one would be visible.
 */
@Mixin(Particle.class)
public abstract class ParticleCullMixin {
	@Shadow
	protected double x;

	@Shadow
	protected double y;

	@Shadow
	protected double z;

	@Inject(method = "move(DDD)V", at = @At("HEAD"), cancellable = true)
	private void hydrogen$skipHiddenPhysics(double dx, double dy, double dz, CallbackInfo ci) {
		Hydrogen h = Hydrogen.get();

		if (h == null || !h.enabled() || !h.config().bool("particle.cullPhysics")) {
			return;
		}

		ConePriority cam = h.cone();
		double minDistance = h.config().number("particle.minCullDistance",
				Math.max(12.0D, h.budget().subPixelMinDistance() * 0.5D));

		if (cam.distanceSqTo(x, y, z) < minDistance * minDistance) {
			return;
		}

		if (h.config().bool("particle.cullBehindOnly") && !cam.behindCamera(x, y, z)) {
			return;
		}

		ci.cancel();
	}
}
