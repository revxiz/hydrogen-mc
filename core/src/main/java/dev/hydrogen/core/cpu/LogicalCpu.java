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
 */
public record LogicalCpu(
		int index,
		int coreId,
		int packageId,
		int smtIndex,
		long maxFreqKHz,
		CpuClass cpuClass) {

	public boolean isPrimaryThread() {
		return smtIndex == 0;
	}
}
