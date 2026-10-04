package dev.hydrogen.mc;

import dev.hydrogen.core.HLog;
import dev.hydrogen.core.Hydrogen;
import dev.hydrogen.core.compat.RenderBackend;
import dev.hydrogen.mc.compat.ModProbe;
import dev.hydrogen.mc.console.LogTap;
import dev.hydrogen.mc.loader.LoaderBridge;
import dev.hydrogen.mc.platform.Platforms;

/**
 * Loader-neutral start-up, called from each loader's earliest entry point:
 * Fabric's preLaunch, Quilt's pre_launch, or the Forge and NeoForge mod
 * constructor. CPU topology, the config file and the GC listener are set up here
 * so the first frame is already governed.
 *
 * Nothing in this class may load a Minecraft class.
 */
public final class HydrogenBoot {
	private HydrogenBoot() {
	}

	public static void boot() {
		if (Hydrogen.get() != null) {
			return;
		}

		try {
			// First, so warnings from the rest of start-up are kept for the console.
			LogTap.install();

			boolean dedicated = !LoaderBridge.physicalClient();
			Hydrogen h = Hydrogen.boot(LoaderBridge.configDir().resolve("hydrogen.properties"),
					Platforms.detect(), dedicated);

			ModProbe.apply(h.compat());

			if (ModProbe.vulkanMod()) {
				h.compat().setBackend(RenderBackend.VULKAN);
			} else if (ModProbe.sodium()) {
				h.compat().setBackend(RenderBackend.SODIUM_GL);
			}

			HLog.LOG.info("Hydrogen started on {} ({}), renderer {}",
					LoaderBridge.name(), dedicated ? "dedicated server" : "client", h.compat().describe());

			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				try {
					h.shutdown();
				} catch (Throwable ignored) {
					// JVM is going down; nothing useful left to do.
				}
			}, "Hydrogen shutdown"));
		} catch (Throwable t) {
			HLog.warnOnce("boot", "Hydrogen failed to start, the game continues untuned", t);
		}
	}
}
