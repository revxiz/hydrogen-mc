package dev.hydrogen.core.platform;

import dev.hydrogen.core.cpu.CpuTopology;
import dev.hydrogen.core.cpu.ThreadRole;
import dev.hydrogen.core.cpu.ThreadSweep;

import java.nio.file.Path;

/**
 * OS-specific hooks. Every method is best effort: an implementation that cannot
 * perform an action returns {@code false} and the caller carries on unchanged.
 */
public interface NativePlatform {
	int PRIORITY_NORMAL = 0;
	int PRIORITY_HIGH = 1;
	int PRIORITY_REALTIME_ISH = 2;

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

	/** OS id of the calling thread, or -1 when the platform cannot say. */
	default long currentThreadId() {
		return -1L;
	}

	/**
	 * Hint for platforms without hard affinity, such as macOS quality-of-service
	 * classes. Returns true when the hint was accepted.
	 */
	default boolean hintCurrentThread(ThreadRole role) {
		return false;
	}

	/**
	 * Applies {@link ThreadSweep} to every thread in the process that Hydrogen did
	 * not pin itself.
	 *
	 * @return threads whose placement changed, or -1 when the platform cannot enumerate threads
	 */
	default int sweepThreads(ThreadSweep sweep) {
		return -1;
	}

	/**
	 * Where boost state is recorded while it is active. If the game is killed or
	 * crashes, the next launch finds the file and puts the system back.
	 */
	default void recoverState(Path stateFile) {
	}

	/**
	 * Opts the process out of OS power throttling (Windows EcoQoS), which would
	 * otherwise run threads at efficiency clocks. Process-scoped and undone by the
	 * OS when the game exits.
	 */
	default boolean disablePowerThrottling() {
		return false;
	}
}
