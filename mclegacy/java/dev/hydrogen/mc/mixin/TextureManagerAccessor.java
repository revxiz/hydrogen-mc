package dev.hydrogen.mc.mixin;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Read access to the texture registry so the evictor can walk it. */
@Mixin(TextureManager.class)
public interface TextureManagerAccessor {
	@Accessor("byPath")
	Map<ResourceLocation, AbstractTexture> hydrogen$byPath();
}
