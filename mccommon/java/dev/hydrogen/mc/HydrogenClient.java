package dev.hydrogen.mc;

import dev.hydrogen.core.HLog;
import dev.hydrogen.core.Hydrogen;
import dev.hydrogen.core.cpu.ThreadRole;
import dev.hydrogen.core.gpu.EvictionController;
import dev.hydrogen.core.gpu.VramSnapshot;
import dev.hydrogen.core.hw.DisplayInfo;
import dev.hydrogen.core.hw.GpuInfo;
import dev.hydrogen.mc.gl.DisplayProbe;
import dev.hydrogen.mc.gl.VramProbe;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Per-tick client driver, called from the end of {@code Minecraft.tick()} by a
 * mixin, so it needs no loader event API. Everything here uses Minecraft APIs
 * that are identical on every supported version; the pieces that are not live
 * behind {@link ClientBridge}.
 */
public final class HydrogenClient {
	private static final HydrogenClient INSTANCE = new HydrogenClient();

	/** Coarse clock for texture use stamps, written once per frame. */
	public static volatile long frameClockMs;

	private final VramProbe vramProbe = new VramProbe();
	private final double[] camera = new double[5];

	private boolean graphicsReady;
	private boolean failed;
	private long lastDisplayCheckMs;
	private DisplayInfo lastDisplay = DisplayInfo.UNKNOWN;
	private boolean wasInWorld;
	private Object lastLevel;
	private int ticks;

	private HydrogenClient() {
	}

	public static void onEndTick(Minecraft mc) {
		INSTANCE.tick(mc);
	}

	public static boolean inWorld(Minecraft mc) {
		return mc.level != null && mc.player != null;
	}

	private void tick(Minecraft mc) {
		Hydrogen h = Hydrogen.get();

		if (h == null || failed || !h.enabled()) {
			return;
		}

		try {
			h.bindCurrentThread(ThreadRole.RENDER);
			ticks++;

			if (!graphicsReady) {
				initGraphics(mc, h);
			}

			trackDisplay(mc, h);
			boolean inWorld = trackWorld(mc, h);

			if (inWorld) {
				feedCamera(mc, h);
				runGc(mc, h);
				runVram(mc, h);
				DeferredSections.replay(mc, h.lastFrameMs(), h.budget().targetFrameMs());
			} else {
				runGc(mc, h);
			}
		} catch (Throwable t) {
			// Stop for the session rather than throwing every tick.
			failed = true;
			HLog.warnOnce("client-tick", "Hydrogen: client tick hook failed, disabling it", t);
		}
	}

	private void initGraphics(Minecraft mc, Hydrogen h) {
		GpuInfo gpu = vramProbe.probe(h.compat().vulkanMod());
		DisplayInfo display = probeDisplay(mc);

		h.hardware().setRenderDistanceChunks(mc.options.renderDistance().get());
		h.hardware().setFovDegrees(mc.options.fov().get());
		h.onGraphicsReady(display, gpu);

		lastDisplay = display;
		graphicsReady = true;
	}

	private DisplayInfo probeDisplay(Minecraft mc) {
		double guiScale = mc.getWindow().getGuiScale();
		int limit = mc.options.framerateLimit().get();
		boolean vsync = mc.options.enableVsync().get();
		return DisplayProbe.probe(ClientBridge.windowHandle(mc), guiScale, limit, vsync);
	}

	/**
	 * Re-probes once a second. A new size, refresh rate or DPI re-runs calibration;
	 * a changed frame limiter or vsync only moves the target, which used to wait
	 * for the next window resize.
	 */
	private void trackDisplay(Minecraft mc, Hydrogen h) {
		long now = System.currentTimeMillis();

		if (now - lastDisplayCheckMs < 1000L) {
			return;
		}

		lastDisplayCheckMs = now;
		h.hardware().setRenderDistanceChunks(mc.options.renderDistance().get());
		h.hardware().setFovDegrees(mc.options.fov().get());

		DisplayInfo d = probeDisplay(mc);

		if (d.equals(lastDisplay)) {
			return;
		}

		boolean geometry = d.framebufferWidth() != lastDisplay.framebufferWidth()
				|| d.framebufferHeight() != lastDisplay.framebufferHeight()
				|| d.refreshHz() != lastDisplay.refreshHz()
				|| d.contentScale() != lastDisplay.contentScale();

		lastDisplay = d;
		h.onDisplayChanged(d, geometry);
	}

	private boolean trackWorld(Minecraft mc, Hydrogen h) {
		boolean inWorld = mc.level != null && mc.player != null;

		if (mc.level != lastLevel) {
			// Deferred coordinates belong to the level they came from.
			DeferredSections.clear();
			lastLevel = mc.level;
		}

		if (inWorld && !wasInWorld) {
			h.onWorldJoin();
		} else if (!inWorld && wasInWorld) {
			h.onWorldLeave();
		}

		wasInWorld = inWorld;
		return inWorld;
	}

	/** The live camera: in third person it is not at the player's eyes. */
	private void feedCamera(Minecraft mc, Hydrogen h) {
		Vec3 motion = mc.player.getDeltaMovement();

		if (!ClientBridge.camera(mc, camera)) {
			camera[0] = mc.player.getX();
			camera[1] = mc.player.getEyeY();
			camera[2] = mc.player.getZ();
			camera[3] = mc.player.getYRot();
			camera[4] = mc.player.getXRot();
		}

		h.cone().updateCamera(camera[0], camera[1], camera[2], camera[3], camera[4], motion.x, motion.z);
	}

	private void runGc(Minecraft mc, Hydrogen h) {
		double speed = 0.0D;

		if (mc.player != null) {
			Vec3 m = mc.player.getDeltaMovement();
			speed = Math.sqrt(m.x * m.x + m.z * m.z);

			if (mc.player.hurtTime > 0 || mc.options.keyAttack.isDown() || mc.options.keyUse.isDown()) {
				h.gc().markAction();
			}
		}

		h.gc().tick(speed, ClientBridge.screenOpen(mc), mc.isPaused());
	}

	/** VRAM moves slowly; four reads a second are plenty and keep driver queries off most frames. */
	private void runVram(Minecraft mc, Hydrogen h) {
		if (!vramProbe.usable() || ticks % 5 != 0) {
			return;
		}

		VramSnapshot snapshot = vramProbe.read();
		h.setVram(snapshot);

		EvictionController.Action action = h.takeEvictionAction();

		if (action != EvictionController.Action.NONE) {
			TextureEvictor.run(mc, h, action, snapshot);
		}

		h.eviction().relaxCap(snapshot, mc.options.renderDistance().get(), System.currentTimeMillis());
	}
}
