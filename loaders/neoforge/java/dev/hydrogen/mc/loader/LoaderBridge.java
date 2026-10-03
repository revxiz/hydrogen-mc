package dev.hydrogen.mc.loader;

import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;

import java.nio.file.Path;

/** The few loader facts Hydrogen needs, answered by NeoForge's FancyModLoader. */
public final class LoaderBridge {
	private LoaderBridge() {
	}

	public static String name() {
		return "NeoForge";
	}

	public static Path configDir() {
		return FMLPaths.CONFIGDIR.get();
	}

	/** The loading mod list is filled before mixin configs are read. */
	public static boolean isModLoaded(String id) {
		try {
			LoadingModList list = LoadingModList.get();
			return list != null && list.getModFileById(id) != null;
		} catch (Throwable t) {
			return false;
		}
	}

	public static boolean physicalClient() {
		return FMLEnvironment.getDist().isClient();
	}
}
