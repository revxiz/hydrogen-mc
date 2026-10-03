package dev.hydrogen.mc.loader;

import dev.hydrogen.mc.HydrogenBoot;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge entry point. Every Forge version from 1.20.1 to 26.x accepts a no-argument
 * mod constructor, and Hydrogen registers nothing on the event buses: all of its
 * hooks are mixins, so construction is the only moment it needs.
 */
@Mod("hydrogen")
public final class HydrogenForge {
	public HydrogenForge() {
		HydrogenBoot.boot();
	}
}
