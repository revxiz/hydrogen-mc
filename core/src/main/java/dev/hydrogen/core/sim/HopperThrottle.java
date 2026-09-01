package dev.hydrogen.core.sim;

import dev.hydrogen.core.config.HConfig;

/**
 * Idle throttling for hoppers.
 *
 * A hopper that is empty and off cooldown still runs an entity box query above
 * itself looking for items to pull in. In a storage room with hundreds of
 * hoppers that query is the measurable cost, not the cooldown decrement.
 *
 * This throttles that query for idle hoppers instead of removing them from the
 * tick list. True removal means rewriting the chunk ticker, which is a large
 * amount of risk for a cost that is already small once the query is thinned.
 * Worst case an item waits {@code interval} extra ticks before being pulled in,
 * which at the default is a fifth of a second against a transfer cooldown of
 * eight ticks.
 *
 * Off by default, because it changes item timing however slightly.
 */
public final class HopperThrottle {
	private final HConfig config;

	private long evaluated;
	private long skipped;

	public HopperThrottle(HConfig config) {
		this.config = config;
	}

	public boolean enabled() {
		return config.bool("hopper.throttle.enabled");
	}

	public int interval() {
		return Math.max(2, (int) config.fixed("hopper.throttle.interval"));
	}

	/**
	 * @param empty          the hopper holds nothing
	 * @param onCooldown     vanilla still has cooldown left to burn down
	 * @param gameTime       current level game time
	 * @param positionHash   any stable per-hopper value, used to stagger the work
	 * @return true when this tick's pull attempt can be skipped
	 */
	public boolean shouldSkip(boolean empty, boolean onCooldown, long gameTime, int positionHash) {
		if (!enabled() || !empty || onCooldown) {
			return false;
		}

		evaluated++;
		int n = interval();

		// Spread the wake-ups so a whole storage room does not fire on one tick.
		if (Math.floorMod(gameTime + positionHash, n) == 0) {
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
