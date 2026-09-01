package dev.hydrogen.core.frame;

/** Immutable view of the frame timeline, safe to hand to other threads. */
public record FrameStats(
		double lastMs,
		double smoothedMs,
		double p50Ms,
		double p95Ms,
		double p99Ms,
		double stallRatio,
		int samples) {

	public static final FrameStats EMPTY = new FrameStats(0, 0, 0, 0, 0, 0, 0);

	public boolean usable() {
		return samples >= 32;
	}
}
