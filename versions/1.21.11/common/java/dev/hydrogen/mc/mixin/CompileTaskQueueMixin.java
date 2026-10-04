package dev.hydrogen.mc.mixin;

import dev.hydrogen.mc.chunk.ConeMetric;
import net.minecraft.client.renderer.chunk.CompileTaskDynamicQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Motion-vector chunk prioritisation.
 *
 * The queue picks the nearest pending section; swapping the distance it compares
 * for a cone-weighted one moves meshing into the player's forward view without
 * touching the recompile quota or the cancellation sweep.
 */
@Mixin(CompileTaskDynamicQueue.class)
public abstract class CompileTaskQueueMixin {
	@Redirect(
			method = "poll",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/core/BlockPos;distToCenterSqr(Lnet/minecraft/core/Position;)D"))
	private double hydrogen$coneWeightedDistance(BlockPos origin, Position camera) {
		return ConeMetric.weighted(origin, camera);
	}
}
