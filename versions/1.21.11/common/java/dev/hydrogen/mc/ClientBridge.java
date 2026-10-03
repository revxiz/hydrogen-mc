package dev.hydrogen.mc;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/** Client accessors for 1.21.9 to 26.1.x: renamed window handle and camera getters. */
public final class ClientBridge {
	private ClientBridge() {
	}

	public static boolean screenOpen(Minecraft mc) {
		return mc.screen != null;
	}

	public static long windowHandle(Minecraft mc) {
		return mc.getWindow().handle();
	}

	/** Fills x, y, z, yaw, pitch of the live camera. False before the first frame. */
	public static boolean camera(Minecraft mc, double[] out) {
		Camera cam = mc.gameRenderer.getMainCamera();

		if (cam == null || !cam.isInitialized()) {
			return false;
		}

		Vec3 pos = cam.position();
		out[0] = pos.x;
		out[1] = pos.y;
		out[2] = pos.z;
		out[3] = cam.yRot();
		out[4] = cam.xRot();
		return true;
	}

	/** Improved transparency replaced Fabulous and still draws through full-size targets. */
	public static boolean fabulous(Minecraft mc) {
		return mc.options.improvedTransparency().get();
	}
}
