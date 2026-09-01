package dev.hydrogen.core.gpu;

import dev.hydrogen.core.config.HConfig;
import dev.hydrogen.core.frame.FrameStats;
import dev.hydrogen.core.hw.Budget;

/**
 * Decides the 3D viewport scale. The HUD is never scaled, so the only thing that
 * moves is the internal world resolution.
 *
 * Scaling down is immediate because a dropped frame is already lost. Scaling back
 * up waits for a quiet period so the picture does not pulse. Both the step size
 * and the pressure thresholds come from the probed display and GPU.
 */
public final class ResolutionController {
	private final HConfig config;
	private final Budget budget;

	private double scale = 1.0D;
	private long lastChangeMs;
	private long calmSinceMs;
	private int downshifts;
	private int upshifts;
	private String reason = "native";

	public ResolutionController(HConfig config, Budget budget) {
		this.config = config;
		this.budget = budget;
	}

	public double scale() {
		return config.bool("drs.enabled") ? scale : 1.0D;
	}

	public boolean scaling() {
		return scale() < 0.999D;
	}

	public String reason() {
		return reason;
	}

	public int downshifts() {
		return downshifts;
	}

	public int upshifts() {
		return upshifts;
	}

	public void update(FrameStats frame, VramSnapshot vram, long nowMs) {
		if (!config.bool("drs.enabled")) {
			scale = 1.0D;
			reason = "off";
			return;
		}

		double min = budget.drsMinScale();
		double max = budget.drsMaxScale();
		double step = budget.drsStep();
		double budgetMs = budget.targetFrameMs() * budget.toleranceFactor();

		boolean vramPressure = vram.known() && vram.usedPercent() >= budget.drsVramHighPercent();
		boolean gpuPressure = frame.usable() && frame.p95Ms() > budgetMs;

		if (vramPressure || gpuPressure) {
			calmSinceMs = 0L;

			if (nowMs - lastChangeMs < 250L || scale <= min + 1.0E-6D) {
				if (scale <= min + 1.0E-6D) {
					reason = "at floor " + Math.round(min * 100.0D) + "%";
				}

				return;
			}

			scale = Math.max(min, round(scale - step));
			lastChangeMs = nowMs;
			downshifts++;
			reason = vramPressure
					? "vram " + Math.round(vram.usedPercent()) + "%"
					: String.format("gpu %.1fms", frame.p95Ms());
			return;
		}

		if (scale >= max - 1.0E-6D) {
			scale = max;
			reason = "native";
			return;
		}

		// Require sustained headroom before giving resolution back.
		if (!frame.usable() || frame.p95Ms() > budgetMs * 0.82D) {
			calmSinceMs = 0L;
			return;
		}

		if (calmSinceMs == 0L) {
			calmSinceMs = nowMs;
			return;
		}

		if (nowMs - calmSinceMs < (long) (config.fixed("drs.recoverySeconds") * 1000.0D)) {
			return;
		}

		scale = Math.min(max, round(scale + step));
		lastChangeMs = nowMs;
		calmSinceMs = nowMs;
		upshifts++;
		reason = "recovering";
	}

	/** Drops resolution at once when the evictor is fighting for memory. */
	public void emergencyDrop(long nowMs) {
		double min = budget.drsMinScale();

		if (scale > min) {
			scale = Math.max(min, round(scale - budget.drsStep() * 2.0D));
			lastChangeMs = nowMs;
			calmSinceMs = 0L;
			downshifts++;
			reason = "vram critical";
		}
	}

	public void reset() {
		scale = 1.0D;
		calmSinceMs = 0L;
		reason = "native";
	}

	private static double round(double v) {
		return Math.round(v * 1000.0D) / 1000.0D;
	}
}
