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
 * Pushes idle GPU textures back to system RAM before the driver starts thrashing.
 *
 * Only plain single-file textures that nothing has drawn for the configured idle
 * time are released. Those are re-uploaded lazily the next time they are
 * requested, so the worst case is one late upload of something that was not on
 * screen. Atlases are never touched because the game cannot rebuild them on
 * demand, and neither are subclasses such as downloaded player skins, which
 * cannot be reloaded from resources at all.
 *
 * A hard pass additionally caps the effective render distance, which is the
 * largest single consumer of section geometry memory.
 */
public final class TextureEvictor {
	private TextureEvictor() {
	}

	public static void run(Minecraft mc, Hydrogen h, EvictionController.Action action, VramSnapshot vram) {
		try {
			int released = releaseIdle(mc, h);
			h.eviction().recordRelease(released);

			if (action == EvictionController.Action.HARD) {
				h.eviction().tightenCap(mc.options.getEffectiveRenderDistance());
			}

			if (released > 0 && h.config().bool("log.verbose")) {
				HLog.LOG.info("Hydrogen: released {} idle GPU textures at {}% VRAM",
						released, Math.round(vram.usedPercent()));
			}
		} catch (Throwable t) {
			HLog.warnOnce("evict", "Hydrogen: texture eviction failed, disabled for this session", t);
			h.config().override("vram.enabled", "false");
		}
	}

	private static int releaseIdle(Minecraft mc, Hydrogen h) {
		if (!(mc.getTextureManager() instanceof TextureManagerAccessor accessor)) {
			return 0;
		}

		long now = HydrogenClient.frameClockMs;
		long idleMs = (long) (h.eviction().idleSeconds() * 1000.0D);
		List<ResourceLocation> victims = new ArrayList<>();

		for (Map.Entry<ResourceLocation, AbstractTexture> e : accessor.hydrogen$byPath().entrySet()) {
			AbstractTexture texture = e.getValue();

			if (texture == null || texture.getClass() != SimpleTexture.class || !(texture instanceof TextureUse use)) {
				continue;
			}

			long last = use.hydrogen$lastUse();

			// 0 means it was never looked up since loading; leave those to the next pass.
			if (last > 0L && now - last >= idleMs) {
				victims.add(e.getKey());
			}
		}

		for (ResourceLocation id : victims) {
			mc.getTextureManager().release(id);
		}

		return victims.size();
	}
}
