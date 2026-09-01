package dev.hydrogen.core.platform;

import dev.hydrogen.core.cpu.CpuTopology;

/**
 * OS-specific hooks. Every method is best effort: an implementation that cannot
 * perform an action returns {@code false} and the caller carries on unchanged.
 */
public interface NativePlatform {
	String name();

	/** True when native calls resolved and the platform is usable. */
	boolean available();

	CpuTopology topology();

	/** Pins the calling thread to the given affinity mask. */
	boolean bindCurrentThread(long[] mask);

	/** Raises or restores the calling thread's OS scheduling priority. */
	boolean setCurrentThreadPriority(int level);

	/** Raises or restores the process priority class. */
	boolean setProcessPriority(int level);

	/**
	 * Requests peak clocks from the OS power governor.
	 *
	 * @return true if the request reached the governor
	 */
	boolean requestBoost(boolean on);

	/** True when the machine is running on battery, so boosting should stay off. */
	boolean onBattery();

	/** Restores anything {@link #requestBoost} changed. Called on shutdown. */
	void restore();

	int PRIORITY_NORMAL = 0;
	int PRIORITY_HIGH = 1;
	int PRIORITY_REALTIME_ISH = 2;
}
