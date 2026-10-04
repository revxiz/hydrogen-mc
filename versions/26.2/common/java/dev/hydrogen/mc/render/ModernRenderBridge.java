package dev.hydrogen.mc.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import dev.hydrogen.mc.mixin.MainTargetAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.Optional;

/**
 * The render-target calls that differ in 26.2: the main target moved onto
 * GameRenderer, targets carry an explicit format, and draws take instance counts.
 */
public final class ModernRenderBridge {
	private ModernRenderBridge() {
	}

	public static RenderTarget mainTarget(Minecraft mc) {
		return ((MainTargetAccessor) mc.gameRenderer).hydrogen$mainTarget();
	}

	public static void setMainTarget(Minecraft mc, RenderTarget target) {
		((MainTargetAccessor) mc.gameRenderer).hydrogen$setMainTarget(target);
	}

	public static RenderTarget createTarget(String label, int width, int height) {
		return new TextureTarget(label, width, height, true, GpuFormat.RGBA8_UNORM);
	}

	/** Plain blit pipeline: no blending, no depth, linear or nearest sampling. */
	public static void upscale(RenderTarget src, RenderTarget dst, boolean linear) {
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
				.createRenderPass(() -> "Hydrogen upscale", dst.getColorTextureView(), Optional.empty())) {
			pass.setPipeline(RenderPipelines.TRACY_BLIT);
			RenderSystem.bindDefaultUniforms(pass);
			pass.bindTexture("InSampler", src.getColorTextureView(),
					RenderSystem.getSamplerCache().getClampToEdge(linear ? FilterMode.LINEAR : FilterMode.NEAREST));
			pass.draw(3, 1, 0, 0);
		}
	}
}
