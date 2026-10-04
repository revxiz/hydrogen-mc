package dev.hydrogen.mc.compat;

import dev.hydrogen.core.compat.CompatState;
import dev.hydrogen.mc.loader.LoaderBridge;

/**
 * Reads what else is installed. Hydrogen only observes these mods: it never
 * replaces their shaders or pipelines, so detection exists to decide which of
 * its own hooks are safe to apply.
 */
public final class ModProbe {
	private ModProbe() {
	}

	public static boolean loaded(String id) {
		try {
			return LoaderBridge.isModLoaded(id);
		} catch (Throwable t) {
			return false;
		}
	}

	/** Sodium on Fabric and NeoForge, and its Forge ports. */
	public static boolean sodium() {
		return loaded("sodium") || loaded("embeddium") || loaded("rubidium");
	}

	public static boolean vulkanMod() {
		return loaded("vulkanmod");
	}

	/** Iris, or its Forge port Oculus. */
	public static boolean iris() {
		return loaded("iris") || loaded("oculus");
	}

	public static void apply(CompatState state) {
		state.setMods(sodium(), vulkanMod(), iris());
	}
}
