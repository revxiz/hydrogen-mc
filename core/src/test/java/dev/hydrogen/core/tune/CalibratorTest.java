package dev.hydrogen.core.tune;

import dev.hydrogen.core.config.HConfig;
import dev.hydrogen.core.hw.Budget;
import dev.hydrogen.core.hw.HardwareProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalibratorTest {
	private static final long FRAME_NS = 10_000_000L;

	private static Calibrator calibrator() {
		HConfig config = new HConfig(null);
		return new Calibrator(config, new Budget(config, new HardwareProfile()));
	}

	@Test
	void finishesAfterWarmupAndSampling() {
		Calibrator c = calibrator();
		long now = 1_000L;
		c.request(now);

		// Default warmup 2 s plus sampling; 30 s of 10 ms frames is far more than enough.
		for (int i = 0; i < 3_000 && c.active(); i++) {
			now += 10L;
			c.onFrame(FRAME_NS, now, 0L, 0L, false);
		}

		assertTrue(c.done());
	}

	@Test
	void heldFramesStopTheClock() {
		Calibrator c = calibrator();
		long now = 1_000L;
		c.request(now);

		// A long pause menu must not count towards warmup or sampling.
		for (int i = 0; i < 6_000; i++) {
			now += 10L;
			c.onFrame(FRAME_NS, now, 0L, 0L, true);
		}

		assertTrue(c.active());
		assertFalse(c.done());
	}

	@Test
	void abortReturnsToIdle() {
		Calibrator c = calibrator();
		c.request(1_000L);
		c.abort();

		assertFalse(c.active());
		assertFalse(c.done());
	}
}
