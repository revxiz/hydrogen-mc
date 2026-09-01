package dev.hydrogen.mc.platform;

import dev.hydrogen.core.cpu.CpuClass;
import dev.hydrogen.core.cpu.CpuTopology;
import dev.hydrogen.core.cpu.LogicalCpu;
import dev.hydrogen.core.platform.NativePlatform;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * macOS implementation.
 *
 * Darwin gives no user-space thread affinity API, so pinning always reports
 * false and Hydrogen falls back to JVM thread priorities. Topology still comes
 * from sysctl, including the Apple Silicon performance and efficiency counts,
 * which is what the meshing split actually needs.
 */
final class MacPlatform implements NativePlatform {
	private final long pSysctlByName;
	private final CpuTopology topology;

	MacPlatform() {
		SharedLibrary libSystem = Natives.open("libSystem.B.dylib", "libSystem.dylib", "libc.dylib");
		this.pSysctlByName = Natives.fn(libSystem, "sysctlbyname");
		this.topology = readTopology();
	}

	@Override
	public String name() {
		return "macos";
	}

	@Override
	public boolean available() {
		return Natives.has(pSysctlByName);
	}

	@Override
	public CpuTopology topology() {
		return topology;
	}

	private CpuTopology readTopology() {
		int logical = Math.max(1, sysctlInt("hw.logicalcpu", Runtime.getRuntime().availableProcessors()));
		int physical = Math.max(1, sysctlInt("hw.physicalcpu", logical));
		int perf = sysctlInt("hw.perflevel0.logicalcpu", 0);
		int eff = sysctlInt("hw.perflevel1.logicalcpu", 0);

		if (perf <= 0 || eff <= 0) {
			// Intel Macs, or sysctl unavailable: uniform cores with SMT if present.
			List<LogicalCpu> cpus = new ArrayList<>(logical);
			int threadsPerCore = Math.max(1, logical / physical);

			for (int i = 0; i < logical; i++) {
				int smt = i % threadsPerCore;
				cpus.add(new LogicalCpu(i, i / threadsPerCore, 0, smt, 0L,
						smt == 0 ? CpuClass.PERFORMANCE : CpuClass.PERFORMANCE_SMT));
			}

			return new CpuTopology(cpus, "sysctl");
		}

		// Apple Silicon reports perflevel0 as the performance cluster.
		List<LogicalCpu> cpus = new ArrayList<>(logical);

		for (int i = 0; i < logical; i++) {
			cpus.add(new LogicalCpu(i, i, 0, 0, 0L,
					i < perf ? CpuClass.PERFORMANCE : CpuClass.EFFICIENCY));
		}

		return new CpuTopology(cpus, "sysctl perflevel");
	}

	private int sysctlInt(String key, int fallback) {
		if (!Natives.has(pSysctlByName)) {
			return fallback;
		}

		ByteBuffer name = null;
		long value = 0L;
		long size = 0L;

		try {
			name = MemoryUtil.memASCII(key, true);
			value = MemoryUtil.nmemCallocChecked(1L, 8L);
			size = MemoryUtil.nmemCallocChecked(1L, 8L);
			MemoryUtil.memPutAddress(size, 8L);

			// sysctlbyname(name, oldp, oldlenp, newp = null, newlen = 0)
			int rc = JNI.invokePPPPI(MemoryUtil.memAddress(name), value, size, 0L, 0, pSysctlByName);

			if (rc != 0) {
				return fallback;
			}

			long len = MemoryUtil.memGetAddress(size);
			return len >= 8L ? (int) MemoryUtil.memGetLong(value) : MemoryUtil.memGetInt(value);
		} catch (Throwable t) {
			return fallback;
		} finally {
			if (name != null) {
				MemoryUtil.memFree(name);
			}

			if (value != 0L) {
				MemoryUtil.nmemFree(value);
			}

			if (size != 0L) {
				MemoryUtil.nmemFree(size);
			}
		}
	}

	@Override
	public boolean bindCurrentThread(long[] mask) {
		return false; // Not offered by Darwin; JVM priorities are the fallback.
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
		return false; // Clock control is entirely firmware managed.
	}

	@Override
	public boolean onBattery() {
		return false;
	}

	@Override
	public void restore() {
	}
}
