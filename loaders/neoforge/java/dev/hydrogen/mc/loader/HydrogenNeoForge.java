package dev.hydrogen.mc.loader;

import dev.hydrogen.mc.HydrogenBoot;
import net.neoforged.fml.common.Mod;

/**
 * NeoForge entry point. Hydrogen registers nothing on the mod bus: all of its
 * hooks are mixins, so construction is the only moment it needs.
 */
@Mod("hydrogen")
public final class HydrogenNeoForge {
	public HydrogenNeoForge() {
		HydrogenBoot.boot();
	}
}
