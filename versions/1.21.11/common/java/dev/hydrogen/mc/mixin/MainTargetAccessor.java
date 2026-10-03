package dev.hydrogen.mc.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The main render target still lives on Minecraft up to 26.1.x. */
@Mixin(Minecraft.class)
public interface MainTargetAccessor {
	@Accessor("mainRenderTarget")
	RenderTarget hydrogen$mainTarget();

	@Mutable
	@Accessor("mainRenderTarget")
	void hydrogen$setMainTarget(RenderTarget target);
}
