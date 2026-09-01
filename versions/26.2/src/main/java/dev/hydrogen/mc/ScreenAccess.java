package dev.hydrogen.mc;

import net.minecraft.client.Minecraft;

/**
 * The two client accessors that moved between branches. 26.x moved the screen
 * onto Gui and renamed the window handle getter.
 */
public final class ScreenAccess {
	private ScreenAccess() {
	}

	public static boolean screenOpen(Minecraft mc) {
		return mc.gui.screen() != null;
	}

	public static long windowHandle(Minecraft mc) {
		return mc.getWindow().handle();
	}
}
