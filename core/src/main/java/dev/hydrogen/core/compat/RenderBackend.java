package dev.hydrogen.core.compat;

/** Which graphics path the game is actually drawing through. */
public enum RenderBackend {
	VANILLA_GL,
	SODIUM_GL,
	VULKAN,
	UNKNOWN;

	public boolean isGl() {
		return this == VANILLA_GL || this == SODIUM_GL;
	}
}
