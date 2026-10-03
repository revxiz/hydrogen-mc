package dev.hydrogen.mc.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.hydrogen.mc.mixin.MainTargetAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.Optional;

/**
 * The render-target calls that differ in 26.3, where rendering moved onto the
 * renderpearl layer: targets name both formats, pipelines are compiled per
 * device, and samplers are bound as uniforms. Works on either backend.
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

	/** Same formats as vanilla's main target, so every world pipeline accepts it. */
	public static RenderTarget createTarget(String label, int width, int height) {
		return new TextureTarget(label, width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
	}

	/** Plain blit pipeline: no blending, no depth, linear or nearest sampling. */
	public static void upscale(RenderTarget src, RenderTarget dst, boolean linear) {
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
				.createRenderPass(() -> "Hydrogen upscale", dst.getColorTextureView(), Optional.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("InSampler", src.getColorTextureView(),
					RenderSystem.getSamplerCache().getClampToEdge(linear ? FilterMode.LINEAR : FilterMode.NEAREST));
			pass.draw(3, 1, 0, 0);
		}
	}
}
