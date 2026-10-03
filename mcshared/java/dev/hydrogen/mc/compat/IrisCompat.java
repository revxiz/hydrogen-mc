package dev.hydrogen.mc.compat;

import java.lang.reflect.Method;

/**
 * Asks Iris (or its Forge port Oculus) through its public API whether a shader
 * pack is in use. Reflection keeps Iris optional; the lookup happens once.
 */
public final class IrisCompat {
	private static boolean resolved;
	private static Object api;
	private static Method inUse;

	private IrisCompat() {
	}

	public static boolean shadersActive() {
		if (!resolved) {
			resolved = true;

			try {
				Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
				api = c.getMethod("getInstance").invoke(null);
				inUse = c.getMethod("isShaderPackInUse");
			} catch (Throwable t) {
				api = null;
			}
		}

		if (api == null) {
			return false;
		}

		try {
			return Boolean.TRUE.equals(inUse.invoke(api));
		} catch (Throwable t) {
			return false;
		}
	}
}
