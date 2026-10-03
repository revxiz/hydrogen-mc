package dev.hydrogen.mc.gl;

import dev.hydrogen.core.HLog;
import dev.hydrogen.core.hw.DisplayInfo;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_DisplayMode;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

/**
 * Reads the live output surface from SDL3, which replaced GLFW as Minecraft's
 * window library in 26.3: framebuffer size, the refresh rate of the display the
 * window sits on, and the OS display scale used for DPI awareness.
 *
 * Same contract as the GLFW probe, so nothing above this class changes.
 */
public final class DisplayProbe {
	private DisplayProbe() {
	}

	/**
	 * @param windowHandle SDL_Window pointer
	 * @param guiScale     effective Minecraft GUI scale
	 * @param frameLimit   in-game frame cap, 0 for unlimited
	 * @param vsync        vertical sync setting
	 */
	public static DisplayInfo probe(long windowHandle, double guiScale, int frameLimit, boolean vsync) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer w = stack.mallocInt(1);
			IntBuffer h = stack.mallocInt(1);
			int fbW = 1;
			int fbH = 1;

			if (SDLVideo.SDL_GetWindowSizeInPixels(windowHandle, w, h)) {
				fbW = Math.max(1, w.get(0));
				fbH = Math.max(1, h.get(0));
			}

			// SDL already answers "which display holds most of this window", which
			// GLFW left to us in windowed mode.
			int display = SDLVideo.SDL_GetDisplayForWindow(windowHandle);
			int refresh = 0;
			int monW = fbW;
			int monH = fbH;

			if (display != 0) {
				SDL_DisplayMode mode = SDLVideo.SDL_GetCurrentDisplayMode(display);

				if (mode != null) {
					refresh = Math.round(mode.refresh_rate());
					monW = mode.w();
					monH = mode.h();
				}
			}

			float scale = SDLVideo.SDL_GetWindowDisplayScale(windowHandle);
			double contentScale = scale > 0.0F ? scale : 1.0D;

			return new DisplayInfo(
					fbW,
					fbH,
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
}
