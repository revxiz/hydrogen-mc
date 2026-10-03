package dev.hydrogen.core.cull;

import dev.hydrogen.core.config.HConfig;
import dev.hydrogen.core.hw.Budget;
import dev.hydrogen.core.hw.DisplayInfo;
import dev.hydrogen.core.hw.HardwareProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubPixelCullerTest {
	private static SubPixelCuller culler() {
		HConfig config = new HConfig(null);
		Budget budget = new Budget(config, new HardwareProfile());
		budget.refresh();
		SubPixelCuller c = new SubPixelCuller(budget);
		c.updateProjection(new DisplayInfo(1920, 1080, 1920, 1080, 60, 1.0D, 2.0D, 0, true), 1.0D, 70.0D);
		return c;
	}

	@Test
	void squaredFormAgreesWithTheDirectForm() {
		SubPixelCuller c = culler();

		for (double size : new double[] {0.1D, 0.25D, 1.0D, 4.0D}) {
			for (double d : new double[] {20.0D, 100.0D, 300.0D, 900.0D}) {
				boolean expected = c.projectedPixels(size, d) < 1.0D;
				assertEquals(expected, c.shouldCullSq(size, d * d), "size " + size + " at " + d);
			}
		}
	}

	@Test
	void zeroOrUnknownSizeIsNeverCulled() {
		SubPixelCuller c = culler();

		// Lightning bolts and some display entities report an empty box.
		assertFalse(c.shouldCullSq(0.0D, 1.0E6D));
		assertFalse(c.shouldCullSq(Double.NaN, 1.0E6D));
	}

	@Test
	void nothingNearIsCulled() {
		SubPixelCuller c = culler();

		assertFalse(c.shouldCullSq(0.001D, 10.0D * 10.0D));
		assertTrue(c.shouldCullSq(0.001D, 100.0D * 100.0D));
	}
}
