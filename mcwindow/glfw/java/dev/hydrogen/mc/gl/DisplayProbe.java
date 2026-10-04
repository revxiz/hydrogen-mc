package dev.hydrogen.mc.gl;

import dev.hydrogen.core.HLog;
import dev.hydrogen.core.hw.DisplayInfo;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;

/**
 * Reads the live output surface from GLFW: framebuffer size, the active
 * monitor's refresh rate and the OS content scale used for DPI awareness.
 *
 * The caller passes the window handle and the game's own scale values, so this
 * class stays free of Minecraft types and is shared by every version module.
 */
public final class DisplayProbe {
	private DisplayProbe() {
	}

	/**
	 * @param windowHandle GLFW window handle
	 * @param guiScale     effective Minecraft GUI scale
	 * @param frameLimit   in-game frame cap, 0 for unlimited
	 * @param vsync        vertical sync setting
	 */
	public static DisplayInfo probe(long windowHandle, double guiScale, int frameLimit, boolean vsync) {
		try {
			int[] fbw = new int[1];
			int[] fbh = new int[1];
			GLFW.glfwGetFramebufferSize(windowHandle, fbw, fbh);

			long monitor = GLFW.glfwGetWindowMonitor(windowHandle);

			if (monitor == 0L) {
				monitor = bestMonitorFor(windowHandle);
			}

			int refresh = 0;
			int monW = fbw[0];
			int monH = fbh[0];

			if (monitor != 0L) {
				GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);

				if (mode != null) {
					refresh = mode.refreshRate();
					monW = mode.width();
					monH = mode.height();
				}
			}

			float[] sx = new float[1];
			float[] sy = new float[1];
			GLFW.glfwGetWindowContentScale(windowHandle, sx, sy);
			double contentScale = sx[0] > 0.0F ? sx[0] : 1.0D;

			return new DisplayInfo(
					Math.max(1, fbw[0]),
					Math.max(1, fbh[0]),
					monW,
					monH,
					refresh > 0 ? refresh : 60,
					contentScale,
					guiScale > 0.0D ? guiScale : 1.0D,
					Math.max(0, frameLimit),
					vsync);
		} catch (Throwable t) {
			HLog.warnOnce("display-probe", "Hydrogen: display probe failed, assuming 1080p60", t);
			return DisplayInfo.UNKNOWN;
		}
	}

	/**
	 * Windowed mode reports no monitor, so the one holding the largest slice of
	 * the window is used. That keeps the refresh target correct on mixed-rate
	 * multi-monitor setups.
	 */
	private static long bestMonitorFor(long windowHandle) {
		try {
			int[] wx = new int[1];
			int[] wy = new int[1];
			int[] ww = new int[1];
			int[] wh = new int[1];
			GLFW.glfwGetWindowPos(windowHandle, wx, wy);
			GLFW.glfwGetWindowSize(windowHandle, ww, wh);

			var monitors = GLFW.glfwGetMonitors();

			if (monitors == null) {
				return GLFW.glfwGetPrimaryMonitor();
			}

			long best = 0L;
			long bestArea = -1L;

			for (int i = 0; i < monitors.limit(); i++) {
				long m = monitors.get(i);
				GLFWVidMode mode = GLFW.glfwGetVideoMode(m);

				if (mode == null) {
					continue;
				}

				int[] mx = new int[1];
				int[] my = new int[1];
				GLFW.glfwGetMonitorPos(m, mx, my);

				long overlap = (long) overlap(wx[0], ww[0], mx[0], mode.width())
						* overlap(wy[0], wh[0], my[0], mode.height());

				if (overlap > bestArea) {
					bestArea = overlap;
					best = m;
				}
			}

			return best != 0L ? best : GLFW.glfwGetPrimaryMonitor();
		} catch (Throwable t) {
			return GLFW.glfwGetPrimaryMonitor();
		}
	}

	private static int overlap(int aPos, int aLen, int bPos, int bLen) {
		return Math.max(0, Math.min(aPos + aLen, bPos + bLen) - Math.max(aPos, bPos));
	}
}
