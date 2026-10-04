package dev.hydrogen.mc.mixin;

import dev.hydrogen.core.Hydrogen;
import dev.hydrogen.core.sim.HopperThrottle;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Idle throttling for hoppers.
 *
 * An empty hopper with no container above it still runs an entity box query
 * every tick looking for dropped items. That query, multiplied by a few hundred
 * hoppers, is the real cost in a storage room.
 *
 * Only {@code getItemsAtAndAbove} is thinned, which vanilla reaches solely when
 * there is no container to pull from. Pulling from a chest, pushing, cooldowns
 * and the tick bookkeeping hopper chains rely on all run exactly as vanilla, so
 * sorters and hopper clocks keep their timing. Hopper minecarts are untouched.
 *
 * The method has the same descriptor on every supported version.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperIdleMixin {
	@Inject(method = "getItemsAtAndAbove", at = @At("HEAD"), cancellable = true)
	private static void hydrogen$throttleIdleScan(Level level, Hopper hopper,
			CallbackInfoReturnable<List<ItemEntity>> cir) {
		if (!(hopper instanceof HopperBlockEntity block)) {
			return;
		}

		Hydrogen h = Hydrogen.get();

		if (h == null) {
			return;
		}

		HopperThrottle throttle = h.hopperThrottle();

		if (throttle.enabled()
				&& throttle.shouldSkipScan(block.isEmpty(), level.getGameTime(), block.getBlockPos().hashCode())) {
			cir.setReturnValue(List.of());
		}
	}
}
