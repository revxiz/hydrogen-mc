package dev.hydrogen.mc.mixin;

import dev.hydrogen.core.Hydrogen;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Distance-based AI thinning for passive mobs.
 *
 * A field of two hundred cows runs a full goal selector, sensing pass and
 * navigation tick each, twenty times a second, whether or not anyone is there to
 * see it. Beyond the configured distance a passive mob runs that work one tick in
 * N instead.
 *
 * Hostile mobs, anything with a target, and anything being ridden are never
 * touched. Movement, physics and collision live in aiStep and travel, which still
 * run every tick, so nothing falls through the world.
 *
 * serverAiStep()V has the same descriptor on all four branches.
 */
@Mixin(Mob.class)
public abstract class MobAiThrottleMixin {
	@Inject(method = "serverAiStep", at = @At("HEAD"), cancellable = true)
	private void hydrogen$throttlePassiveAi(CallbackInfo ci) {
		Hydrogen h = Hydrogen.get();

		if (h == null || !h.enabled() || !h.aiThrottle().enabled()) {
			return;
		}

		Mob self = (Mob) (Object) this;

		if (self.isPassenger() || self.isVehicle()) {
			return;
		}

		boolean hostile = self instanceof Enemy;
		boolean hasTarget = self.getTarget() != null;
		double distance = h.aiThrottle().distance();
		Player nearest = self.level().getNearestPlayer(self, distance);

		if (h.aiThrottle().shouldSkip(hostile, hasTarget, nearest != null ? 0.0D : -1.0D, self.tickCount)) {
			ci.cancel();
		}
	}
}
