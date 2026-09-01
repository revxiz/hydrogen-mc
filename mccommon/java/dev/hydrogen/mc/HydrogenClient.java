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
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Per-frame client driver. Everything here uses Minecraft APIs that are
 * identical on 1.20.1 through 26.x; the two pieces that are not live behind
 * {@link ScreenAccess} and {@link dev.hydrogen.mc.render.RenderScaler}.
 */
public final class HydrogenClient implements ClientModInitializer {
	private final VramProbe vramProbe = new VramProbe();

	private boolean graphicsReady;
	private long lastDisplayCheckMs;
	private int lastWidth;
	private int lastHeight;
	private boolean wasInWorld;

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
		HLog.LOG.info("Hydrogen client ready");
	}

	private void onEndTick(Minecraft mc) {
		Hydrogen h = Hydrogen.get();

		if (h == null || !h.enabled()) {
			return;
		}

		try {
			h.bindCurrentThread(ThreadRole.RENDER);

			if (!graphicsReady) {
				initGraphics(mc, h);
			}

			trackDisplay(mc, h);
			trackWorld(mc, h);
			feedCamera(mc, h);
			runGc(mc, h);
			runVram(mc, h);
			DeferredSections.replay(mc, h.lastFrameMs(), h.budget().targetFrameMs());
		} catch (Throwable t) {
			HLog.warnOnce("client-tick", "Hydrogen: client tick hook failed, disabling it", t);
		}
	}

	private void initGraphics(Minecraft mc, Hydrogen h) {
		boolean vulkan = h.compat().vulkanMod();
		GpuInfo gpu = vramProbe.probe(vulkan);
		DisplayInfo display = probeDisplay(mc);

		h.hardware().setRenderDistanceChunks(mc.options.getEffectiveRenderDistance());
		h.hardware().setFovDegrees(mc.options.fov().get());
		h.onGraphicsReady(display, gpu);

		lastWidth = display.framebufferWidth();
		lastHeight = display.framebufferHeight();
		graphicsReady = true;
	}

	private DisplayInfo probeDisplay(Minecraft mc) {
		double guiScale = mc.getWindow().getGuiScale();
		int limit = mc.options.framerateLimit().get();
		boolean vsync = mc.options.enableVsync().get();
		return DisplayProbe.probe(ScreenAccess.windowHandle(mc), guiScale, limit, vsync);
	}

	/** Cheap poll; a resize or monitor change re-derives every threshold. */
	private void trackDisplay(Minecraft mc, Hydrogen h) {
		long now = System.currentTimeMillis();

		if (now - lastDisplayCheckMs < 1000L) {
			return;
		}

		lastDisplayCheckMs = now;

		int w = mc.getWindow().getWidth();
		int hgt = mc.getWindow().getHeight();

		h.hardware().setRenderDistanceChunks(mc.options.getEffectiveRenderDistance());
		h.hardware().setFovDegrees(mc.options.fov().get());

		if (w != lastWidth || hgt != lastHeight) {
			lastWidth = w;
			lastHeight = hgt;
			h.onDisplayChanged(probeDisplay(mc));
		}
	}

	private void trackWorld(Minecraft mc, Hydrogen h) {
		boolean inWorld = mc.level != null && mc.player != null;

		if (inWorld && !wasInWorld) {
			h.onWorldJoin();
		} else if (!inWorld && wasInWorld) {
			h.onWorldLeave();
		}

		wasInWorld = inWorld;
	}

	private void feedCamera(Minecraft mc, Hydrogen h) {
		if (mc.player == null) {
			return;
		}

		Vec3 motion = mc.player.getDeltaMovement();
		h.cone().updateCamera(
				mc.player.getX(),
				mc.player.getEyeY(),
				mc.player.getZ(),
				mc.player.getYRot(),
				mc.player.getXRot(),
				motion.x,
				motion.z);
	}

	private void runGc(Minecraft mc, Hydrogen h) {
		double speed = 0.0D;

		if (mc.player != null) {
			Vec3 m = mc.player.getDeltaMovement();
			speed = Math.sqrt(m.x * m.x + m.z * m.z);

			if (mc.player.hurtTime > 0 || mc.options.keyAttack.isDown()) {
				h.gc().markAction();
			}
		}

		h.gc().tick(speed, ScreenAccess.screenOpen(mc), mc.isPaused());
	}

	private void runVram(Minecraft mc, Hydrogen h) {
		if (!vramProbe.usable()) {
			return;
		}

		VramSnapshot snapshot = vramProbe.read();
		h.setVram(snapshot);

		EvictionController.Action action = h.takeEvictionAction();

		if (action != EvictionController.Action.NONE) {
			TextureEvictor.run(mc, h, action, snapshot);
		}
	}
}
