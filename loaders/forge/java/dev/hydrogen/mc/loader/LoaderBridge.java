package dev.hydrogen.mc.loader;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;

/** The few loader facts Hydrogen needs, answered by Forge's FML. */
public final class LoaderBridge {
	private LoaderBridge() {
	}

	public static String name() {
		return "Forge";
	}

	public static Path configDir() {
		return FMLPaths.CONFIGDIR.get();
	}

	/**
	 * Uses the loading mod list, which is filled before mixin configs are read,
	 * so this also works from the mixin plugin. Forge 1.20.1 to 1.21.11 expose it
	 * through {@code LoadingModList.get()}, 26.x made the lookup static, so the one
	 * method is found reflectively and works on both.
	 */
	public static boolean isModLoaded(String id) {
		try {
			Class<?> list = Class.forName("net.minecraftforge.fml.loading.LoadingModList");
			Method byId = list.getMethod("getModFileById", String.class);
			Object target = null;

			if (!Modifier.isStatic(byId.getModifiers())) {
				target = list.getMethod("get").invoke(null);

				if (target == null) {
					return false;
				}
			}

			return byId.invoke(target, id) != null;
		} catch (Throwable t) {
			return false;
		}
	}

	public static boolean physicalClient() {
		return FMLEnvironment.dist == Dist.CLIENT;
	}
}
