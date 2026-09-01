package dev.hydrogen.mc;

import net.minecraft.client.Minecraft;

/**
 * The two client accessors that moved between branches: the current screen and
 * the GLFW window handle.
 */
public final class ScreenAccess {
	private ScreenAccess() {
	}

	public static boolean screenOpen(Minecraft mc) {
		return mc.screen != null;
	}

	public static long windowHandle(Minecraft mc) {
		return mc.getWindow().getWindow();
	}
}
