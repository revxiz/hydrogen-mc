package dev.hydrogen.mc.loader;

import net.fabricmc.api.EnvType;
import org.quiltmc.loader.api.ModContainer;
import org.quiltmc.loader.api.QuiltLoader;
import org.quiltmc.loader.api.minecraft.MinecraftQuiltLoader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The few loader facts Hydrogen needs, answered by Quilt Loader. Quilt also
 * reports Fabric mods here, so Sodium and friends are seen either way.
 */
public final class LoaderBridge {
	private LoaderBridge() {
	}

	public static String name() {
		return "Quilt";
	}

	public static Path configDir() {
		return QuiltLoader.getConfigDir();
	}

	/** Safe from a mixin config plugin, before any game class loads. */
	public static boolean isModLoaded(String id) {
		return QuiltLoader.isModLoaded(id);
	}

	public static boolean physicalClient() {
		return MinecraftQuiltLoader.getEnvironmentType() == EnvType.CLIENT;
	}

	public static String loaderVersion() {
		return version("quilt_loader");
	}

	/** The exact release running, which can be any version inside the jar's range. */
	public static String minecraftVersion() {
		return version("minecraft");
	}

	/** "id version" for every loaded mod, Fabric mods included. */
	public static List<String> mods() {
		List<String> out = new ArrayList<>();

		for (ModContainer c : QuiltLoader.getAllMods()) {
			String id = c.metadata().id();

			if (!id.equals("java")) {
				out.add(id + " " + c.metadata().version().raw());
			}
		}

		out.sort(null);
		return out;
	}

	private static String version(String id) {
		return QuiltLoader.getModContainer(id).map(c -> c.metadata().version().raw()).orElse(null);
	}
}
