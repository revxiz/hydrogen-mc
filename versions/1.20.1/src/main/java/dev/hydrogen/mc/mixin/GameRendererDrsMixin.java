package dev.hydrogen.mc.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.hydrogen.core.Hydrogen;
import dev.hydrogen.mc.render.RenderScaler;
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
 *
 * 1.20.x still passes the partial tick and a deadline rather than a DeltaTracker.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererDrsMixin {
	@Inject(method = "renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V", at = @At("HEAD"))
	private void hydrogen$beginWorld(float partialTick, long finishNano, PoseStack pose, CallbackInfo ci) {
		Hydrogen h = Hydrogen.get();

		if (h != null) {
			RenderScaler.begin(Minecraft.getInstance(), h);
		}
	}

	@Inject(method = "renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V", at = @At("RETURN"))
	private void hydrogen$endWorld(float partialTick, long finishNano, PoseStack pose, CallbackInfo ci) {
		Hydrogen h = Hydrogen.get();

		if (h != null) {
			RenderScaler.end(Minecraft.getInstance(), h);
		}
	}
}
