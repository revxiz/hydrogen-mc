package dev.hydrogen.core.cpu;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CpuTopologyTest {
	@Test
	void parsesKernelCpuLists() {
		assertEquals(List.of(0, 1, 2, 3, 8, 10, 11), CpuTopology.parseCpuList("0-3,8,10-11\n"));
		assertEquals(List.of(), CpuTopology.parseCpuList(""));
		assertEquals(List.of(4), CpuTopology.parseCpuList("x-y,4"));
	}

	@Test
	void sparseIndicesKeepTheirBits() {
		List<LogicalCpu> cpus = List.of(cpu(0), cpu(70));

		long[] mask = CpuTopology.mask(cpus, 2);

		assertEquals(2, mask.length);
		assertEquals(1L, mask[0]);
		assertEquals(1L << 6, mask[1]);
	}

	@Test
	void x3dPrefersTheLargeCacheOverClock() {
		List<LogicalCpu> cpus = new ArrayList<>();

		// CCD0: 96 MB V-Cache at 5.2 GHz, CCD1: 32 MB at 5.7 GHz.
		for (int i = 0; i < 4; i++) {
			cpus.add(new LogicalCpu(i, i, 0, 0, 5_200_000L, CpuClass.PERFORMANCE, 0, 0, 98_304L));
		}

		for (int i = 4; i < 8; i++) {
			cpus.add(new LogicalCpu(i, i, 0, 0, 5_700_000L, CpuClass.PERFORMANCE, 0, 1, 32_768L));
		}

		CpuTopology topo = new CpuTopology(cpus, "test");

		assertTrue(topo.mixedCache());
		assertEquals(0, topo.fastPrimaries().get(0).cacheId());
	}

	@Test
	void uniformCacheFallsBackToFirmwareRankThenClock() {
		List<LogicalCpu> cpus = List.of(
				new LogicalCpu(0, 0, 0, 0, 4_000_000L, CpuClass.PERFORMANCE, 200, 0, 32_768L),
				new LogicalCpu(1, 1, 0, 0, 4_000_000L, CpuClass.PERFORMANCE, 230, 0, 32_768L),
				new LogicalCpu(2, 2, 0, 0, 4_500_000L, CpuClass.PERFORMANCE, 200, 0, 32_768L));

		CpuTopology topo = new CpuTopology(cpus, "test");

		assertFalse(topo.mixedCache());
		assertEquals(List.of(1, 2, 0), topo.fastPrimaries().stream().map(LogicalCpu::index).toList());
	}

	@Test
	void siblingsShareTheCore() {
		List<LogicalCpu> cpus = List.of(
				new LogicalCpu(0, 0, 0, 0, 0L, CpuClass.PERFORMANCE),
				new LogicalCpu(1, 1, 0, 0, 0L, CpuClass.PERFORMANCE),
				new LogicalCpu(2, 0, 0, 1, 0L, CpuClass.PERFORMANCE_SMT),
				new LogicalCpu(3, 1, 0, 1, 0L, CpuClass.PERFORMANCE_SMT));

		CpuTopology topo = new CpuTopology(cpus, "test");

		assertArrayEquals(new long[] {0b0101L},
				CpuTopology.mask(topo.siblingsOf(List.of(cpus.get(0))), topo.indexSpan()));
	}

	private static LogicalCpu cpu(int index) {
		return new LogicalCpu(index, index, 0, 0, 0L, CpuClass.PERFORMANCE);
	}
}
