package dev.hydrogen.mc;

import dev.hydrogen.core.HLog;
import dev.hydrogen.core.Hydrogen;
import dev.hydrogen.core.gpu.EvictionController;
import dev.hydrogen.core.gpu.VramSnapshot;
import dev.hydrogen.mc.mixin.TextureManagerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pushes GPU textures back to system RAM before the driver starts thrashing.
 *
 * Only single-file textures are released. Those are re-uploaded lazily the next
 * time they are requested, so the worst case is one late upload. Block and item
 * atlases are never touched because the game cannot rebuild them on demand.
 *
 * A hard pass additionally trims render distance, which is the largest single
 * consumer of section geometry memory, and restores it once pressure clears.
 */
public final class TextureEvictor {
	private static int trimmedFrom = -1;

	private TextureEvictor() {
	}

	public static void run(Minecraft mc, Hydrogen h, EvictionController.Action action, VramSnapshot vram) {
		try {
			int released = releaseSimpleTextures(mc, h);

			if (action == EvictionController.Action.HARD) {
				trimRenderDistance(mc, h);
			} else {
				restoreRenderDistance(mc, h, vram);
			}

			if (released > 0 && h.config().bool("log.verbose")) {
				HLog.LOG.info("Hydrogen: released {} GPU textures at {}% VRAM",
						released, Math.round(vram.usedPercent()));
			}
		} catch (Throwable t) {
			HLog.warnOnce("evict", "Hydrogen: texture eviction failed, feature disabled", t);
			h.config().set("vram.enabled", "false");
		}
	}

	private static int releaseSimpleTextures(Minecraft mc, Hydrogen h) {
		Object manager = mc.getTextureManager();

		if (!(manager instanceof TextureManagerAccessor accessor)) {
			return 0;
		}

		Map<ResourceLocation, AbstractTexture> byPath = accessor.hydrogen$byPath();
		List<ResourceLocation> victims = new ArrayList<>();

		for (Map.Entry<ResourceLocation, AbstractTexture> e : byPath.entrySet()) {
			if (e.getValue() instanceof SimpleTexture) {
				victims.add(e.getKey());
			}
		}

		for (ResourceLocation id : victims) {
			mc.getTextureManager().release(id);
		}

		h.eviction().recordRelease(victims.size() * 64L);
		return victims.size();
	}

	private static void trimRenderDistance(Minecraft mc, Hydrogen h) {
		if (!h.config().bool("vram.trimRenderDistance")) {
			return;
		}

		int current = mc.options.renderDistance().get();

		if (current <= 6) {
			return;
		}

		if (trimmedFrom < 0) {
			trimmedFrom = current;
		}

		mc.options.renderDistance().set(Math.max(6, current - 2));
		HLog.LOG.info("Hydrogen: VRAM critical, render distance {} -> {}",
				current, mc.options.renderDistance().get());
	}

	private static void restoreRenderDistance(Minecraft mc, Hydrogen h, VramSnapshot vram) {
		if (trimmedFrom < 0 || !vram.known()) {
			return;
		}

		// Give it back only once there is real headroom again.
		if (vram.usedPercent() > h.budget().vramReleaseTargetPercent() - 5.0D) {
			return;
		}

		int restore = Math.min(trimmedFrom, mc.options.renderDistance().get() + 1);
		mc.options.renderDistance().set(restore);

		if (restore >= trimmedFrom) {
			trimmedFrom = -1;
		}
	}
}
