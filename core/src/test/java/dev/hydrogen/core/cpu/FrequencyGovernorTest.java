package dev.hydrogen.core.cpu;

import dev.hydrogen.core.config.HConfig;
import dev.hydrogen.core.frame.FrameStats;
import dev.hydrogen.core.hw.Budget;
import dev.hydrogen.core.hw.HardwareProfile;
import dev.hydrogen.core.platform.NativePlatform;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrequencyGovernorTest {
	/** Records boost requests; battery state is switchable. */
	private static final class FakePlatform implements NativePlatform {
		boolean battery;
		boolean boostedAtOs;
		int requests;

		@Override public String name() { return "fake"; }
		@Override public boolean available() { return true; }
		@Override public CpuTopology topology() { return null; }
		@Override public boolean bindCurrentThread(long[] mask) { return false; }
		@Override public boolean setCurrentThreadPriority(int level) { return false; }
		@Override public boolean setProcessPriority(int level) { return false; }
		@Override public boolean onBattery() { return battery; }
		@Override public void restore() { boostedAtOs = false; }

		@Override
		public boolean requestBoost(boolean on) {
			requests++;
			boostedAtOs = on;
			return true;
		}
	}

	// 60 Hz display by default: every frame at 40 ms is a clear overrun.
	private static final FrameStats SLOW = new FrameStats(40, 40, 40, 40, 40, 1.0D, 120);
	private static final FrameStats FAST = new FrameStats(5, 5, 5, 5, 5, 0.0D, 120);

	private static FrequencyGovernor governor(FakePlatform p, HConfig config) {
		return new FrequencyGovernor(p, config, new Budget(config, new HardwareProfile()));
	}

	@Test
	void boostsOnOverrunAndReleasesWhenCalm() {
		FakePlatform p = new FakePlatform();
		FrequencyGovernor g = governor(p, new HConfig(null));

		g.update(SLOW, 100_000L);
		assertTrue(g.boosted());
		assertTrue(p.boostedAtOs);

		g.update(FAST, 200_000L);
		assertFalse(g.boosted());
		assertFalse(p.boostedAtOs);
	}

	@Test
	void dwellTimeStopsFlapping() {
		FakePlatform p = new FakePlatform();
		FrequencyGovernor g = governor(p, new HConfig(null));

		g.update(SLOW, 100_000L);
		g.update(FAST, 100_010L);

		assertTrue(g.boosted());
		assertEquals(1, g.switchCount());
	}

	@Test
	void disallowedSwitchStillReleasesThroughTheOs() {
		FakePlatform p = new FakePlatform();
		HConfig config = new HConfig(null);
		FrequencyGovernor g = governor(p, config);

		g.update(SLOW, 100_000L);
		assertTrue(p.boostedAtOs);

		// Same as being unplugged mid-session: the next update gives the plan back
		// straight away, without waiting for frames to calm down or for the dwell.
		config.override("cpu.governor.allowPowerPlanSwitch", "false");
		g.update(SLOW, 100_001L);

		assertFalse(g.boosted());
		assertFalse(p.boostedAtOs);
	}

	@Test
	void neverTouchesTheOsWhenSwitchingIsDisallowed() {
		FakePlatform p = new FakePlatform();
		HConfig config = new HConfig(null);
		config.override("cpu.governor.allowPowerPlanSwitch", "false");
		config.override("cpu.governor.spinHintFallback", "false");
		FrequencyGovernor g = governor(p, config);

		g.update(SLOW, 100_000L);
		g.release(200_000L);

		assertEquals(0, p.requests);
	}

	@Test
	void leavingTheWorldReleasesImmediately() {
		FakePlatform p = new FakePlatform();
		FrequencyGovernor g = governor(p, new HConfig(null));

		g.update(SLOW, 100_000L);
		g.release(100_001L);

		assertFalse(g.boosted());
		assertFalse(p.boostedAtOs);
	}
}
