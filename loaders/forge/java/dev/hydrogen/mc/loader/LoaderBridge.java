package dev.hydrogen.mc.loader;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

	public static String loaderVersion() {
		for (String[] m : modInfo()) {
			if (m[0].equals("forge")) {
				return m[1];
			}
		}

		return null;
	}

	public static String minecraftVersion() {
		for (String[] m : modInfo()) {
			if (m[0].equals("minecraft")) {
				return m[1];
			}
		}

		return null;
	}

	public static List<String> mods() {
		List<String> out = new ArrayList<>();

		for (String[] m : modInfo()) {
			out.add(m[0] + " " + m[1]);
		}

		out.sort(null);
		return out;
	}

	/**
	 * Id and version of every loaded mod. Read through the public mod info
	 * interface by reflection, so a change in the loader's own class layout
	 * between Minecraft branches costs the console its mod list, not the game.
	 */
	private static List<String[]> modInfo() {
		List<String[]> out = new ArrayList<>();

		try {
			Class<?> listClass = Class.forName("net.minecraftforge.fml.ModList");
			Object list = listClass.getMethod("get").invoke(null);

			if (list == null) {
				return out;
			}

			for (Object info : (List<?>) listClass.getMethod("getMods").invoke(list)) {
				Object id = publicCall(info, "getModId");
				Object version = publicCall(info, "getVersion");

				if (id != null) {
					out.add(new String[] {String.valueOf(id), String.valueOf(version)});
				}
			}
		} catch (Throwable t) {
			// Too early, or a loader without ModList; the console just shows no mods.
		}

		return out;
	}

	private static Object publicCall(Object target, String name) throws ReflectiveOperationException {
		for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
			for (Class<?> i : c.getInterfaces()) {
				try {
					return i.getMethod(name).invoke(target);
				} catch (NoSuchMethodException ignored) {
					// Try the next interface.
				}
			}
		}

		return target.getClass().getMethod(name).invoke(target);
	}
}
