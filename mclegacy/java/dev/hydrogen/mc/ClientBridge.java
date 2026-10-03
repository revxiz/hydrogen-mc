package dev.hydrogen.mc;

import net.minecraft.client.Camera;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/** Client accessors for 1.20.x and 1.21.1, the OpenGL render-target era. */
public final class ClientBridge {
	private ClientBridge() {
	}

	public static boolean screenOpen(Minecraft mc) {
		return mc.screen != null;
	}

	public static long windowHandle(Minecraft mc) {
		return mc.getWindow().getWindow();
	}

	/** Fills x, y, z, yaw, pitch of the live camera. False before the first frame. */
	public static boolean camera(Minecraft mc, double[] out) {
		Camera cam = mc.gameRenderer.getMainCamera();

		if (cam == null || !cam.isInitialized()) {
			return false;
		}

		Vec3 pos = cam.getPosition();
		out[0] = pos.x;
		out[1] = pos.y;
		out[2] = pos.z;
		out[3] = cam.getYRot();
		out[4] = cam.getXRot();
		return true;
	}

	/** Fabulous draws through full-size targets a scaled world cannot feed. */
	public static boolean fabulous(Minecraft mc) {
		return mc.options.graphicsMode().get() == GraphicsStatus.FABULOUS;
	}
}
