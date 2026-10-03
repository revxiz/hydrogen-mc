package dev.hydrogen.mc.mixin;

import dev.hydrogen.core.Hydrogen;
import dev.hydrogen.core.sim.AiThrottle;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.Npc;
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
 * Hostile mobs, anything with a target, anything ridden or riding, and villagers
 * and traders are never touched: iron farms, raids and trading halls depend on
 * villager AI running on time. Movement, physics and collision live in aiStep and
 * travel, which still run every tick, so nothing falls through the world.
 *
 * The cheap checks run first, so mobs that run their AI this tick anyway never
 * pay for the nearest-player search. serverAiStep()V is the same on every
 * supported version.
 */
@Mixin(Mob.class)
public abstract class MobAiThrottleMixin {
	@Inject(method = "serverAiStep", at = @At("HEAD"), cancellable = true)
	private void hydrogen$throttlePassiveAi(CallbackInfo ci) {
		Hydrogen h = Hydrogen.get();

		if (h == null) {
			return;
		}

		AiThrottle throttle = h.aiThrottle();

		if (!throttle.enabled()) {
			return;
		}

		Mob self = (Mob) (Object) this;

		if (self instanceof Enemy || self instanceof Npc || self.isPassenger() || self.isVehicle()
				|| self.getTarget() != null) {
			return;
		}

		if (!throttle.candidate(self.tickCount)) {
			return;
		}

		if (self.level().getNearestPlayer(self, throttle.distance()) != null) {
			return;
		}

		throttle.recordSkip();
		ci.cancel();
	}
}
