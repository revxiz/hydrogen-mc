package dev.hydrogen.mc.loader;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

	public static String loaderVersion() {
		return version("fabricloader");
	}

	/** The exact release running, which can be any version inside the jar's range. */
	public static String minecraftVersion() {
		return version("minecraft");
	}

	/** "id version" for every mod the player installed, libraries bundled inside other mods left out. */
	public static List<String> mods() {
		List<String> out = new ArrayList<>();

		for (ModContainer c : FabricLoader.getInstance().getAllMods()) {
			String id = c.getMetadata().getId();

			if (id.equals("java") || c.getContainingMod().isPresent()) {
				continue;
			}

			out.add(id + " " + c.getMetadata().getVersion().getFriendlyString());
		}

		out.sort(null);
		return out;
	}

	private static String version(String id) {
		return FabricLoader.getInstance().getModContainer(id)
				.map(c -> c.getMetadata().getVersion().getFriendlyString())
				.orElse(null);
	}
}
