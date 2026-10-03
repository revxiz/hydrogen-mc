package dev.hydrogen.mc.mixin;

import dev.hydrogen.core.Hydrogen;
import dev.hydrogen.core.hw.Tuning;
import net.minecraft.core.BlockPos;
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
 * Vanilla already frustum-culls block entities at section granularity, so this
 * only ever matters at the far end of a large view distance. Anything projecting
 * to less than a physical pixel is skipped. Renderers that override
 * shouldRender, such as beacons, keep their own logic.
 *
 * The block entity's own state is untouched. This only decides whether a frame
 * draws it. The descriptor of shouldRender is identical on every branch.
 */
@Mixin(BlockEntityRenderer.class)
public interface BlockEntityCullMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private void hydrogen$subPixelCull(BlockEntity entity, Vec3 cameraPos,
			CallbackInfoReturnable<Boolean> cir) {
		Hydrogen h = Hydrogen.get();

		if (h == null) {
			return;
		}

		Tuning t = h.tuning();

		if (!t.enabled() || !t.blockEntityCull() || !t.subPixel()) {
			return;
		}

		BlockPos pos = entity.getBlockPos();
		double dx = pos.getX() + 0.5D - cameraPos.x;
		double dy = pos.getY() + 0.5D - cameraPos.y;
		double dz = pos.getZ() + 0.5D - cameraPos.z;

		// One block covers most block entities; banners and beds are close enough.
		if (h.culler().shouldCullSq(1.0D, dx * dx + dy * dy + dz * dz)) {
			cir.setReturnValue(false);
		}
	}
}
