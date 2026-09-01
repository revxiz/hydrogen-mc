package dev.hydrogen.mc.mixin;

import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the hopper's cooldown so the throttle never fights vanilla timing. */
@Mixin(HopperBlockEntity.class)
public interface HopperAccessor {
	@Accessor("cooldownTime")
	int hydrogen$cooldownTime();
}
