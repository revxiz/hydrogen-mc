package dev.hydrogen.core.sim;

import dev.hydrogen.core.hw.Budget;
import dev.hydrogen.core.hw.Tuning;

/**
 * Idle throttling for hoppers.
 *
 * A hopper with no container above it still runs an entity box query every tick
 * looking for items to pick up. In a storage room with hundreds of hoppers that
 * query is the measurable cost, not the cooldown decrement.
 *
 * Only that entity scan is thinned, and only while the hopper is empty. Pulling
 * from a chest above, pushing, cooldowns and the tick ordering hopper chains rely
 * on are all left to vanilla, so item sorters and hopper clocks keep their timing.
 * Worst case a dropped item lying on an empty hopper waits {@code interval - 1}
 * extra ticks before it is picked up.
 *
 * Off by default, because it changes pickup timing however slightly.
 */
public final class HopperThrottle {
	private final Budget budget;

	private long evaluated;
	private long skipped;

	public HopperThrottle(Budget budget) {
		this.budget = budget;
	}

	public boolean enabled() {
		Tuning t = budget.tuning();
		return t.enabled() && t.hopperThrottle();
	}

	public int interval() {
		return budget.tuning().hopperInterval();
	}

	/**
	 * @param empty        the hopper holds nothing
	 * @param gameTime     current level game time
	 * @param positionHash any stable per-hopper value, used to stagger the work
	 * @return true when this tick's item-entity scan can be skipped
	 */
	public boolean shouldSkipScan(boolean empty, long gameTime, int positionHash) {
		if (!enabled() || !empty) {
			return false;
		}

		evaluated++;

		// Spread the wake-ups so a whole storage room does not fire on one tick.
		if (Math.floorMod(gameTime + positionHash, interval()) == 0) {
			return false;
		}

		skipped++;
		return true;
	}

	public long evaluated() {
		return evaluated;
	}

	public long skipped() {
		return skipped;
	}
}
