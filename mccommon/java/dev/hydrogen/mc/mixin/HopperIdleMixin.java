package dev.hydrogen.mc.mixin;

import dev.hydrogen.core.Hydrogen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Idle throttling for hoppers.
 *
 * An empty hopper that is off cooldown still runs an entity box query above
 * itself every tick looking for items to pull in. That query, multiplied by a few
 * hundred hoppers, is the real cost in a storage room.
 *
 * The tick is only skipped when the hopper is empty and has no cooldown left to
 * burn down, so vanilla's transfer timing is never interfered with. Wake-ups are
 * staggered by block position to stop a whole room firing on the same tick.
 *
 * pushItemsTick has the same descriptor on all four branches.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperIdleMixin {
	@Inject(method = "pushItemsTick", at = @At("HEAD"), cancellable = true)
	private static void hydrogen$throttleIdle(Level level, BlockPos pos, BlockState state,
			HopperBlockEntity hopper, CallbackInfo ci) {
		Hydrogen h = Hydrogen.get();

		if (h == null || !h.enabled() || !h.hopperThrottle().enabled()) {
			return;
		}

		boolean onCooldown = ((HopperAccessor) hopper).hydrogen$cooldownTime() > 0;

		if (h.hopperThrottle().shouldSkip(hopper.isEmpty(), onCooldown, level.getGameTime(), pos.hashCode())) {
			ci.cancel();
		}
	}
}
