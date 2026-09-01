package dev.hydrogen.core.tune;

/**
 * Result of the calibration pass.
 *
 * @param p50Ms         median frame time at baseline
 * @param p95Ms         95th percentile frame time
 * @param p99Ms         99th percentile frame time
 * @param jitterMs      standard deviation of frame time
 * @param churnMbPerSec heap allocation rate measured over the window
 * @param vramGrowthKb  video memory the scene claimed during the window
 * @param samples       frames collected
 */
public record Baseline(
		double p50Ms,
		double p95Ms,
		double p99Ms,
		double jitterMs,
		double churnMbPerSec,
		long vramGrowthKb,
		int samples) {

	public static final Baseline NONE = new Baseline(0, 0, 0, 0, 0, 0, 0);

	public boolean valid() {
		return samples >= 60 && p50Ms > 0.0D;
	}

	/**
	 * How much headroom the machine has against a target frame time.
	 * Above 1.0 means it comfortably beats the target.
	 */
	public double headroom(double targetMs) {
		return valid() && p95Ms > 0.0D ? targetMs / p95Ms : 1.0D;
	}

	public String describe(double targetMs) {
		return String.format(
				"baseline p50 %.2fms p95 %.2fms jitter %.2fms churn %.0fMB/s headroom %.2fx",
				p50Ms, p95Ms, jitterMs, churnMbPerSec, headroom(targetMs));
	}
}
