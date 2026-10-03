package dev.hydrogen.mc.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import dev.hydrogen.core.HLog;
import dev.hydrogen.core.Hydrogen;
import dev.hydrogen.mc.ClientBridge;
import dev.hydrogen.mc.compat.IrisCompat;
import dev.hydrogen.mc.mixin.MainTargetAccessor;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Decoupled dynamic resolution scaling on the OpenGL render target path.
 *
 * For the length of the world render, the scaled target is swapped in as the
 * game's main target. Vanilla rebinds "the main target" several times inside
 * that pass (after entity outlines, for item entities), and with the swap every
 * one of those lands in the scaled target instead of drawing a corner of the
 * full-size one that the upscale then overwrites. Afterwards the real target is
 * put back and the world is blitted up with linear filtering. The HUD, text and
 * menus are drawn after that, straight into the real target, so they stay at
 * native resolution.
 *
 * Fabulous graphics and Iris shader packs draw through extra full-size targets
 * of their own, so scaling steps aside while either is active.
 */
public final class RenderScaler {
	private static TextureTarget target;
	private static RenderTarget real;
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
		ensureRestored(mc);

		if (broken || !h.enabled() || !h.compat().allowFramebufferScaling()) {
			return;
		}

		double scale = h.resolution().scale();

		if (scale >= 0.999D) {
			return;
		}

		if (ClientBridge.fabulous(mc)) {
			HLog.once("drs-fabulous", "Hydrogen: resolution scaling pauses while Fabulous graphics is on");
			return;
		}

		if (IrisCompat.shadersActive()) {
			HLog.once("drs-iris", "Hydrogen: resolution scaling pauses while an Iris shader pack is active");
			return;
		}

		try {
			MainTargetAccessor access = (MainTargetAccessor) mc;
			RenderTarget main = access.hydrogen$mainTarget();
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

			real = main;
			access.hydrogen$setMainTarget(target);
			target.setClearColor(0.0F, 0.0F, 0.0F, 1.0F);
			target.clear(Minecraft.ON_OSX);
			// Binds the scaled target and sets the viewport to its size.
			target.bindWrite(true);
			redirecting = true;
		} catch (Throwable t) {
			fail(mc, t);
		}
	}

	/** Called immediately after the world is drawn. */
	public static void end(Minecraft mc, Hydrogen h) {
		if (!redirecting) {
			return;
		}

		redirecting = false;

		try {
			((MainTargetAccessor) mc).hydrogen$setMainTarget(real);
			target.unbindWrite();

			GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
			GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, real.frameBufferId);
			GlStateManager._glBlitFrameBuffer(
					0, 0, targetWidth, targetHeight,
					0, 0, real.width, real.height,
					GL11.GL_COLOR_BUFFER_BIT,
					h.config().bool("drs.linearUpscale") ? GL11.GL_LINEAR : GL11.GL_NEAREST);

			real.bindWrite(true);
		} catch (Throwable t) {
			fail(mc, t);
		}
	}

	/**
	 * Puts the real main target back if a world render threw before {@link #end}.
	 * Called at the start of every frame, so a failure costs at most one frame.
	 */
	public static void ensureRestored(Minecraft mc) {
		if (!redirecting) {
			return;
		}

		redirecting = false;

		try {
			((MainTargetAccessor) mc).hydrogen$setMainTarget(real);
			real.bindWrite(true);
		} catch (Throwable ignored) {
			// Nothing more to do; the next frame binds the main target anyway.
		}
	}

	private static void fail(Minecraft mc, Throwable t) {
		broken = true;
		ensureRestored(mc);
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
