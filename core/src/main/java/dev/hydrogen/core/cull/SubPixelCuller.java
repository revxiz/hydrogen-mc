package dev.hydrogen.core.cull;

import dev.hydrogen.core.hw.Budget;
import dev.hydrogen.core.hw.DisplayInfo;
import dev.hydrogen.core.hw.Tuning;

/**
 * Screen-space size test. An object that covers less than a physical pixel cannot
 * change what the monitor shows, so the draw call is dropped before the driver
 * sees it.
 *
 * Projected height in pixels for a perspective camera:
 *   px = size / distance * (viewportHeight / (2 * tan(fovY / 2)))
 *
 * viewportHeight is the live framebuffer height, including any active DRS scale,
 * so the test follows the resolution the world is actually drawn at.
 */
public final class SubPixelCuller {
	private final Budget budget;

	private volatile double focalPx = 540.0D;
	private volatile int viewportHeight = 1080;

	private long tested;
	private long culled;

	public SubPixelCuller(Budget budget) {
		this.budget = budget;
	}

	/**
	 * Recomputed whenever the window resizes, the FOV changes or DRS moves.
	 *
	 * @param display        probed display, used for the DPI-aware pixel threshold
	 * @param renderScale    active DRS scale
	 * @param fovDegreesY    vertical field of view
	 */
	public void updateProjection(DisplayInfo display, double renderScale, double fovDegreesY) {
		int h = Math.max(1, (int) Math.round(display.framebufferHeight() * Math.max(0.1D, renderScale)));
		double fov = Math.toRadians(Math.max(1.0D, Math.min(179.0D, fovDegreesY)));
		this.viewportHeight = h;
		this.focalPx = h / (2.0D * Math.tan(fov * 0.5D));
	}

	public int viewportHeight() {
		return viewportHeight;
	}

	public double projectedPixels(double sizeBlocks, double distanceBlocks) {
		if (distanceBlocks <= 0.001D) {
			return Double.MAX_VALUE;
		}

		return sizeBlocks / distanceBlocks * focalPx;
	}

	public boolean enabled() {
		return budget.tuning().subPixel();
	}

	/**
	 * @param sizeBlocks     largest bounding box dimension
	 * @param distanceBlocks camera distance
	 * @return true when the object can be skipped this frame
	 */
	public boolean shouldCull(double sizeBlocks, double distanceBlocks) {
		return shouldCullSq(sizeBlocks, distanceBlocks * distanceBlocks);
	}

	/**
	 * Same test without the square root: {@code size * focal < threshold * distance}
	 * compared in squares, since both sides are positive.
	 */
	public boolean shouldCullSq(double sizeBlocks, double distanceSq) {
		Tuning t = budget.tuning();
		double min = t.subPixelMinDistance();

		if (!t.subPixel() || distanceSq < min * min || !(sizeBlocks > 0.0D)) {
			return false;
		}

		tested++;
		double projected = sizeBlocks * focalPx;
		double limit = t.subPixelThreshold();
		boolean cull = projected * projected < limit * limit * distanceSq;

		if (cull) {
			culled++;
		}

		return cull;
	}

	public long tested() {
		return tested;
	}

	public long culled() {
		return culled;
	}

	public double cullRatio() {
		return tested == 0L ? 0.0D : (double) culled / tested;
	}

	public void resetCounters() {
		tested = 0L;
		culled = 0L;
	}
}
