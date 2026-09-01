package dev.hydrogen.core.cpu;

/** Performance tier of a logical CPU as reported by the OS. */
public enum CpuClass {
	/** First SMT thread of a high-clock physical core. */
	PERFORMANCE,
	/** Second (or later) SMT thread sharing a performance core. */
	PERFORMANCE_SMT,
	/** Efficiency / density core on a hybrid part. */
	EFFICIENCY,
	UNKNOWN
}
