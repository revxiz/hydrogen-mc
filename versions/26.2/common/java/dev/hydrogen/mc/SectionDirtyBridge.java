package dev.hydrogen.mc;

import net.minecraft.client.Minecraft;

/**
 * 26.x reworked how sections are invalidated and no longer exposes a public
 * per-section dirty call, so deferral is not offered on this branch. Chunk
 * ordering still applies through the cone-weighted queue metric, which is the
 * stronger half of the feature anyway.
 */
public final class SectionDirtyBridge {
	private SectionDirtyBridge() {
	}

	public static boolean supported() {
		return false;
	}

	public static void markDirty(Minecraft mc, int x, int y, int z) {
	}
}
