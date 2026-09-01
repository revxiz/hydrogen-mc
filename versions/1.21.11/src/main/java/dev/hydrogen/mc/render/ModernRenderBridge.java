package dev.hydrogen.mc.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.client.Minecraft;

/** The three render-target calls that differ between 1.21.9+ and 26.x. */
public final class ModernRenderBridge {
	private ModernRenderBridge() {
	}

	public static RenderTarget mainTarget(Minecraft mc) {
		return mc.getMainRenderTarget();
	}

	public static RenderTarget createTarget(String label, int width, int height) {
		return new TextureTarget(label, width, height, true);
	}

	public static void upscale(RenderTarget src, RenderTarget dst) {
		src.blitAndBlendToTexture(dst.getColorTextureView());
	}
}
