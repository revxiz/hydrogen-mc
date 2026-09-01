package dev.hydrogen.mc;

import dev.hydrogen.core.HLog;
import dev.hydrogen.core.Hydrogen;
import dev.hydrogen.core.compat.RenderBackend;
import dev.hydrogen.mc.compat.ModProbe;
import dev.hydrogen.mc.platform.Platforms;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.nio.file.Path;

/**
 * Runs before any Minecraft class is loaded, which is the earliest point a
 * Fabric mod can act. CPU topology, the config file and the GC listener are all
 * set up here so the very first frame is already governed.
 *
 * This is the "agent" role: instrumentation is installed around the game rather
 * than inside anyone else's renderer.
 */
public final class HydrogenPreLaunch implements PreLaunchEntrypoint {
	@Override
	public void onPreLaunch() {
		try {
			Path config = FabricLoader.getInstance().getConfigDir().resolve("hydrogen.properties");
			Hydrogen h = Hydrogen.boot(config, Platforms.detect());

			ModProbe.apply(h.compat());

			if (ModProbe.vulkanMod()) {
				h.compat().setBackend(RenderBackend.VULKAN);
			} else if (ModProbe.sodium()) {
				h.compat().setBackend(RenderBackend.SODIUM_GL);
			}

			HLog.LOG.info("Hydrogen pre-launch: renderer {}", h.compat().describe());

			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				try {
					h.shutdown();
				} catch (Throwable ignored) {
					// JVM is going down; nothing useful left to do.
				}
			}, "Hydrogen shutdown"));
		} catch (Throwable t) {
			HLog.warnOnce("prelaunch", "Hydrogen failed to start, the game continues untuned", t);
		}
	}
}
