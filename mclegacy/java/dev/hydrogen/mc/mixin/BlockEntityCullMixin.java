package dev.hydrogen.mc.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.hydrogen.core.Hydrogen;
import dev.hydrogen.core.hw.Tuning;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sub-pixel culling for block entity renderers, 1.20.1 and 1.21.1.
 *
 * Vanilla already frustum-culls block entities at section granularity, so this
 * only ever matters at the far end of a large view distance. Anything projecting
 * to less than a physical pixel is skipped.
 *
 * Hooks the dispatcher rather than the renderer interface: Forge 47 ships Mixin
 * 0.8.5, which cannot inject into an interface. Renderers that draw past their
 * own block (beacon beams, structure block outlines, end gateways) say so through
 * shouldRenderOffScreen and are never culled here.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityCullMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void hydrogen$subPixelCull(BlockEntity entity, float partialTick, PoseStack pose,
			MultiBufferSource buffers, CallbackInfo ci) {
		Hydrogen h = Hydrogen.get();

		if (h == null) {
			return;
		}

		Tuning t = h.tuning();

		if (!t.enabled() || !t.blockEntityCull() || !t.subPixel()) {
			return;
		}

		BlockEntityRenderDispatcher self = (BlockEntityRenderDispatcher) (Object) this;
		Camera camera = self.camera;

		if (camera == null) {
			return;
		}

		Vec3 cam = camera.getPosition();
		BlockPos pos = entity.getBlockPos();
		double dx = pos.getX() + 0.5D - cam.x;
		double dy = pos.getY() + 0.5D - cam.y;
		double dz = pos.getZ() + 0.5D - cam.z;

		// One block covers most block entities; banners and beds are close enough.
		if (!h.culler().shouldCullSq(1.0D, dx * dx + dy * dy + dz * dz)) {
			return;
		}

		BlockEntityRenderer<BlockEntity> renderer = self.getRenderer(entity);

		if (renderer != null && !renderer.shouldRenderOffScreen(entity)) {
			ci.cancel();
		}
	}
}
