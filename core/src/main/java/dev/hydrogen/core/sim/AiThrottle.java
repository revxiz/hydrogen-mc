package dev.hydrogen.core.sim;

import dev.hydrogen.core.hw.Budget;
import dev.hydrogen.core.hw.Tuning;

/**
 * Distance-based AI throttling for passive mobs.
 *
 * This thins AI rather than switching it off. Beyond the threshold a passive mob
 * runs its goal selector one tick in N instead of never, so it still wanders,
 * still paths and still reacts, just less often. Skipping outright is what breaks
 * farms and leaves animals frozen mid-path, and the CPU saving between "one in
 * four" and "never" is not worth that.
 *
 * Off by default. This and the hopper throttle are the only parts of Hydrogen
 * that change simulation behaviour rather than presentation.
 */
public final class AiThrottle {
	private final Budget budget;

	private long evaluated;
	private long throttled;

	public AiThrottle(Budget budget) {
		this.budget = budget;
	}

	public boolean enabled() {
		Tuning t = budget.tuning();
		return t.enabled() && t.aiThrottle();
	}

	public double distance() {
		return budget.tuning().aiDistance();
	}

	public int interval() {
		return budget.tuning().aiInterval();
	}

	/**
	 * Cheap half of the decision, checked before any world query. A mob that will
	 * run its AI this tick anyway does not need a nearest-player search.
	 *
	 * @param tickCount the mob's own age, used to stagger the work
	 * @return true when this tick could be skipped if no player is near
	 */
	public boolean candidate(int tickCount) {
		evaluated++;
		return tickCount % interval() != 0;
	}

	/** Called after the world query confirmed nobody is in range. */
	public void recordSkip() {
		throttled++;
	}

	/**
	 * Full decision in one call, kept for callers that already know the distance.
	 *
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

		double d = distance();

		if (playerDistanceSq >= 0.0D && playerDistanceSq < d * d) {
			return false;
		}

		if (!candidate(tickCount)) {
			return false;
		}

		recordSkip();
		return true;
	}

	public long evaluated() {
		return evaluated;
	}

	public long throttled() {
		return throttled;
	}
}
