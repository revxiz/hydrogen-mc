package dev.hydrogen.mc.platform;

import dev.hydrogen.core.HLog;
import dev.hydrogen.core.platform.NativePlatform;
import org.lwjgl.system.Platform;

/** Picks the implementation for the host OS, falling back to a no-op. */
public final class Platforms {
	private static volatile NativePlatform cached;

	private Platforms() {
	}

	public static NativePlatform detect() {
		NativePlatform p = cached;

		if (p != null) {
			return p;
		}

		synchronized (Platforms.class) {
			if (cached == null) {
				cached = create();
			}

			return cached;
		}
	}

	private static NativePlatform create() {
		// Dedicated servers ship without LWJGL, which every native call goes through.
		// That is expected, so it gets an info line rather than a stack of warnings.
		if (Platforms.class.getClassLoader().getResource("org/lwjgl/system/Platform.class") == null) {
			HLog.once("plat-no-lwjgl",
					"Hydrogen: no LWJGL in this process (dedicated server), using JVM thread priorities only");
			return new NoopPlatform("no LWJGL");
		}

		try {
			NativePlatform p = switch (Platform.get()) {
				case WINDOWS -> new WindowsPlatform();
				case LINUX -> new LinuxPlatform();
				case MACOSX -> new MacPlatform();
				// Newer LWJGL builds add platforms; run untuned rather than guess.
				default -> new NoopPlatform("unsupported OS");
			};

			if (!p.available()) {
				HLog.once("plat-degraded",
						"Hydrogen: native calls unavailable on " + p.name() + ", using JVM priorities only");
			}

			return p;
		} catch (Throwable t) {
			HLog.warnOnce("plat-init", "Hydrogen: platform layer failed to initialise", t);
			return new NoopPlatform("init failed");
		}
	}
}
