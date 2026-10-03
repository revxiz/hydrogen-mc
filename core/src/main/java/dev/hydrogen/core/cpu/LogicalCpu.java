package dev.hydrogen.core.cpu;

/**
 * One schedulable OS processor.
 *
 * @param index      OS processor index
 * @param coreId     physical core this thread belongs to
 * @param packageId  socket / package id
 * @param smtIndex   0 for the first thread on the core
 * @param maxFreqKHz advertised peak clock, 0 when unknown
 * @param cpuClass   performance tier
 * @param perfRank   relative core quality from the firmware (ACPI CPPC highest
 *                   performance, ARM capacity or the Windows scheduling class),
 *                   higher is better, 0 when unknown
 * @param cacheId    last-level cache domain, -1 when unknown
 * @param cacheKb    size of that last-level cache, 0 when unknown
 */
public record LogicalCpu(
		int index,
		int coreId,
		int packageId,
		int smtIndex,
		long maxFreqKHz,
		CpuClass cpuClass,
		int perfRank,
		int cacheId,
		long cacheKb) {

	public LogicalCpu(int index, int coreId, int packageId, int smtIndex, long maxFreqKHz, CpuClass cpuClass) {
		this(index, coreId, packageId, smtIndex, maxFreqKHz, cpuClass, 0, -1, 0L);
	}

	public boolean isPrimaryThread() {
		return smtIndex == 0;
	}

	/** Same physical core, possibly a different SMT thread. */
	public boolean sameCore(LogicalCpu other) {
		return packageId == other.packageId && coreId == other.coreId;
	}

	public LogicalCpu withClass(CpuClass cls) {
		return new LogicalCpu(index, coreId, packageId, smtIndex, maxFreqKHz, cls, perfRank, cacheId, cacheKb);
	}

	public LogicalCpu withCache(int id, long kb) {
		return new LogicalCpu(index, coreId, packageId, smtIndex, maxFreqKHz, cpuClass, perfRank, id, kb);
	}

	public LogicalCpu withRank(int rank) {
		return new LogicalCpu(index, coreId, packageId, smtIndex, maxFreqKHz, cpuClass, rank, cacheId, cacheKb);
	}
}
