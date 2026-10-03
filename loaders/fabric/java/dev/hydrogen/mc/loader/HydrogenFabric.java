package dev.hydrogen.mc.loader;

import dev.hydrogen.mc.HydrogenBoot;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Fabric entry point. preLaunch runs before any Minecraft class is loaded, which
 * is the earliest point a Fabric mod can act. Everything after this is driven by
 * mixins, so Fabric API is not needed.
 */
public final class HydrogenFabric implements PreLaunchEntrypoint {
	@Override
	public void onPreLaunch() {
		HydrogenBoot.boot();
	}
}
