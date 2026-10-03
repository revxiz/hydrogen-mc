package dev.hydrogen.mc.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.hydrogen.core.HLog;
import dev.hydrogen.core.Hydrogen;
import dev.hydrogen.mc.ClientBridge;
import dev.hydrogen.mc.compat.IrisCompat;
import net.minecraft.client.Minecraft;

/**
 * Decoupled dynamic resolution scaling on the Blaze3D path used by 1.21.9+ and
 * 26.x.
 *
 * The world's frame graph imports whatever the game reports as its main target
 * when the level pass starts. For the length of that pass the scaled target is
 * swapped in, so terrain, entities, sky, weather and the hand all render into it
 * with a matching depth buffer. Afterwards the real target is put back and the
 * world is drawn up into it with a plain linear blit. The HUD is drawn after
 * that and stays native, exactly as on the OpenGL path.
 *
 * This works through Mojang's own render abstraction, so it does not care which
 * graphics API is underneath. It stays behind drs.allowNewBlaze3d because the
 * abstraction keeps changing between releases.
 */
public final class RenderScaler {
	private static RenderTarget target;
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

	/** The target the world should draw into right now, used by hooks that cache it. */
	public static RenderTarget currentMain(RenderTarget fallback) {
		return redirecting && target != null ? target : fallback;
	}

	public static void begin(Minecraft mc, Hydrogen h) {
		ensureRestored(mc);

		if (broken || !h.enabled() || h.compat().vulkanMod()) {
			return;
		}

		double scale = h.resolution().scale();

		if (scale >= 0.999D) {
			return;
		}

		if (!h.config().bool("drs.allowNewBlaze3d")) {
			HLog.once("drs-modern",
					"Hydrogen: viewport scaling is opt-in on this Minecraft version, "
							+ "set drs.allowNewBlaze3d=true in config/hydrogen.properties to enable it");
			return;
		}

		if (ClientBridge.fabulous(mc)) {
			HLog.once("drs-fabulous", "Hydrogen: resolution scaling pauses while improved transparency is on");
			return;
		}

		if (IrisCompat.shadersActive()) {
			HLog.once("drs-iris", "Hydrogen: resolution scaling pauses while an Iris shader pack is active");
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

			real = main;
			ModernRenderBridge.setMainTarget(mc, target);
			redirecting = true;
		} catch (Throwable t) {
			fail(mc, t);
		}
	}

	public static void end(Minecraft mc, Hydrogen h) {
		if (!redirecting) {
			return;
		}

		redirecting = false;

		try {
			ModernRenderBridge.setMainTarget(mc, real);
			ModernRenderBridge.upscale(target, real, h.config().bool("drs.linearUpscale"));
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
			ModernRenderBridge.setMainTarget(mc, real);
		} catch (Throwable ignored) {
			// Nothing more to do.
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
