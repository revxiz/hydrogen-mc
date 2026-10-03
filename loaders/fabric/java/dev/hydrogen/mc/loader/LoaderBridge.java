package dev.hydrogen.mc.loader;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/** The few loader facts Hydrogen needs, answered by Fabric Loader. */
public final class LoaderBridge {
	private LoaderBridge() {
	}

	public static String name() {
		return "Fabric";
	}

	public static Path configDir() {
		return FabricLoader.getInstance().getConfigDir();
	}

	/** Safe from a mixin config plugin, before any game class loads. */
	public static boolean isModLoaded(String id) {
		return FabricLoader.getInstance().isModLoaded(id);
	}

	public static boolean physicalClient() {
		return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
	}
}
