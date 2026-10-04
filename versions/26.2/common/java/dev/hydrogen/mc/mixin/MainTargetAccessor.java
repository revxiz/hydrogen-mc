package dev.hydrogen.mc.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Since 26.2 the main render target lives on GameRenderer. */
@Mixin(GameRenderer.class)
public interface MainTargetAccessor {
	@Accessor("mainRenderTarget")
	RenderTarget hydrogen$mainTarget();

	@Mutable
	@Accessor("mainRenderTarget")
	void hydrogen$setMainTarget(RenderTarget target);
}
