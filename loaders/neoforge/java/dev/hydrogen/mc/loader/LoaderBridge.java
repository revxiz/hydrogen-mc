package dev.hydrogen.mc.loader;

import net.neoforged.api.distmarker.Dist;
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

	/**
	 * NeoForge 21.1 exposes the side as the static field {@code dist}; later
	 * releases replaced it with {@code getDist()}. One jar per branch still reads
	 * it reflectively so a loader update inside the branch cannot break it.
	 */
	public static boolean physicalClient() {
		try {
			Object dist;

			try {
				dist = FMLEnvironment.class.getMethod("getDist").invoke(null);
			} catch (NoSuchMethodException e) {
				dist = FMLEnvironment.class.getField("dist").get(null);
			}

			return dist == Dist.CLIENT;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return LoaderBridge.class.getClassLoader().getResource("net/minecraft/client/Minecraft.class") != null;
		}
	}
}
