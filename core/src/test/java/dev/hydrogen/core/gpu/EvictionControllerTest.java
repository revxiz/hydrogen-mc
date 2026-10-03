package dev.hydrogen.core.gpu;

import dev.hydrogen.core.config.HConfig;
import dev.hydrogen.core.hw.Budget;
import dev.hydrogen.core.hw.GpuInfo;
import dev.hydrogen.core.hw.HardwareProfile;
import dev.hydrogen.core.compat.RenderBackend;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvictionControllerTest {
	private static final long GB = 1024L * 1024L;

	@Test
	void capTightensThenRecoversToTheUserSetting() {
		HardwareProfile hw = new HardwareProfile();
		hw.setGpu(new GpuInfo("test", "test", 4L * GB, "NVX", RenderBackend.VANILLA_GL));
		HConfig config = new HConfig(null);
		EvictionController e = new EvictionController(config, new Budget(config, hw));

		e.tightenCap(12);
		assertEquals(10, e.renderDistanceCap());

		VramSnapshot calm = new VramSnapshot(4L * GB, 3L * GB, 0L, "NVX");
		long now = 100_000L;

		for (int i = 0; i < 3; i++) {
			now += 6_000L;
			e.relaxCap(calm, 12, now);
		}

		assertEquals(-1, e.renderDistanceCap());
	}

	@Test
	void capNeverGoesBelowSixChunks() {
		HConfig config = new HConfig(null);
		EvictionController e = new EvictionController(config, new Budget(config, new HardwareProfile()));

		e.tightenCap(7);
		assertEquals(6, e.renderDistanceCap());

		e.tightenCap(6);
		assertEquals(6, e.renderDistanceCap());
	}

	@Test
	void pressureNeverRelaxes() {
		HardwareProfile hw = new HardwareProfile();
		hw.setGpu(new GpuInfo("test", "test", 4L * GB, "NVX", RenderBackend.VANILLA_GL));
		HConfig config = new HConfig(null);
		EvictionController e = new EvictionController(config, new Budget(config, hw));

		e.tightenCap(12);
		e.relaxCap(new VramSnapshot(4L * GB, 100L * 1024L, 0L, "NVX"), 12, 50_000L);

		assertEquals(10, e.renderDistanceCap());
	}
}
