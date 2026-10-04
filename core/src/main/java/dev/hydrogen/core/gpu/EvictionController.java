package dev.hydrogen.core.gpu;

import dev.hydrogen.core.HLog;
import dev.hydrogen.core.config.HConfig;
import dev.hydrogen.core.hw.Budget;

/**
 * Turns VRAM readings into eviction decisions. The GL work lives in the version
 * modules; this owns the thresholds, hysteresis, cooldown and the temporary
 * render distance cap.
 *
 * The cap is a ceiling applied to the game's effective render distance for this
 * session only. The player's saved setting is never written, so quitting while
 * capped, or opening the video settings, cannot lose it.
 */
public final class EvictionController {
	public enum Action {
		NONE,
		/** Release textures nobody has drawn for a while. */
		SOFT,
		/** Release textures, cap render distance and drop resolution. */
		HARD
	}

	/** Never cap below this; the world turns into fog soup quickly under it. */
	private static final int MIN_CAP_CHUNKS = 6;

	private final HConfig config;
	private final Budget budget;

	private long lastPassMs;
	private long lastRelaxMs;
	private int softPasses;
	private int hardPasses;
	private long releasedTextures;
	private volatile int capChunks = -1;

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

	public void recordRelease(int textures) {
		releasedTextures += Math.max(0, textures);
	}

	// ------------------------------------------------------- render distance cap

	/** Current ceiling in chunks, or -1 when none is active. */
	public int renderDistanceCap() {
		return capChunks;
	}

	/** Tightens the ceiling by two chunks below what is in effect now. */
	public void tightenCap(int effectiveChunks) {
		if (!config.bool("vram.trimRenderDistance") || effectiveChunks <= MIN_CAP_CHUNKS) {
			return;
		}

		int next = Math.max(MIN_CAP_CHUNKS, effectiveChunks - 2);
		capChunks = next;
		HLog.LOG.info("Hydrogen: VRAM critical, render distance capped at {} for this session", next);
	}

	/**
	 * Gives one chunk back once there is real headroom again, at most every few
	 * seconds, and drops the cap entirely when it reaches the player's own setting.
	 * Runs whether or not an eviction pass happened, so recovery never stalls.
	 */
	public void relaxCap(VramSnapshot vram, int userChunks, long nowMs) {
		int cap = capChunks;

		if (cap < 0) {
			return;
		}

		if (!config.bool("vram.enabled") || !config.bool("vram.trimRenderDistance") || !vram.known()) {
			capChunks = -1;
			return;
		}

		if (nowMs - lastRelaxMs < 5_000L || vram.usedPercent() > budget.vramReleaseTargetPercent() - 5.0D) {
			return;
		}

		lastRelaxMs = nowMs;
		int next = cap + 1;
		capChunks = next >= userChunks ? -1 : next;
	}

	public void clearCap() {
		capChunks = -1;
	}

	public int softPasses() {
		return softPasses;
	}

	public int hardPasses() {
		return hardPasses;
	}

	public long releasedTextures() {
		return releasedTextures;
	}
}
