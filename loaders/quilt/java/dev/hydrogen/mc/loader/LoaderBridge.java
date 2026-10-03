package dev.hydrogen.mc.loader;

import net.fabricmc.api.EnvType;
import org.quiltmc.loader.api.QuiltLoader;
import org.quiltmc.loader.api.minecraft.MinecraftQuiltLoader;

import java.nio.file.Path;

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
}
