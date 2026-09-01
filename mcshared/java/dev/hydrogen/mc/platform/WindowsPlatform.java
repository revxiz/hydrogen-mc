package dev.hydrogen.mc.platform;

import dev.hydrogen.core.HLog;
import dev.hydrogen.core.cpu.CpuClass;
import dev.hydrogen.core.cpu.CpuTopology;
import dev.hydrogen.core.cpu.LogicalCpu;
import dev.hydrogen.core.platform.NativePlatform;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Windows implementation.
 *
 * Topology comes from GetLogicalProcessorInformationEx, which reports the SMT
 * flag and EfficiencyClass per physical core; a higher EfficiencyClass means a
 * higher performance core, so the top class becomes the P-core set.
 *
 * Boosting switches the active power plan to High Performance and holds the
 * system out of idle states, restoring the previous plan on shutdown.
 */
final class WindowsPlatform implements NativePlatform {
	private static final int RELATION_PROCESSOR_CORE = 0;
	private static final int LTP_PC_SMT = 0x1;

	private static final int THREAD_PRIORITY_NORMAL = 0;
	private static final int THREAD_PRIORITY_ABOVE_NORMAL = 1;
	private static final int THREAD_PRIORITY_HIGHEST = 2;

	private static final int NORMAL_PRIORITY_CLASS = 0x00000020;
	private static final int ABOVE_NORMAL_PRIORITY_CLASS = 0x00008000;
	private static final int HIGH_PRIORITY_CLASS = 0x00000080;

	private static final int ES_SYSTEM_REQUIRED = 0x00000001;
	private static final int ES_DISPLAY_REQUIRED = 0x00000002;
	private static final int ES_CONTINUOUS = 0x80000000;

	// GUID_MIN_POWER_SAVINGS, the built-in High Performance plan.
	private static final int[] HIGH_PERF_GUID = {
			0x8c5e7fda, 0xe8bf, 0x4a96, 0x9a, 0x85, 0xa6, 0xe2, 0x3a, 0x8c, 0x63, 0x5c
	};

	private final long pGetCurrentThread;
	private final long pGetCurrentProcess;
	private final long pSetThreadAffinityMask;
	private final long pSetThreadPriority;
	private final long pSetPriorityClass;
	private final long pGetLogicalProcessorInformationEx;
	private final long pGetSystemPowerStatus;
	private final long pSetThreadExecutionState;
	private final long pPowerSetActiveScheme;
	private final long pPowerGetActiveScheme;
	private final long pLocalFree;

	private final CpuTopology topology;
	private final boolean multiGroup;

	private long savedSchemeGuid;
	private boolean planChanged;
	private boolean planWritable = true;

	WindowsPlatform() {
		SharedLibrary kernel32 = Natives.open("kernel32");
		SharedLibrary powrprof = Natives.open("powrprof");

		this.pGetCurrentThread = Natives.fn(kernel32, "GetCurrentThread");
		this.pGetCurrentProcess = Natives.fn(kernel32, "GetCurrentProcess");
		this.pSetThreadAffinityMask = Natives.fn(kernel32, "SetThreadAffinityMask");
		this.pSetThreadPriority = Natives.fn(kernel32, "SetThreadPriority");
		this.pSetPriorityClass = Natives.fn(kernel32, "SetPriorityClass");
		this.pGetLogicalProcessorInformationEx = Natives.fn(kernel32, "GetLogicalProcessorInformationEx");
		this.pGetSystemPowerStatus = Natives.fn(kernel32, "GetSystemPowerStatus");
		this.pSetThreadExecutionState = Natives.fn(kernel32, "SetThreadExecutionState");
		this.pLocalFree = Natives.fn(kernel32, "LocalFree");
		this.pPowerSetActiveScheme = Natives.fn(powrprof, "PowerSetActiveScheme");
		this.pPowerGetActiveScheme = Natives.fn(powrprof, "PowerGetActiveScheme");

		CpuTopology topo = readTopology();
		this.topology = topo;

		boolean groups = false;

		for (LogicalCpu c : topo.cpus()) {
			if (c.index() >= 64) {
				groups = true;
				break;
			}
		}

		this.multiGroup = groups;
	}

	@Override
	public String name() {
		return "windows";
	}

	@Override
	public boolean available() {
		return Natives.has(pGetCurrentThread, pSetThreadAffinityMask);
	}

	@Override
	public CpuTopology topology() {
		return topology;
	}

	// ------------------------------------------------------------- topology

	private CpuTopology readTopology() {
		if (!Natives.has(pGetLogicalProcessorInformationEx)) {
			return CpuTopology.flat(Runtime.getRuntime().availableProcessors());
		}

		long lenPtr = 0L;
		long buffer = 0L;

		try {
			lenPtr = MemoryUtil.nmemCallocChecked(1L, 4L);

			// First call fails and reports the buffer size it needs.
			JNI.invokePPI(RELATION_PROCESSOR_CORE, 0L, lenPtr, pGetLogicalProcessorInformationEx);
			int len = MemoryUtil.memGetInt(lenPtr);

			if (len <= 0) {
				return CpuTopology.flat(Runtime.getRuntime().availableProcessors());
			}

			buffer = MemoryUtil.nmemCallocChecked(1L, len);
			MemoryUtil.memPutInt(lenPtr, len);

			if (JNI.invokePPI(RELATION_PROCESSOR_CORE, buffer, lenPtr, pGetLogicalProcessorInformationEx) == 0) {
				return CpuTopology.flat(Runtime.getRuntime().availableProcessors());
			}

			return parse(buffer, MemoryUtil.memGetInt(lenPtr));
		} catch (Throwable t) {
			HLog.warnOnce("win-topo", "Hydrogen: GetLogicalProcessorInformationEx failed", t);
			return CpuTopology.flat(Runtime.getRuntime().availableProcessors());
		} finally {
			if (buffer != 0L) {
				MemoryUtil.nmemFree(buffer);
			}

			if (lenPtr != 0L) {
				MemoryUtil.nmemFree(lenPtr);
			}
		}
	}

	/**
	 * Walks the variable-length SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX array.
	 * Layout (x64): Relationship@0, Size@4, Flags@8, EfficiencyClass@9,
	 * GroupCount@30, GROUP_AFFINITY[]@32 with 16 bytes per entry.
	 */
	private CpuTopology parse(long buffer, int length) {
		record Core(int efficiencyClass, boolean smt, int group, long mask) {
		}

		List<Core> cores = new ArrayList<>();
		long ptr = buffer;
		long end = buffer + length;
		int maxEff = 0;
		int minEff = Integer.MAX_VALUE;

		while (ptr + 8L <= end) {
			int relationship = MemoryUtil.memGetInt(ptr);
			int size = MemoryUtil.memGetInt(ptr + 4L);

			if (size <= 0) {
				break;
			}

			if (relationship == RELATION_PROCESSOR_CORE) {
				int flags = MemoryUtil.memGetByte(ptr + 8L) & 0xFF;
				int eff = MemoryUtil.memGetByte(ptr + 9L) & 0xFF;
				int groupCount = MemoryUtil.memGetShort(ptr + 30L) & 0xFFFF;

				for (int g = 0; g < groupCount; g++) {
					long entry = ptr + 32L + (long) g * 16L;

					if (entry + 16L > end) {
						break;
					}

					long mask = MemoryUtil.memGetLong(entry);
					int group = MemoryUtil.memGetShort(entry + 8L) & 0xFFFF;
					cores.add(new Core(eff, (flags & LTP_PC_SMT) != 0, group, mask));
				}

				maxEff = Math.max(maxEff, eff);
				minEff = Math.min(minEff, eff);
			}

			ptr += size;
		}

		if (cores.isEmpty()) {
			return CpuTopology.flat(Runtime.getRuntime().availableProcessors());
		}

		boolean hybrid = maxEff > minEff;
		List<LogicalCpu> cpus = new ArrayList<>();
		int coreId = 0;

		for (Core c : cores) {
			int smtIndex = 0;

			for (int bit = 0; bit < 64; bit++) {
				if ((c.mask() & (1L << bit)) == 0L) {
					continue;
				}

				CpuClass cls;

				if (hybrid && c.efficiencyClass() < maxEff) {
					cls = CpuClass.EFFICIENCY;
				} else {
					cls = smtIndex == 0 ? CpuClass.PERFORMANCE : CpuClass.PERFORMANCE_SMT;
				}

				cpus.add(new LogicalCpu(c.group() * 64 + bit, coreId, c.group(), smtIndex, 0L, cls));
				smtIndex++;
			}

			coreId++;
		}

		return new CpuTopology(cpus, hybrid ? "GLPIEx hybrid" : "GLPIEx");
	}

	// ------------------------------------------------------------- affinity

	@Override
	public boolean bindCurrentThread(long[] mask) {
		if (!available() || mask == null || mask.length == 0 || mask[0] == 0L) {
			return false;
		}

		// SetThreadAffinityMask only addresses the thread's own processor group.
		if (multiGroup) {
			HLog.once("win-groups",
					"Hydrogen: more than 64 logical CPUs detected, affinity pinning skipped");
			return false;
		}

		try {
			long thread = JNI.invokeP(pGetCurrentThread);
			return JNI.invokePPP(thread, mask[0], pSetThreadAffinityMask) != 0L;
		} catch (Throwable t) {
			HLog.warnOnce("win-affinity", "Hydrogen: SetThreadAffinityMask failed", t);
			return false;
		}
	}

	@Override
	public boolean setCurrentThreadPriority(int level) {
		if (!Natives.has(pGetCurrentThread, pSetThreadPriority)) {
			return false;
		}

		try {
			int value = switch (level) {
				case PRIORITY_REALTIME_ISH -> THREAD_PRIORITY_HIGHEST;
				case PRIORITY_HIGH -> THREAD_PRIORITY_ABOVE_NORMAL;
				default -> THREAD_PRIORITY_NORMAL;
			};

			long thread = JNI.invokeP(pGetCurrentThread);
			return JNI.invokePI(thread, value, pSetThreadPriority) != 0;
		} catch (Throwable t) {
			return false;
		}
	}

	@Override
	public boolean setProcessPriority(int level) {
		if (!Natives.has(pGetCurrentProcess, pSetPriorityClass)) {
			return false;
		}

		try {
			int value = switch (level) {
				case PRIORITY_REALTIME_ISH -> HIGH_PRIORITY_CLASS;
				case PRIORITY_HIGH -> ABOVE_NORMAL_PRIORITY_CLASS;
				default -> NORMAL_PRIORITY_CLASS;
			};

			long process = JNI.invokeP(pGetCurrentProcess);
			return JNI.invokePI(process, value, pSetPriorityClass) != 0;
		} catch (Throwable t) {
			return false;
		}
	}

	// ------------------------------------------------------------- governor

	@Override
	public boolean requestBoost(boolean on) {
		boolean ok = false;

		if (Natives.has(pSetThreadExecutionState)) {
			try {
				int flags = on
						? ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED
						: ES_CONTINUOUS;
				ok = JNI.invokeI(flags, pSetThreadExecutionState) != 0;
			} catch (Throwable ignored) {
				// Not fatal; the power plan switch below matters more.
			}
		}

		if (!planWritable || !Natives.has(pPowerSetActiveScheme)) {
			return ok;
		}

		long guid = 0L;

		try {
			if (on) {
				if (!planChanged) {
					savedSchemeGuid = readActiveScheme();
				}

				guid = MemoryUtil.nmemCallocChecked(1L, 16L);
				writeGuid(guid, HIGH_PERF_GUID);

				if (JNI.invokePPI(0L, guid, pPowerSetActiveScheme) == 0) {
					planChanged = true;
					return true;
				}

				planWritable = false;
				return ok;
			}

			if (planChanged && savedSchemeGuid != 0L) {
				boolean restored = JNI.invokePPI(0L, savedSchemeGuid, pPowerSetActiveScheme) == 0;
				planChanged = !restored;
				return restored || ok;
			}

			return ok;
		} catch (Throwable t) {
			planWritable = false;
			return ok;
		} finally {
			if (guid != 0L) {
				MemoryUtil.nmemFree(guid);
			}
		}
	}

	/** Copies the current scheme GUID into memory Hydrogen owns. */
	private long readActiveScheme() {
		if (!Natives.has(pPowerGetActiveScheme)) {
			return 0L;
		}

		long out = 0L;
		long holder = 0L;

		try {
			holder = MemoryUtil.nmemCallocChecked(1L, 8L);

			if (JNI.invokePPI(0L, holder, pPowerGetActiveScheme) != 0) {
				return 0L;
			}

			long allocated = MemoryUtil.memGetAddress(holder);

			if (allocated == 0L) {
				return 0L;
			}

			out = MemoryUtil.nmemCallocChecked(1L, 16L);
			MemoryUtil.memCopy(allocated, out, 16L);

			if (Natives.has(pLocalFree)) {
				JNI.invokePP(allocated, pLocalFree);
			}

			return out;
		} catch (Throwable t) {
			return out;
		} finally {
			if (holder != 0L) {
				MemoryUtil.nmemFree(holder);
			}
		}
	}

	private static void writeGuid(long address, int[] parts) {
		MemoryUtil.memPutInt(address, parts[0]);
		MemoryUtil.memPutShort(address + 4L, (short) parts[1]);
		MemoryUtil.memPutShort(address + 6L, (short) parts[2]);

		for (int i = 0; i < 8; i++) {
			MemoryUtil.memPutByte(address + 8L + i, (byte) parts[3 + i]);
		}
	}

	@Override
	public boolean onBattery() {
		if (!Natives.has(pGetSystemPowerStatus)) {
			return false;
		}

		long status = 0L;

		try {
			// SYSTEM_POWER_STATUS: ACLineStatus is the first byte, 0 means battery.
			status = MemoryUtil.nmemCallocChecked(1L, 12L);

			if (JNI.invokePI(status, pGetSystemPowerStatus) == 0) {
				return false;
			}

			return (MemoryUtil.memGetByte(status) & 0xFF) == 0;
		} catch (Throwable t) {
			return false;
		} finally {
			if (status != 0L) {
				MemoryUtil.nmemFree(status);
			}
		}
	}

	@Override
	public void restore() {
		try {
			requestBoost(false);
		} finally {
			if (savedSchemeGuid != 0L) {
				MemoryUtil.nmemFree(savedSchemeGuid);
				savedSchemeGuid = 0L;
			}
		}
	}
}
