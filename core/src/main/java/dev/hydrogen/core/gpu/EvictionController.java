package dev.hydrogen.core.gpu;

import dev.hydrogen.core.config.HConfig;
import dev.hydrogen.core.hw.Budget;

/**
 * Turns VRAM readings into eviction decisions. The GL work lives in the version
 * modules; this owns the thresholds, hysteresis and cooldown only.
 */
public final class EvictionController {
	public enum Action {
		NONE,
		/** Release textures untouched for a while. */
		SOFT,
		/** Release textures, trim render distance and drop resolution. */
		HARD
	}

	private final HConfig config;
	private final Budget budget;

	private long lastPassMs;
	private int softPasses;
	private int hardPasses;
	private long releasedKb;

	public EvictionController(HConfig config, Budget budget) {
		this.config = config;
		this.budget = budget;
	}

	public Action decide(VramSnapshot vram, long nowMs) {
		if (!config.bool("vram.enabled") || !vram.known()) {
			return Action.NONE;
		}

		double used = vram.usedPercent();
		double evictAt = budget.vramEvictPercent();

		if (used < evictAt) {
			return Action.NONE;
		}

		// The driver already spilling to system RAM is the clearest distress signal.
		boolean hard = used >= Math.min(99.0D, evictAt + 6.0D) || vram.evictedKb() > 0L;
		long cooldown = (long) (budget.targetFrameMs() * (hard ? 240.0D : 90.0D));

		if (nowMs - lastPassMs < cooldown) {
			return Action.NONE;
		}

		lastPassMs = nowMs;

		if (hard) {
			hardPasses++;
			return Action.HARD;
		}

		softPasses++;
		return Action.SOFT;
	}

	/** How much memory the pass should try to free, in kilobytes. */
	public long targetReleaseKb(VramSnapshot vram) {
		long wantUsed = (long) (vram.totalKb() * budget.vramReleaseTargetPercent() / 100.0D);
		return Math.max(0L, vram.usedKb() - wantUsed);
	}

	public double idleSeconds() {
		return budget.vramTextureIdleSeconds();
	}

	public long minTextureBytes() {
		return (long) config.fixed("vram.minTextureBytes");
	}

	public void recordRelease(long kb) {
		releasedKb += Math.max(0L, kb);
	}

	public int softPasses() {
		return softPasses;
	}

	public int hardPasses() {
		return hardPasses;
	}

	public long releasedKb() {
		return releasedKb;
	}
}
