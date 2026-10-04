package dev.hydrogen.core.console;

/**
 * Server tick times, fed by a mixin at both ends of {@code tickServer}. It is
 * the same measurement Minecraft's own debug screen averages, kept here so it
 * reads the same on every version.
 */
public final class TickClock {
	private static final int WINDOW = 100;

	private static final long[] ticks = new long[WINDOW];
	private static int count;
	private static int head;
	private static long started;
	private static volatile long lastTickMs;
	private static volatile int players = -1;

	private TickClock() {
	}

	public static void begin() {
		started = System.nanoTime();
	}

	public static void end(int playerCount) {
		if (started == 0L) {
			return;
		}

		long took = System.nanoTime() - started;
		started = 0L;

		synchronized (ticks) {
			ticks[head] = took;
			head = (head + 1) % WINDOW;
			count = Math.min(WINDOW, count + 1);
		}

		players = playerCount;
		lastTickMs = System.currentTimeMillis();
	}

	/** Mean over the last 100 ticks, or NaN when no server has ticked lately. */
	public static double meanMs() {
		if (System.currentTimeMillis() - lastTickMs > 5_000L) {
			return Double.NaN;
		}

		synchronized (ticks) {
			if (count == 0) {
				return Double.NaN;
			}

			long sum = 0L;

			for (int i = 0; i < count; i++) {
				sum += ticks[i];
			}

			return sum / (double) count / 1_000_000.0D;
		}
	}

	/** Players on the server this process runs, or -1 when there is none. */
	public static int players() {
		return System.currentTimeMillis() - lastTickMs > 5_000L ? -1 : players;
	}

	public static void reset() {
		synchronized (ticks) {
			count = 0;
			head = 0;
		}

		lastTickMs = 0L;
		players = -1;
	}
}
