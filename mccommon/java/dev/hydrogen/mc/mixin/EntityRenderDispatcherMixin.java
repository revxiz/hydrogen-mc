package dev.hydrogen.mc.mixin;

import dev.hydrogen.core.Hydrogen;
import dev.hydrogen.core.cull.SubPixelCuller;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sub-pixel geometry pruning for entities.
 *
 * Runs before the frustum test, so anything rejected here costs no model setup,
 * no buffer writes and no draw call. The minimum distance is always well above
 * arm's length, so the player and any ridden vehicle are never candidates.
 *
 * The signature of shouldRender is byte-identical on 1.20.1 through 26.x.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private <E extends Entity> void hydrogen$subPixelCull(E entity, Frustum frustum,
			double camX, double camY, double camZ, CallbackInfoReturnable<Boolean> cir) {
		Hydrogen h = Hydrogen.get();

		if (h == null || !h.enabled()) {
			return;
		}

		SubPixelCuller culler = h.culler();

		if (!culler.enabled()) {
			return;
		}

		double dx = entity.getX() - camX;
		double dy = entity.getY() - camY;
		double dz = entity.getZ() - camZ;
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
		double size = Math.max(entity.getBbHeight(), entity.getBbWidth());

		if (culler.shouldCull(size, distance)) {
			cir.setReturnValue(false);
		}
	}
}
