package dev.hydrogen.mc.platform;

import dev.hydrogen.core.cpu.CpuTopology;
import dev.hydrogen.core.platform.NativePlatform;

/** Last resort. Reports a flat topology and performs no OS calls. */
final class NoopPlatform implements NativePlatform {
	private final CpuTopology topology =
			CpuTopology.flat(Runtime.getRuntime().availableProcessors());
	private final String reason;

	NoopPlatform(String reason) {
		this.reason = reason;
	}

	@Override
	public String name() {
		return "generic (" + reason + ")";
	}

	@Override
	public boolean available() {
		return false;
	}

	@Override
	public CpuTopology topology() {
		return topology;
	}

	@Override
	public boolean bindCurrentThread(long[] mask) {
		return false;
	}

	@Override
	public boolean setCurrentThreadPriority(int level) {
		return false;
	}

	@Override
	public boolean setProcessPriority(int level) {
		return false;
	}

	@Override
	public boolean requestBoost(boolean on) {
		return false;
	}

	@Override
	public boolean onBattery() {
		return false;
	}

	@Override
	public void restore() {
	}
}
