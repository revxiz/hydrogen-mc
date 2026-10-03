package dev.hydrogen.mc.mixin;

import dev.hydrogen.mc.EntityCulling;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sub-pixel culling for entities. Since 1.21.9 the culling box and the opt-out
 * live on the renderer, so both are read from there.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererCullMixin {
	@Shadow
	protected abstract boolean affectedByCulling(Entity entity);

	@Shadow
	protected abstract AABB getBoundingBoxForCulling(Entity entity);

	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private void hydrogen$subPixelCull(Entity entity, Frustum frustum, double camX, double camY, double camZ,
			CallbackInfoReturnable<Boolean> cir) {
		if (affectedByCulling(entity) && EntityCulling.cull(entity, getBoundingBoxForCulling(entity), camX, camY, camZ)) {
			cir.setReturnValue(false);
		}
	}
}
