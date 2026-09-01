package dev.hydrogen.core.sim;

import dev.hydrogen.core.config.HConfig;

/**
 * Distance-based AI throttling for passive mobs.
 *
 * This thins AI rather than switching it off. Beyond the threshold a passive mob
 * runs its goal selector one tick in N instead of never, so it still wanders,
 * still paths and still reacts, just less often. Skipping outright is what breaks
 * farms and leaves animals frozen mid-path, and the CPU saving between "one in
 * four" and "never" is not worth that.
 *
 * Off by default. This is the only part of Hydrogen that changes simulation
 * behaviour rather than presentation.
 */
public final class AiThrottle {
	private final HConfig config;

	private long evaluated;
	private long throttled;

	public AiThrottle(HConfig config) {
		this.config = config;
	}

	public boolean enabled() {
		return config.bool("ai.throttle.enabled");
	}

	public double distance() {
		return Math.max(16.0D, config.fixed("ai.throttle.distance"));
	}

	public int interval() {
		return Math.max(2, (int) config.fixed("ai.throttle.interval"));
	}

	/**
	 * @param hostile          mob implements Enemy, or is otherwise dangerous
	 * @param hasTarget        mob is currently tracking something
	 * @param playerDistanceSq squared distance to the nearest player, negative when none
	 * @param tickCount        the mob's own age, used to stagger the work
	 * @return true when the goal selector and navigation should sit this tick out
	 */
	public boolean shouldSkip(boolean hostile, boolean hasTarget, double playerDistanceSq, int tickCount) {
		if (!enabled() || hostile || hasTarget) {
			return false;
		}

		evaluated++;

		double d = distance();

		// No player in range at all is the strongest case for thinning out.
		if (playerDistanceSq >= 0.0D && playerDistanceSq < d * d) {
			return false;
		}

		// Stagger by entity age so mobs do not all wake on the same tick.
		if (tickCount % interval() == 0) {
			return false;
		}

		throttled++;
		return true;
	}

	public long evaluated() {
		return evaluated;
	}

	public long throttled() {
		return throttled;
	}
}
