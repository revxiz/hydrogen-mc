package dev.hydrogen.core.audio;

import dev.hydrogen.core.hw.Budget;
import dev.hydrogen.core.hw.Tuning;

/**
 * Priority gate for the OpenAL channel pool.
 *
 * Vanilla has no priority system: a creeper fuse and a distant cow compete for
 * the same 247 channels, and once the pool saturates every new sound is dropped
 * regardless of what it was. This keeps the pool from filling with noise so the
 * sounds that matter still have somewhere to go.
 *
 * Nothing is culled while there is headroom. Pressure only starts mattering when
 * the pool is genuinely close to full.
 */
public final class SoundGate {
	/** Sounds that carry gameplay information. Never culled. */
	public static final int TIER_CRITICAL = 0;
	/** Blocks, machines, neutral mobs. Culled only under heavy pressure. */
	public static final int TIER_NORMAL = 1;
	/** Ambience, music, weather. First to go. */
	public static final int TIER_AMBIENT = 2;

	private final Budget budget;

	private long considered;
	private long culled;
	private int peakChannels;

	public SoundGate(Budget budget) {
		this.budget = budget;
	}

	public boolean enabled() {
		return budget.tuning().audio();
	}

	public int poolSize() {
		return budget.tuning().audioPool();
	}

	/**
	 * @param tier           one of the TIER constants
	 * @param distanceSq     squared distance from the listener, 0 for relative sounds
	 * @param activeChannels channels currently held by the engine
	 * @return true when the sound should be dropped before it reaches the pool
	 */
	public boolean shouldCull(int tier, double distanceSq, int activeChannels) {
		Tuning t = budget.tuning();

		if (!t.audio() || tier == TIER_CRITICAL) {
			return false;
		}

		considered++;

		if (activeChannels > peakChannels) {
			peakChannels = activeChannels;
		}

		double load = (double) activeChannels / t.audioPool();

		if (load < t.audioPressure()) {
			return false;
		}

		double limitSq = t.audioCullDistanceSq();

		// Ambient goes first. Normal only starts dropping once the pool is nearly gone.
		if (tier == TIER_NORMAL) {
			if (load < 0.92D) {
				return false;
			}

			limitSq *= 2.25D;
		}

		if (distanceSq <= limitSq) {
			return false;
		}

		culled++;
		return true;
	}

	public double cullDistance() {
		return Math.sqrt(budget.tuning().audioCullDistanceSq());
	}

	public long considered() {
		return considered;
	}

	public long culled() {
		return culled;
	}

	public int peakChannels() {
		return peakChannels;
	}
}
