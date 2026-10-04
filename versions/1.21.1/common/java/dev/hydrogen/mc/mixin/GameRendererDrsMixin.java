package dev.hydrogen.mc.mixin;

import dev.hydrogen.core.Hydrogen;
import dev.hydrogen.mc.render.RenderScaler;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brackets the world render so only the 3D viewport is scaled. Everything drawn
 * after renderLevel returns, which is the entire HUD and every menu, lands in the
 * native-resolution main target.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererDrsMixin {
	@Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"))
	private void hydrogen$beginWorld(DeltaTracker delta, CallbackInfo ci) {
		Hydrogen h = Hydrogen.get();

		if (h != null) {
			RenderScaler.begin(Minecraft.getInstance(), h);
		}
	}

	@Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("RETURN"))
	private void hydrogen$endWorld(DeltaTracker delta, CallbackInfo ci) {
		Hydrogen h = Hydrogen.get();

		if (h != null) {
			RenderScaler.end(Minecraft.getInstance(), h);
		}
	}
}
