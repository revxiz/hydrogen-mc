package dev.hydrogen.mc.mixin;

import net.minecraft.client.sounds.ChannelAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

/** Live channel handles, which is how many of the 247 slots are actually taken. */
@Mixin(ChannelAccess.class)
public interface ChannelAccessAccessor {
	@Accessor("channels")
	Set<?> hydrogen$channels();
}
