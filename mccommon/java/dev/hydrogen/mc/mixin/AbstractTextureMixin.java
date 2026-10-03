package dev.hydrogen.mc.mixin;

import dev.hydrogen.mc.TextureUse;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Carries the {@link TextureUse} stamp. One long per texture, written on lookup. */
@Mixin(AbstractTexture.class)
public abstract class AbstractTextureMixin implements TextureUse {
	@Unique
	private long hydrogen$lastUseMs;

	@Override
	public void hydrogen$touch(long nowMs) {
		hydrogen$lastUseMs = nowMs;
	}

	@Override
	public long hydrogen$lastUse() {
		return hydrogen$lastUseMs;
	}
}
