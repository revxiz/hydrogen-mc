package dev.hydrogen.mc.render;

import dev.hydrogen.core.HLog;
import dev.hydrogen.core.Hydrogen;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Decoupled dynamic resolution scaling on the OpenGL render target path.
 *
 * The 3D scene is drawn into an off-screen target at the controller's scale and
 * then blitted up into the main target with linear filtering. The HUD, text and
 * menus are drawn afterwards straight into the main target, so they stay at
 * native resolution no matter how far the world is scaled down.
 *
 * Sodium draws into whatever target is bound, so it needs no special handling.
 * A Vulkan backend has no GL framebuffer and is excluded by the mixin plugin.
 */
public final class RenderScaler {
	private static TextureTarget target;
	private static int targetWidth;
	private static int targetHeight;
	private static boolean redirecting;
	private static boolean broken;

	private RenderScaler() {
	}

	public static boolean active() {
		return redirecting;
	}

	/** Called immediately before the world is drawn. */
	public static void begin(Minecraft mc, Hydrogen h) {
		redirecting = false;

		if (broken || !h.enabled() || !h.compat().allowFramebufferScaling()) {
			return;
		}

		double scale = h.resolution().scale();

		if (scale >= 0.999D) {
			return;
		}

		try {
			RenderTarget main = mc.getMainRenderTarget();
			int w = Math.max(1, (int) Math.round(main.width * scale));
			int hgt = Math.max(1, (int) Math.round(main.height * scale));

			if (target == null || targetWidth != w || targetHeight != hgt) {
				if (target != null) {
					target.destroyBuffers();
				}

				target = new TextureTarget(w, hgt, true, Minecraft.ON_OSX);
				target.setFilterMode(GL11.GL_LINEAR);
				targetWidth = w;
				targetHeight = hgt;
			}

			target.setClearColor(0.0F, 0.0F, 0.0F, 1.0F);
			target.clear(Minecraft.ON_OSX);
			// Copy depth so anything already drawn still occludes correctly.
			target.bindWrite(true);
			redirecting = true;
		} catch (Throwable t) {
			fail(t);
		}
	}

	/** Called immediately after the world is drawn. */
	public static void end(Minecraft mc, Hydrogen h) {
		if (!redirecting) {
			return;
		}

		redirecting = false;

		try {
			RenderTarget main = mc.getMainRenderTarget();
			target.unbindWrite();

			GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
			GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, main.frameBufferId);
			GlStateManager._glBlitFrameBuffer(
					0, 0, targetWidth, targetHeight,
					0, 0, main.width, main.height,
					GL11.GL_COLOR_BUFFER_BIT,
					h.config().bool("drs.linearUpscale") ? GL11.GL_LINEAR : GL11.GL_NEAREST);

			main.bindWrite(true);
		} catch (Throwable t) {
			fail(t);

			try {
				mc.getMainRenderTarget().bindWrite(true);
			} catch (Throwable ignored) {
				// Nothing further to do; the next frame rebinds anyway.
			}
		}
	}

	private static void fail(Throwable t) {
		broken = true;
		redirecting = false;
		HLog.warnOnce("drs", "Hydrogen: resolution scaling failed, staying at native", t);
	}

	public static void dispose() {
		if (target != null) {
			try {
				target.destroyBuffers();
			} catch (Throwable ignored) {
				// Context may already be gone.
			}

			target = null;
		}
	}
}
