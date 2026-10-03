package dev.hydrogen.core.frame;

import dev.hydrogen.core.util.Ema;

import java.util.Arrays;

/**
 * Ring of recent frame durations. Written only by the render thread, and the
 * snapshot is also taken there, so no locking is needed.
 */
public final class FrameTimeline {
	public static final int CAPACITY = 256;

	private final long[] nanos = new long[CAPACITY];
	private final long[] sorted = new long[CAPACITY];
	private final Ema smoothed = new Ema(0.08D);

	private int cursor;
	private int filled;
	private long lastNanos;
	private long stalls;
	private long total;

	public void push(long frameNanos) {
		if (frameNanos <= 0L || frameNanos > 2_000_000_000L) {
			return; // Loading spikes and debugger pauses would poison the statistics.
		}

		nanos[cursor] = frameNanos;
		cursor = (cursor + 1) % CAPACITY;

		if (filled < CAPACITY) {
			filled++;
		}

		lastNanos = frameNanos;
		total++;
		smoothed.push(frameNanos / 1_000_000.0D);
	}

	public void markStall() {
		stalls++;
	}

	public int samples() {
		return filled;
	}

	public double lastMs() {
		return lastNanos / 1_000_000.0D;
	}

	public double smoothedMs() {
		return smoothed.get();
	}

	public double fps() {
		double ms = smoothed.get();
		return ms > 0.0D ? 1000.0D / ms : 0.0D;
	}

	public long stallCount() {
		return stalls;
	}

	public long frameCount() {
		return total;
	}

	/** Fraction of the retained window that exceeded {@code thresholdMs}. */
	public double stallRatio(double thresholdMs) {
		return filled == 0 ? 0.0D : (double) countAbove(sortWindow(), thresholdMs) / filled;
	}

	public double percentileMs(double q) {
		return filled == 0 ? 0.0D : pick(sortWindow(), q);
	}

	/** One copy and one sort serve every percentile and the stall ratio. */
	public FrameStats snapshot(double stallThresholdMs) {
		if (filled == 0) {
			return FrameStats.EMPTY;
		}

		int n = sortWindow();

		return new FrameStats(
				lastMs(),
				smoothedMs(),
				pick(n, 0.50D),
				pick(n, 0.95D),
				pick(n, 0.99D),
				(double) countAbove(n, stallThresholdMs) / n,
				n);
	}

	private int sortWindow() {
		System.arraycopy(nanos, 0, sorted, 0, filled);
		Arrays.sort(sorted, 0, filled);
		return filled;
	}

	private double pick(int n, double q) {
		int idx = (int) Math.round(q * (n - 1));
		return sorted[Math.max(0, Math.min(n - 1, idx))] / 1_000_000.0D;
	}

	/** Entries strictly above the threshold, found by binary search on the sorted copy. */
	private int countAbove(int n, double thresholdMs) {
		long limit = (long) (thresholdMs * 1_000_000.0D);
		int lo = 0;
		int hi = n;

		while (lo < hi) {
			int mid = (lo + hi) >>> 1;

			if (sorted[mid] <= limit) {
				lo = mid + 1;
			} else {
				hi = mid;
			}
		}

		return n - lo;
	}

	public void reset() {
		Arrays.fill(nanos, 0L);
		cursor = 0;
		filled = 0;
		lastNanos = 0L;
		stalls = 0L;
		total = 0L;
		smoothed.reset();
	}
}
