package dev.hydrogen.mc.audio;

import dev.hydrogen.core.Hydrogen;
import dev.hydrogen.core.audio.SoundGate;
import dev.hydrogen.mc.mixin.ChannelAccessAccessor;
import dev.hydrogen.mc.mixin.SoundEngineAccessor;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;

/**
 * Shared decision for both SoundEngine.play variants. 1.21.9 changed the return
 * type of play, so the mixin differs per branch but the logic does not.
 */
public final class SoundGating {
	private SoundGating() {
	}

	public static boolean shouldCull(SoundInstance sound, SoundEngine engine) {
		Hydrogen h = Hydrogen.get();

		if (h == null || !h.enabled() || sound == null) {
			return false;
		}

		SoundGate gate = h.soundGate();

		if (!gate.enabled()) {
			return false;
		}

		// Relative sounds are attached to the listener, so distance never applies.
		double distanceSq = sound.isRelative()
				? 0.0D
				: h.cone().distanceSqTo(sound.getX(), sound.getY(), sound.getZ());

		return gate.shouldCull(tierOf(sound), distanceSq, activeChannels(engine));
	}

	/**
	 * Matched on the enum name rather than the constants, so a category added or
	 * renamed in a future version falls through to the middle tier instead of
	 * failing to compile.
	 */
	private static int tierOf(SoundInstance sound) {
		String source = sound.getSource() == null ? "" : sound.getSource().name();

		return switch (source) {
			case "PLAYERS", "HOSTILE" -> SoundGate.TIER_CRITICAL;
			case "AMBIENT", "MUSIC", "WEATHER", "RECORDS" -> SoundGate.TIER_AMBIENT;
			default -> SoundGate.TIER_NORMAL;
		};
	}

	private static int activeChannels(SoundEngine engine) {
		try {
			ChannelAccess access = ((SoundEngineAccessor) engine).hydrogen$channelAccess();
			return access == null ? 0 : ((ChannelAccessAccessor) access).hydrogen$channels().size();
		} catch (Throwable t) {
			return 0; // Unknown pressure reads as empty, so nothing gets culled.
		}
	}
}
