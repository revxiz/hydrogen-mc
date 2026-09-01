package dev.hydrogen.mc.platform;

import dev.hydrogen.core.HLog;
import org.lwjgl.system.Platform;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.linux.LinuxLibrary;
import org.lwjgl.system.macosx.MacOSXLibrary;
import org.lwjgl.system.windows.WindowsLibrary;

/**
 * Dynamic symbol lookup through LWJGL's loader, which the game already ships.
 * Hydrogen bundles no native libraries of its own.
 *
 * Every entry point returns 0 or null instead of throwing, so a locked-down
 * machine degrades to JVM-level tuning rather than crashing.
 */
final class Natives {
	private Natives() {
	}

	/** Opens the first library that loads. Returns null when none do. */
	static SharedLibrary open(String... names) {
		for (String name : names) {
			try {
				SharedLibrary lib = switch (Platform.get()) {
					case WINDOWS -> openWindows(name);
					case LINUX -> openLinux(name);
					case MACOSX -> openMac(name);
					// Newer LWJGL builds add platforms; treat them as unsupported.
					default -> null;
				};

				if (lib != null) {
					return lib;
				}
			} catch (Throwable ignored) {
				// Try the next candidate name.
			}
		}

		return null;
	}

	private static SharedLibrary openWindows(String name) {
		return new WindowsLibrary(name);
	}

	private static SharedLibrary openLinux(String name) {
		return new LinuxLibrary(name);
	}

	private static SharedLibrary openMac(String name) {
		return MacOSXLibrary.create(name);
	}

	/** Function address, or 0 when the symbol is absent. */
	static long fn(SharedLibrary lib, String name) {
		if (lib == null) {
			return 0L;
		}

		try {
			return lib.getFunctionAddress(name);
		} catch (Throwable t) {
			HLog.warnOnce("sym-" + name, "Hydrogen: symbol " + name + " unavailable", t);
			return 0L;
		}
	}

	static boolean has(long... addresses) {
		for (long a : addresses) {
			if (a == 0L) {
				return false;
			}
		}

		return true;
	}
}
