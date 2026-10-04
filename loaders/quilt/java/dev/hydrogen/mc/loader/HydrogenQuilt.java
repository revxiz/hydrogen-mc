package dev.hydrogen.mc.loader;

import dev.hydrogen.mc.HydrogenBoot;
import org.quiltmc.loader.api.ModContainer;
import org.quiltmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Quilt entry point, Quilt Loader's own pre-launch hook. Only Quilt Loader is
 * required: Hydrogen uses no QSL or Quilted Fabric API, which also lets it run on
 * Minecraft versions those libraries have not been ported to.
 */
public final class HydrogenQuilt implements PreLaunchEntrypoint {
	@Override
	public void onPreLaunch(ModContainer mod) {
		HydrogenBoot.boot();
	}
}
