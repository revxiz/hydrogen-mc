package dev.hydrogen.mc.mixin;

import dev.hydrogen.mc.EntityCulling;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sub-pixel culling for entities on 1.20.x and 1.21.1.
 *
 * Hooked into the renderer rather than the dispatcher, so renderers that extend
 * the vanilla test keep their own rules: a leashed mob is still drawn while its
 * leash holder is on screen. Entities that opt out of culling (lightning, the
 * ender dragon, fishing lines) are never touched.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererCullMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private void hydrogen$subPixelCull(Entity entity, Frustum frustum, double camX, double camY, double camZ,
			CallbackInfoReturnable<Boolean> cir) {
		if (!entity.noCulling && EntityCulling.cull(entity, entity.getBoundingBoxForCulling(), camX, camY, camZ)) {
			cir.setReturnValue(false);
		}
	}
}
