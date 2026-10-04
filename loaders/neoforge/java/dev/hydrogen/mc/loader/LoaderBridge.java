package dev.hydrogen.mc.loader;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

	public static String loaderVersion() {
		for (String[] m : modInfo()) {
			if (m[0].equals("neoforge")) {
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
			Class<?> listClass = Class.forName("net.neoforged.fml.ModList");
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
