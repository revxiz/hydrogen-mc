package dev.hydrogen.mc;

import net.minecraft.client.Minecraft;

/** Re-queues a section rebuild that was postponed. */
public final class SectionDirtyBridge {
	private SectionDirtyBridge() {
	}

	public static boolean supported() {
		return true;
	}

	public static void markDirty(Minecraft mc, int x, int y, int z) {
		mc.levelRenderer.setSectionDirty(x, y, z);
	}
}
