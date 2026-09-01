package dev.hydrogen.mc.mixin;

import dev.hydrogen.mc.audio.SoundGating;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops low-priority distant sounds before they reach a saturated channel pool.
 * On 1.20 and 1.21.1 play returns void, so cancelling is enough.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineGateMixin {
	@Inject(
			method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)V",
			at = @At("HEAD"),
			cancellable = true)
	private void hydrogen$gate(SoundInstance sound, CallbackInfo ci) {
		if (SoundGating.shouldCull(sound, (SoundEngine) (Object) this)) {
			ci.cancel();
		}
	}
}
