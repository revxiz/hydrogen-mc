package dev.hydrogen.core.frame;

import dev.hydrogen.core.util.Ema;

import java.util.Arrays;

/**
 * Lock-free-ish ring of recent frame durations. Written only by the render thread;
 * readers take a copy, so a torn read costs at most one stale percentile.
 */
public final class FrameTimeline {
	public static final int CAPACITY = 256;

	private final long[] nanos = new long[CAPACITY];
	private final Ema smoothed = new Ema(0.08D);

	private int cursor;
	private int filled;
	private long lastNanos;
	private long stalls;
	private long total;

	private final double[] scratch = new double[CAPACITY];

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
		if (filled == 0) {
			return 0.0D;
		}

		long limit = (long) (thresholdMs * 1_000_000.0D);
		int over = 0;

		for (int i = 0; i < filled; i++) {
			if (nanos[i] > limit) {
				over++;
			}
		}

		return (double) over / filled;
	}

	public double percentileMs(double q) {
		if (filled == 0) {
			return 0.0D;
		}

		for (int i = 0; i < filled; i++) {
			scratch[i] = nanos[i] / 1_000_000.0D;
		}

		Arrays.sort(scratch, 0, filled);
		int idx = (int) Math.round(q * (filled - 1));
		return scratch[Math.max(0, Math.min(filled - 1, idx))];
	}

	public FrameStats snapshot(double stallThresholdMs) {
		return new FrameStats(
				lastMs(),
				smoothedMs(),
				percentileMs(0.50D),
				percentileMs(0.95D),
				percentileMs(0.99D),
				stallRatio(stallThresholdMs),
				filled);
	}

	public void reset() {
		Arrays.fill(nanos, 0L);
		cursor = 0;
		filled = 0;
		lastNanos = 0L;
		smoothed.reset();
	}
}
