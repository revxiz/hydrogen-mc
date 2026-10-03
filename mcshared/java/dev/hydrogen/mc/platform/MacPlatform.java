package dev.hydrogen.mc.platform;

import dev.hydrogen.core.cpu.CpuClass;
import dev.hydrogen.core.cpu.CpuTopology;
import dev.hydrogen.core.cpu.LogicalCpu;
import dev.hydrogen.core.cpu.ThreadRole;
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
 * Darwin has no user-space thread affinity, so pinning always reports false.
 * What it does have is quality-of-service classes, which on Apple Silicon are
 * how the scheduler picks performance or efficiency cores: a user-interactive
 * thread is kept on the P-cluster, a utility thread drifts to the E-cluster.
 * Each role gets the matching class. Topology still comes from sysctl,
 * including the Apple Silicon performance and efficiency counts.
 */
final class MacPlatform implements NativePlatform {
	// <sys/qos.h>
	private static final int QOS_CLASS_USER_INTERACTIVE = 0x21;
	private static final int QOS_CLASS_USER_INITIATED = 0x19;
	private static final int QOS_CLASS_UTILITY = 0x11;

	private final long pSysctlByName;
	private final long pSetQosClassSelf;
	private final long pPthreadMachThreadNp;
	private final long pPthreadSelf;
	private final CpuTopology topology;

	MacPlatform() {
		SharedLibrary libSystem = Natives.open("libSystem.B.dylib", "libSystem.dylib", "libc.dylib");
		this.pSysctlByName = Natives.fn(libSystem, "sysctlbyname");
		this.pSetQosClassSelf = Natives.fnQuiet(libSystem, "pthread_set_qos_class_self_np");
		this.pPthreadSelf = Natives.fnQuiet(libSystem, "pthread_self");
		this.pPthreadMachThreadNp = Natives.fnQuiet(libSystem, "pthread_mach_thread_np");
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

			// sysctlbyname(name, oldp, oldlenp, newp = NULL, newlen = 0). newlen is a
			// size_t, so it is passed as a full 64-bit zero.
			int rc = JNI.invokePPPPPI(MemoryUtil.memAddress(name), value, size, 0L, 0L, pSysctlByName);

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
		return false; // Not offered by Darwin; quality-of-service classes are the lever.
	}

	/** The render and server threads ask for the P-cluster, background work steps aside. */
	@Override
	public boolean hintCurrentThread(ThreadRole role) {
		if (!Natives.has(pSetQosClassSelf)) {
			return false;
		}

		int qos = switch (role) {
			case RENDER -> QOS_CLASS_USER_INTERACTIVE;
			case SERVER -> QOS_CLASS_USER_INITIATED;
			case CHUNK_BUILD -> QOS_CLASS_USER_INITIATED;
			case BACKGROUND -> QOS_CLASS_UTILITY;
		};

		try {
			return JNI.invokeI(qos, 0, pSetQosClassSelf) == 0;
		} catch (Throwable t) {
			return false;
		}
	}

	@Override
	public long currentThreadId() {
		if (!Natives.has(pPthreadSelf, pPthreadMachThreadNp)) {
			return -1L;
		}

		try {
			return JNI.invokePI(JNI.invokeP(pPthreadSelf), pPthreadMachThreadNp) & 0xFFFFFFFFL;
		} catch (Throwable t) {
			return -1L;
		}
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
