package dev.hydrogen.mc.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.hydrogen.core.HLog;
import dev.hydrogen.core.Hydrogen;
import net.minecraft.client.Minecraft;

/**
 * Decoupled dynamic resolution scaling on the Blaze3D GpuDevice path used by
 * 1.21.9+ and 26.x.
 *
 * The world is redirected into a scaled target through the render output
 * overrides, then blitted up into the main target. The HUD is drawn after
 * renderLevel returns and so stays native, exactly as on the OpenGL path.
 *
 * This backend is opt-in. The GL path has been in use for years, while this one
 * rides an API that is still changing between snapshots, so it stays behind
 * drs.allowNewBlaze3d until a user asks for it.
 */
public final class RenderScaler {
	private static RenderTarget target;
	private static int targetWidth;
	private static int targetHeight;
	private static boolean redirecting;
	private static boolean broken;

	private RenderScaler() {
	}

	public static boolean active() {
		return redirecting;
	}

	public static void begin(Minecraft mc, Hydrogen h) {
		redirecting = false;

		if (broken || !h.enabled() || !h.compat().allowFramebufferScaling()) {
			return;
		}

		if (!h.config().bool("drs.allowNewBlaze3d")) {
			if (h.resolution().scaling()) {
				HLog.once("drs-modern",
						"Hydrogen: viewport scaling is opt-in on this Minecraft version, "
								+ "set drs.allowNewBlaze3d=true in config/hydrogen.properties to enable it");
			}

			return;
		}

		double scale = h.resolution().scale();

		if (scale >= 0.999D) {
			return;
		}

		try {
			RenderTarget main = ModernRenderBridge.mainTarget(mc);
			int w = Math.max(1, (int) Math.round(main.width * scale));
			int hgt = Math.max(1, (int) Math.round(main.height * scale));

			if (target == null || targetWidth != w || targetHeight != hgt) {
				if (target != null) {
					target.destroyBuffers();
				}

				target = ModernRenderBridge.createTarget("hydrogen_world", w, hgt);
				targetWidth = w;
				targetHeight = hgt;
			}

			RenderSystem.outputColorTextureOverride = target.getColorTextureView();
			RenderSystem.outputDepthTextureOverride = target.getDepthTextureView();
			redirecting = true;
		} catch (Throwable t) {
			fail(t);
		}
	}

	public static void end(Minecraft mc, Hydrogen h) {
		if (!redirecting) {
			return;
		}

		redirecting = false;

		try {
			RenderSystem.outputColorTextureOverride = null;
			RenderSystem.outputDepthTextureOverride = null;
			ModernRenderBridge.upscale(target, ModernRenderBridge.mainTarget(mc));
		} catch (Throwable t) {
			fail(t);
		}
	}

	private static void fail(Throwable t) {
		broken = true;
		redirecting = false;
		RenderSystem.outputColorTextureOverride = null;
		RenderSystem.outputDepthTextureOverride = null;
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
