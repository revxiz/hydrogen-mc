package dev.hydrogen.core.util;

/** Exponential moving average with a warm-up that seeds from the first sample. */
public final class Ema {
	private final double alpha;
	private double value;
	private boolean seeded;

	public Ema(double alpha) {
		this.alpha = alpha;
	}

	public double push(double sample) {
		if (!seeded) {
			value = sample;
			seeded = true;
		} else {
			value += alpha * (sample - value);
		}

		return value;
	}

	public double get() {
		return value;
	}

	public boolean seeded() {
		return seeded;
	}

	public void reset() {
		seeded = false;
		value = 0.0D;
	}
}
