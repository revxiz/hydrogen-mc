package dev.hydrogen.mc.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.hydrogen.mc.render.RenderScaler;
import net.minecraft.client.renderer.SkyRenderer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Since 26.2 the sky renderer keeps its own reference to the main target, taken
 * when it was created, so swapping the main target does not reach it. While the
 * world is being scaled, its reads are pointed at the scaled target, otherwise
 * the sky would be drawn into the full-size target and lost under the upscale.
 */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererTargetMixin {
	@Shadow
	@Final
	private RenderTarget renderTarget;

	@Redirect(method = "*", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
			target = "Lnet/minecraft/client/renderer/SkyRenderer;renderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
	private RenderTarget hydrogen$scaledTarget(SkyRenderer self) {
		return RenderScaler.currentMain(renderTarget);
	}
}
