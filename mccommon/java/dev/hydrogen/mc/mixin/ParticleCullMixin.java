package dev.hydrogen.mc.mixin;

import dev.hydrogen.core.Hydrogen;
import dev.hydrogen.core.chunk.ConePriority;
import dev.hydrogen.core.hw.Tuning;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Collision culling for particles the camera cannot see.
 *
 * Vanilla runs a block collision sweep for every live particle every tick,
 * visible or not. For a hidden particle, {@code hasPhysics} is switched off for
 * the length of one {@code move} call, so vanilla still integrates its position
 * and it keeps drifting and ageing normally; only the sweep is skipped. Turning
 * round shows particles where they would have been, rather than frozen in place.
 *
 * Only particles behind the camera are culled by default. A particle in front of
 * you but currently off screen may well be moving into view.
 */
@Mixin(Particle.class)
public abstract class ParticleCullMixin {
	@Shadow
	protected double x;

	@Shadow
	protected double y;

	@Shadow
	protected double z;

	@Shadow
	protected boolean hasPhysics;

	@Unique
	private boolean hydrogen$suspended;

	@Inject(method = "move(DDD)V", at = @At("HEAD"))
	private void hydrogen$skipHiddenCollision(double dx, double dy, double dz, CallbackInfo ci) {
		if (!hasPhysics) {
			return;
		}

		Hydrogen h = Hydrogen.get();

		if (h == null) {
			return;
		}

		Tuning t = h.tuning();

		if (!t.enabled() || !t.particleCull()) {
			return;
		}

		ConePriority cam = h.cone();

		if (cam.distanceSqTo(x, y, z) < t.particleMinDistanceSq()) {
			return;
		}

		if (t.particleBehindOnly() && !cam.behindCamera(x, y, z)) {
			return;
		}

		hasPhysics = false;
		hydrogen$suspended = true;
	}

	@Inject(method = "move(DDD)V", at = @At("RETURN"))
	private void hydrogen$restorePhysics(double dx, double dy, double dz, CallbackInfo ci) {
		if (hydrogen$suspended) {
			hasPhysics = true;
			hydrogen$suspended = false;
		}
	}
}
