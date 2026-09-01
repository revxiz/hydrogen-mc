package dev.hydrogen.core.tune;

import dev.hydrogen.core.HLog;
import dev.hydrogen.core.config.HConfig;
import dev.hydrogen.core.hw.Budget;

import java.util.Arrays;

/**
 * Silent benchmark that runs for a few seconds after the player enters a world.
 *
 * Nothing is drawn and no setting is touched while it runs: the point is to
 * capture how the machine behaves untuned, so every later threshold is measured
 * rather than assumed. Adaptive features stay parked until it finishes.
 */
public final class Calibrator {
	private enum Phase {
		IDLE,
		WARMUP,
		SAMPLING,
		DONE
	}

	private static final int MAX_SAMPLES = 4096;

	private final HConfig config;
	private final Budget budget;

	private Phase phase = Phase.IDLE;
	private long phaseStartMs;
	private final double[] samples = new double[MAX_SAMPLES];
	private int sampleCount;

	private long heapUsedLast;
	private double heapGrowthMb;
	private long vramFreeAtStartKb;
	private long vramFreeNowKb;
	private int runs;

	public Calibrator(HConfig config, Budget budget) {
		this.config = config;
		this.budget = budget;
	}

	/** Called when a level becomes visible. */
	public void request(long nowMs) {
		if (!config.bool("calibration.enabled")) {
			phase = Phase.DONE;
			return;
		}

		phase = Phase.WARMUP;
		phaseStartMs = nowMs;
		sampleCount = 0;
		heapGrowthMb = 0.0D;
		heapUsedLast = 0L;
		vramFreeAtStartKb = 0L;
	}

	public void onResize(long nowMs) {
		if (config.bool("calibration.recalibrateOnResize") && phase == Phase.DONE) {
			request(nowMs);
		}
	}

	public void abort() {
		if (phase == Phase.WARMUP || phase == Phase.SAMPLING) {
			phase = Phase.IDLE;
		}
	}

	/** True while adaptive features should hold still. */
	public boolean active() {
		return phase == Phase.WARMUP || phase == Phase.SAMPLING;
	}

	public boolean done() {
		return phase == Phase.DONE;
	}

	public double progress() {
		if (!active()) {
			return phase == Phase.DONE ? 1.0D : 0.0D;
		}

		double total = config.fixed("calibration.warmupSeconds") + config.fixed("calibration.seconds");
		double elapsed = (System.currentTimeMillis() - phaseStartMs) / 1000.0D;

		if (phase == Phase.SAMPLING) {
			elapsed += config.fixed("calibration.warmupSeconds");
		}

		return Math.max(0.0D, Math.min(1.0D, elapsed / Math.max(0.1D, total)));
	}

	public int runs() {
		return runs;
	}

	/**
	 * @param frameNanos    duration of the frame just finished
	 * @param heapUsedBytes current heap occupancy
	 * @param vramFreeKb    free video memory, 0 when unknown
	 */
	public void onFrame(long frameNanos, long nowMs, long heapUsedBytes, long vramFreeKb) {
		if (!active()) {
			return;
		}

		// Positive heap deltas approximate allocation; negative ones are collections.
		if (heapUsedLast > 0L && heapUsedBytes > heapUsedLast) {
			heapGrowthMb += (heapUsedBytes - heapUsedLast) / 1048576.0D;
		}

		heapUsedLast = heapUsedBytes;
		vramFreeNowKb = vramFreeKb;

		if (vramFreeAtStartKb == 0L) {
			vramFreeAtStartKb = vramFreeKb;
		}

		double elapsed = (nowMs - phaseStartMs) / 1000.0D;

		if (phase == Phase.WARMUP) {
			if (elapsed >= config.fixed("calibration.warmupSeconds")) {
				phase = Phase.SAMPLING;
				phaseStartMs = nowMs;
				heapGrowthMb = 0.0D;
			}

			return;
		}

		if (sampleCount < MAX_SAMPLES) {
			samples[sampleCount++] = frameNanos / 1_000_000.0D;
		}

		if (elapsed >= config.fixed("calibration.seconds")) {
			finish(elapsed);
		}
	}

	private void finish(double elapsedSeconds) {
		phase = Phase.DONE;
		runs++;

		if (sampleCount < 30) {
			HLog.LOG.info("Hydrogen: calibration collected only {} frames, keeping derived defaults", sampleCount);
			return;
		}

		double[] sorted = Arrays.copyOf(samples, sampleCount);
		Arrays.sort(sorted);

		double mean = 0.0D;

		for (int i = 0; i < sampleCount; i++) {
			mean += sorted[i];
		}

		mean /= sampleCount;

		double variance = 0.0D;

		for (int i = 0; i < sampleCount; i++) {
			double d = sorted[i] - mean;
			variance += d * d;
		}

		double jitter = Math.sqrt(variance / sampleCount);
		double churn = heapGrowthMb / Math.max(0.5D, elapsedSeconds);
		long vramGrowth = vramFreeAtStartKb > 0L && vramFreeNowKb > 0L
				? Math.max(0L, vramFreeAtStartKb - vramFreeNowKb)
				: 0L;

		Baseline baseline = new Baseline(
				pick(sorted, 0.50D),
				pick(sorted, 0.95D),
				pick(sorted, 0.99D),
				jitter,
				churn,
				vramGrowth,
				sampleCount);

		budget.setBaseline(baseline);
		HLog.LOG.info("Hydrogen: {}", baseline.describe(budget.targetFrameMs()));
		HLog.LOG.info("Hydrogen: tuned to {}", budget.describe());
	}

	private static double pick(double[] sorted, double q) {
		int idx = (int) Math.round(q * (sorted.length - 1));
		return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
	}
}
