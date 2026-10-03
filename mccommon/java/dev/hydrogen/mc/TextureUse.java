package dev.hydrogen.mc;

/**
 * Last-use stamp added to every texture, so eviction only releases textures that
 * nothing has drawn for a while instead of whatever is loaded.
 */
public interface TextureUse {
	void hydrogen$touch(long nowMs);

	long hydrogen$lastUse();
}
