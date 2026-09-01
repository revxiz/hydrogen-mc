package dev.hydrogen.mc.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.client.Minecraft;

/**
 * The three render-target calls that differ in 26.x: the main target moved onto
 * GameRenderer, targets carry an explicit format, and the blit takes both views.
 */
public final class ModernRenderBridge {
	private ModernRenderBridge() {
	}

	public static RenderTarget mainTarget(Minecraft mc) {
		return mc.gameRenderer.mainRenderTarget();
	}

	public static RenderTarget createTarget(String label, int width, int height) {
		return new TextureTarget(label, width, height, true, GpuFormat.RGBA8_UNORM);
	}

	public static void upscale(RenderTarget src, RenderTarget dst) {
		src.blitAndBlendToTexture(dst.getColorTextureView(), dst.getDepthTextureView());
	}
}
