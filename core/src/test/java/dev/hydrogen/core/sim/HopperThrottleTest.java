package dev.hydrogen.core.sim;

import dev.hydrogen.core.config.HConfig;
import dev.hydrogen.core.hw.Budget;
import dev.hydrogen.core.hw.HardwareProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HopperThrottleTest {
	private static HopperThrottle throttle(boolean enabled) {
		HConfig config = new HConfig(null);
		config.set("hopper.throttle.enabled", String.valueOf(enabled));
		Budget budget = new Budget(config, new HardwareProfile());
		budget.refresh();
		return new HopperThrottle(budget);
	}

	@Test
	void offByDefaultMeansNoSkips() {
		HopperThrottle t = throttle(false);

		for (long tick = 0; tick < 20; tick++) {
			assertFalse(t.shouldSkipScan(true, tick, 7));
		}
	}

	@Test
	void busyHoppersAreNeverThrottled() {
		HopperThrottle t = throttle(true);

		for (long tick = 0; tick < 20; tick++) {
			assertFalse(t.shouldSkipScan(false, tick, 7));
		}
	}

	@Test
	void idleHoppersScanOneTickInFour() {
		HopperThrottle t = throttle(true);
		int scans = 0;

		for (long tick = 0; tick < 400; tick++) {
			if (!t.shouldSkipScan(true, tick, 12345)) {
				scans++;
			}
		}

		assertEquals(100, scans);
	}
}
