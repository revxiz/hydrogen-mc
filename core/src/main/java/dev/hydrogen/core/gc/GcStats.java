package dev.hydrogen.core.gc;

/** Counters for the overlay and the shutdown summary. */
public record GcStats(
		long scheduledSweeps,
		long observedPauses,
		long pausesDuringAction,
		double meanPauseMs,
		double worstPauseMs,
		double heapPercent,
		long reclaimedMb,
		boolean active) {
}
