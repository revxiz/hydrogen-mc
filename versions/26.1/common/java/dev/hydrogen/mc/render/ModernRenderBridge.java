package dev.hydrogen.mc.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import dev.hydrogen.mc.mixin.MainTargetAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.OptionalInt;

/** The render-target calls that differ between Minecraft versions, 1.21.9 to 26.1.x flavour. */
public final class ModernRenderBridge {
	private ModernRenderBridge() {
	}

	public static RenderTarget mainTarget(Minecraft mc) {
		return ((MainTargetAccessor) mc).hydrogen$mainTarget();
	}

	public static void setMainTarget(Minecraft mc, RenderTarget target) {
		((MainTargetAccessor) mc).hydrogen$setMainTarget(target);
	}

	public static RenderTarget createTarget(String label, int width, int height) {
		return new TextureTarget(label, width, height, true);
	}

	/**
	 * Draws {@code src} over all of {@code dst} with the plain blit pipeline (no
	 * blending, no depth). Vanilla's blitAndBlendToTexture would blend and always
	 * samples nearest.
	 */
	public static void upscale(RenderTarget src, RenderTarget dst, boolean linear) {
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
				.createRenderPass(() -> "Hydrogen upscale", dst.getColorTextureView(), OptionalInt.empty())) {
			pass.setPipeline(RenderPipelines.TRACY_BLIT);
			RenderSystem.bindDefaultUniforms(pass);
			pass.bindTexture("InSampler", src.getColorTextureView(),
					RenderSystem.getSamplerCache().getClampToEdge(linear ? FilterMode.LINEAR : FilterMode.NEAREST));
			pass.draw(0, 3);
		}
	}
}
