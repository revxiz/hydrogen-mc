package dev.hydrogen.mc.mixin;

import dev.hydrogen.core.Hydrogen;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sub-pixel culling for block entity renderers.
 *
 * Vanilla already frustum-culls block entities at section granularity, so the
 * win here is the distant end: a chest room seen from 100 blocks away still
 * submits a full animated model per chest. Anything projecting to less than a
 * physical pixel is skipped.
 *
 * The block entity's own state is untouched. This only decides whether a frame
 * draws it. The descriptor of shouldRender is identical on all four branches.
 */
@Mixin(BlockEntityRenderer.class)
public interface BlockEntityCullMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private void hydrogen$subPixelCull(BlockEntity entity, Vec3 cameraPos,
			CallbackInfoReturnable<Boolean> cir) {
		Hydrogen h = Hydrogen.get();

		if (h == null || !h.enabled() || !h.config().bool("cull.blockEntities.enabled")) {
			return;
		}

		if (!h.culler().enabled()) {
			return;
		}

		double dx = entity.getBlockPos().getX() + 0.5D - cameraPos.x;
		double dy = entity.getBlockPos().getY() + 0.5D - cameraPos.y;
		double dz = entity.getBlockPos().getZ() + 0.5D - cameraPos.z;
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

		// One block covers most block entities; banners and beds are close enough.
		if (h.culler().shouldCull(1.0D, distance)) {
			cir.setReturnValue(false);
		}
	}
}
