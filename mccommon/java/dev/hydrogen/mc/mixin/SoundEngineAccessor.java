package dev.hydrogen.mc.mixin;

import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reaches the engine's channel bookkeeping so pool pressure can be measured. */
@Mixin(SoundEngine.class)
public interface SoundEngineAccessor {
	@Accessor("channelAccess")
	ChannelAccess hydrogen$channelAccess();
}
