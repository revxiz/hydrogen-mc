package dev.hydrogen.mc.compat;

import dev.hydrogen.core.compat.CompatState;
import net.fabricmc.loader.api.FabricLoader;

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
			return FabricLoader.getInstance().isModLoaded(id);
		} catch (Throwable t) {
			return false;
		}
	}

	public static boolean sodium() {
		return loaded("sodium") || loaded("embeddium") || loaded("rubidium");
	}

	public static boolean vulkanMod() {
		return loaded("vulkanmod");
	}

	public static boolean iris() {
		return loaded("iris") || loaded("oculus");
	}

	public static boolean yacl() {
		return loaded("yet_another_config_lib_v3") || loaded("yet_another_config_lib");
	}

	public static boolean clothConfig() {
		return loaded("cloth-config") || loaded("cloth-config2");
	}

	public static void apply(CompatState state) {
		state.setMods(sodium(), vulkanMod(), iris());
	}
}
