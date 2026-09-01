package dev.hydrogen.core.hw;

/**
 * Everything Hydrogen knows about the output surface, probed from GLFW and the
 * game window rather than assumed.
 *
 * @param framebufferWidth  physical pixels the 3D scene is drawn at
 * @param framebufferHeight physical pixels
 * @param monitorWidth      active monitor mode width
 * @param monitorHeight     active monitor mode height
 * @param refreshHz         active monitor refresh rate
 * @param contentScale      OS DPI scale factor, 1.0 on a standard-density display
 * @param guiScale          effective Minecraft GUI scale
 * @param frameLimit        in-game frame cap, 0 when unlimited
 * @param vsync             vertical sync enabled
 */
public record DisplayInfo(
		int framebufferWidth,
		int framebufferHeight,
		int monitorWidth,
		int monitorHeight,
		int refreshHz,
		double contentScale,
		double guiScale,
		int frameLimit,
		boolean vsync) {

	public static final DisplayInfo UNKNOWN =
			new DisplayInfo(1920, 1080, 1920, 1080, 60, 1.0D, 2.0D, 0, true);

	public long pixels() {
		return (long) framebufferWidth * framebufferHeight;
	}

	/**
	 * Frames per second the client is actually aiming for.
	 * Vsync pins it to the panel; an explicit limiter below that wins.
	 */
	public int effectiveTargetHz() {
		int hz = refreshHz > 0 ? refreshHz : 60;

		if (frameLimit > 0 && frameLimit < 260) {
			return Math.min(hz, frameLimit);
		}

		// Uncapped and no vsync: still pace against the panel, there is nothing
		// to gain from frames the monitor cannot show.
		return hz;
	}

	public double targetFrameMs() {
		return 1000.0D / Math.max(1, effectiveTargetHz());
	}

	/** True for 1440p and above, where scaling the 3D viewport buys the most. */
	public boolean highResolution() {
		return pixels() >= 3_500_000L;
	}

	public String describe() {
		return framebufferWidth + "x" + framebufferHeight + " @ " + refreshHz + "Hz"
				+ (contentScale != 1.0D ? String.format(" (DPI %.2fx)", contentScale) : "")
				+ String.format(", target %.2fms", targetFrameMs());
	}
}
