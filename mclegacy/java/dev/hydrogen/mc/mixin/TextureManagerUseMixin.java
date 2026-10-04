package dev.hydrogen.mc.mixin;

import dev.hydrogen.mc.HydrogenClient;
import dev.hydrogen.mc.TextureUse;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Stamps every texture lookup, which is how render types reach their textures. */
@Mixin(TextureManager.class)
public abstract class TextureManagerUseMixin {
	@Inject(method = "getTexture(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/texture/AbstractTexture;",
			at = @At("RETURN"))
	private void hydrogen$stamp(ResourceLocation id, CallbackInfoReturnable<AbstractTexture> cir) {
		if (cir.getReturnValue() instanceof TextureUse use) {
			use.hydrogen$touch(HydrogenClient.frameClockMs);
		}
	}
}
