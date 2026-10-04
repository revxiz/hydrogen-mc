package dev.hydrogen.mc.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets the scaler swap which target the game treats as "main" for the length of
 * the world render, so every vanilla call that rebinds the main target during
 * that pass lands in the scaled one.
 */
@Mixin(Minecraft.class)
public interface MainTargetAccessor {
	@Accessor("mainRenderTarget")
	RenderTarget hydrogen$mainTarget();

	@Mutable
	@Accessor("mainRenderTarget")
	void hydrogen$setMainTarget(RenderTarget target);
}
