package dev.hydrogen.mc.mixin;

import dev.hydrogen.mc.audio.SoundGating;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Drops low-priority distant sounds before they reach a saturated channel pool.
 * 1.21.9 changed play to return a PlayResult, so the caller is told the sound
 * never started rather than being left to assume it did.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineGateMixin {
	@Inject(
			method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)"
					+ "Lnet/minecraft/client/sounds/SoundEngine$PlayResult;",
			at = @At("HEAD"),
			cancellable = true)
	private void hydrogen$gate(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
		if (SoundGating.shouldCull(sound, (SoundEngine) (Object) this)) {
			cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
		}
	}
}
