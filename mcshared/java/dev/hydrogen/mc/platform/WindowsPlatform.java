package dev.hydrogen.mc.platform;

import dev.hydrogen.core.HLog;
import dev.hydrogen.core.cpu.CpuClass;
import dev.hydrogen.core.cpu.CpuTopology;
import dev.hydrogen.core.cpu.LogicalCpu;
import dev.hydrogen.core.cpu.ThreadSweep;
import dev.hydrogen.core.platform.NativePlatform;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Windows implementation.
 *
 * Topology comes from GetLogicalProcessorInformationEx, which reports the SMT
 * flag and EfficiencyClass per physical core (a higher EfficiencyClass means a
 * higher performance core) and the cores behind each last-level cache.
 *
 * Boosting switches the active power plan to High Performance. The previous plan
 * is written to a state file first, so a crash or a forced kill is undone on the
 * next launch rather than leaving the machine on High Performance for good.
 */
final class WindowsPlatform implements NativePlatform {
	private static final int RELATION_PROCESSOR_CORE = 0;
	private static final int RELATION_CACHE = 2;
	private static final int RELATION_ALL = 0xFFFF;
	private static final int LTP_PC_SMT = 0x1;
	private static final int CACHE_INSTRUCTION = 1;

	private static final int THREAD_PRIORITY_NORMAL = 0;
	private static final int THREAD_PRIORITY_ABOVE_NORMAL = 1;
	private static final int THREAD_PRIORITY_HIGHEST = 2;

	private static final int NORMAL_PRIORITY_CLASS = 0x00000020;
	private static final int ABOVE_NORMAL_PRIORITY_CLASS = 0x00008000;
	private static final int HIGH_PRIORITY_CLASS = 0x00000080;

	private static final int TH32CS_SNAPTHREAD = 0x00000004;
	private static final int THREAD_SET_LIMITED_INFORMATION = 0x0400;
	private static final int THREAD_QUERY_LIMITED_INFORMATION = 0x0800;
	private static final int THREADENTRY32_SIZE = 28;

	private static final int PROCESS_POWER_THROTTLING_INFO = 4;
	private static final int THROTTLE_EXECUTION_SPEED = 0x1;
	private static final int THROTTLE_IGNORE_TIMER_RESOLUTION = 0x4;

	// GUID_MIN_POWER_SAVINGS, the built-in High Performance plan.
	private static final int[] HIGH_PERF_GUID = {
			0x8c5e7fda, 0xe8bf, 0x4a96, 0x9a, 0x85, 0xa6, 0xe2, 0x3a, 0x8c, 0x63, 0x5c
	};

	private final long pGetCurrentThread;
	private final long pGetCurrentThreadId;
	private final long pGetCurrentProcess;
	private final long pGetCurrentProcessId;
	private final long pSetThreadAffinityMask;
	private final long pSetThreadPriority;
	private final long pSetPriorityClass;
	private final long pSetProcessInformation;
	private final long pGetLogicalProcessorInformationEx;
	private final long pGetSystemPowerStatus;
	private final long pPowerSetActiveScheme;
	private final long pPowerGetActiveScheme;
	private final long pLocalFree;
	private final long pCreateToolhelp32Snapshot;
	private final long pThread32First;
	private final long pThread32Next;
	private final long pOpenThread;
	private final long pCloseHandle;
	private final long pGetThreadDescription;

	private final CpuTopology topology;
	private final boolean multiGroup;

	private long savedSchemeGuid;
	private boolean planChanged;
	private boolean planWritable = true;
	private Path stateFile;

	WindowsPlatform() {
		SharedLibrary kernel32 = Natives.open("kernel32");
		SharedLibrary powrprof = Natives.open("powrprof");

		this.pGetCurrentThread = Natives.fn(kernel32, "GetCurrentThread");
		this.pGetCurrentThreadId = Natives.fn(kernel32, "GetCurrentThreadId");
		this.pGetCurrentProcess = Natives.fn(kernel32, "GetCurrentProcess");
		this.pGetCurrentProcessId = Natives.fn(kernel32, "GetCurrentProcessId");
		this.pSetThreadAffinityMask = Natives.fn(kernel32, "SetThreadAffinityMask");
		this.pSetThreadPriority = Natives.fn(kernel32, "SetThreadPriority");
		this.pSetPriorityClass = Natives.fn(kernel32, "SetPriorityClass");
		this.pSetProcessInformation = Natives.fn(kernel32, "SetProcessInformation");
		this.pGetLogicalProcessorInformationEx = Natives.fn(kernel32, "GetLogicalProcessorInformationEx");
		this.pGetSystemPowerStatus = Natives.fn(kernel32, "GetSystemPowerStatus");
		this.pLocalFree = Natives.fn(kernel32, "LocalFree");
		this.pCreateToolhelp32Snapshot = Natives.fn(kernel32, "CreateToolhelp32Snapshot");
		this.pThread32First = Natives.fn(kernel32, "Thread32First");
		this.pThread32Next = Natives.fn(kernel32, "Thread32Next");
		this.pOpenThread = Natives.fn(kernel32, "OpenThread");
		this.pCloseHandle = Natives.fn(kernel32, "CloseHandle");
		// Windows 10 1607+. Without thread names the sweep cannot tell threads apart, so it stays off.
		this.pGetThreadDescription = Natives.fnQuiet(kernel32, "GetThreadDescription");
		this.pPowerSetActiveScheme = Natives.fn(powrprof, "PowerSetActiveScheme");
		this.pPowerGetActiveScheme = Natives.fn(powrprof, "PowerGetActiveScheme");

		CpuTopology topo = readTopology();
		this.topology = topo;
		this.multiGroup = topo.indexSpan() > 64;
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
			JNI.invokePPI(RELATION_ALL, 0L, lenPtr, pGetLogicalProcessorInformationEx);
			int len = MemoryUtil.memGetInt(lenPtr);

			if (len <= 0 || len > 16 * 1024 * 1024) {
				return CpuTopology.flat(Runtime.getRuntime().availableProcessors());
			}

			buffer = MemoryUtil.nmemCallocChecked(1L, len);
			MemoryUtil.memPutInt(lenPtr, len);

			if (JNI.invokePPI(RELATION_ALL, buffer, lenPtr, pGetLogicalProcessorInformationEx) == 0) {
				return CpuTopology.flat(Runtime.getRuntime().availableProcessors());
			}

			return parse(buffer, Math.min(len, MemoryUtil.memGetInt(lenPtr)));
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
	 *
	 * Core entries (x64): Relationship@0, Size@4, Flags@8, EfficiencyClass@9,
	 * GroupCount@30, GROUP_AFFINITY[]@32 with 16 bytes per entry.
	 *
	 * Cache entries: Level@8, CacheSize@12, Type@16, GroupCount@38,
	 * GROUP_AFFINITY[]@40. Before Windows 10 20H2 GroupCount was reserved and
	 * zero, with exactly one mask at the same offset.
	 */
	private CpuTopology parse(long buffer, int length) {
		record Core(int efficiencyClass, boolean smt, int group, long mask) {
		}

		record Cache(int level, long kb, int group, long mask) {
		}

		List<Core> cores = new ArrayList<>();
		List<Cache> caches = new ArrayList<>();
		long ptr = buffer;
		long end = buffer + length;
		int maxEff = 0;
		int minEff = Integer.MAX_VALUE;

		while (ptr + 8L <= end) {
			int relationship = MemoryUtil.memGetInt(ptr);
			int size = MemoryUtil.memGetInt(ptr + 4L);

			if (size <= 0 || ptr + size > end) {
				break;
			}

			if (relationship == RELATION_PROCESSOR_CORE && size >= 48) {
				int flags = MemoryUtil.memGetByte(ptr + 8L) & 0xFF;
				int eff = MemoryUtil.memGetByte(ptr + 9L) & 0xFF;
				int groupCount = MemoryUtil.memGetShort(ptr + 30L) & 0xFFFF;

				for (int g = 0; g < Math.max(1, groupCount); g++) {
					long entry = ptr + 32L + (long) g * 16L;

					if (entry + 16L > ptr + size) {
						break;
					}

					long mask = MemoryUtil.memGetLong(entry);
					int group = MemoryUtil.memGetShort(entry + 8L) & 0xFFFF;
					cores.add(new Core(eff, (flags & LTP_PC_SMT) != 0, group, mask));
				}

				maxEff = Math.max(maxEff, eff);
				minEff = Math.min(minEff, eff);
			} else if (relationship == RELATION_CACHE && size >= 56) {
				int level = MemoryUtil.memGetByte(ptr + 8L) & 0xFF;
				long kb = (MemoryUtil.memGetInt(ptr + 12L) & 0xFFFFFFFFL) / 1024L;
				int type = MemoryUtil.memGetInt(ptr + 16L);
				int groupCount = MemoryUtil.memGetShort(ptr + 38L) & 0xFFFF;

				if (type != CACHE_INSTRUCTION) {
					for (int g = 0; g < Math.max(1, groupCount); g++) {
						long entry = ptr + 40L + (long) g * 16L;

						if (entry + 16L > ptr + size) {
							break;
						}

						caches.add(new Cache(level, kb, MemoryUtil.memGetShort(entry + 8L) & 0xFFFF,
								MemoryUtil.memGetLong(entry)));
					}
				}
			}

			ptr += size;
		}

		if (cores.isEmpty()) {
			return CpuTopology.flat(Runtime.getRuntime().availableProcessors());
		}

		int topLevel = 0;

		for (Cache c : caches) {
			topLevel = Math.max(topLevel, c.level());
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

				CpuClass cls = hybrid && c.efficiencyClass() < maxEff ? CpuClass.EFFICIENCY
						: smtIndex == 0 ? CpuClass.PERFORMANCE : CpuClass.PERFORMANCE_SMT;

				int index = c.group() * 64 + bit;
				int cacheId = -1;
				long cacheKb = 0L;

				for (int k = 0; k < caches.size(); k++) {
					Cache cache = caches.get(k);

					if (cache.level() == topLevel && cache.group() == c.group() && (cache.mask() & (1L << bit)) != 0L) {
						cacheId = k;
						cacheKb = cache.kb();
						break;
					}
				}

				// EfficiencyClass doubles as a firmware rank: higher is a faster core.
				cpus.add(new LogicalCpu(index, coreId, c.group(), smtIndex, 0L, cls,
						hybrid ? c.efficiencyClass() + 1 : 0, cacheId, cacheKb));
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
	public long currentThreadId() {
		if (!Natives.has(pGetCurrentThreadId)) {
			return -1L;
		}

		try {
			return JNI.invokeI(pGetCurrentThreadId) & 0xFFFFFFFFL;
		} catch (Throwable t) {
			return -1L;
		}
	}

	/**
	 * Windows does not pass affinity on to new threads, so only named worker
	 * threads need moving here. Names come from GetThreadDescription, which the
	 * JVM fills for every thread; without it the sweep does nothing.
	 */
	@Override
	public int sweepThreads(ThreadSweep sweep) {
		if (multiGroup || !Natives.has(pCreateToolhelp32Snapshot, pThread32First, pThread32Next, pOpenThread,
				pCloseHandle, pGetThreadDescription, pGetCurrentProcessId, pSetThreadAffinityMask, pLocalFree)) {
			return -1;
		}

		long[] worker = sweep.workerMask();

		if (worker.length == 0 || worker[0] == 0L) {
			return 0;
		}

		long snapshot = -1L;
		long entry = 0L;
		long namePtr = 0L;
		int moved = 0;

		try {
			int pid = JNI.invokeI(pGetCurrentProcessId);
			snapshot = JNI.invokeP(TH32CS_SNAPTHREAD, 0, pCreateToolhelp32Snapshot);

			if (snapshot == -1L || snapshot == 0L) {
				return 0;
			}

			entry = MemoryUtil.nmemCallocChecked(1L, THREADENTRY32_SIZE);
			namePtr = MemoryUtil.nmemCallocChecked(1L, 8L);
			MemoryUtil.memPutInt(entry, THREADENTRY32_SIZE);

			for (boolean ok = JNI.invokePPI(snapshot, entry, pThread32First) != 0; ok;
					ok = JNI.invokePPI(snapshot, entry, pThread32Next) != 0) {
				long tid = MemoryUtil.memGetInt(entry + 8L) & 0xFFFFFFFFL;
				int owner = MemoryUtil.memGetInt(entry + 12L);

				if (owner != pid || sweep.owns(tid)) {
					continue;
				}

				// OpenThread(DWORD, BOOL, DWORD): the thread id rides in a 64-bit slot,
				// which the x64 and ARM64 calling conventions read as its low 32 bits.
				long handle = JNI.invokePP(THREAD_SET_LIMITED_INFORMATION | THREAD_QUERY_LIMITED_INFORMATION,
						0, tid, pOpenThread);

				if (handle == 0L) {
					continue;
				}

				try {
					String name = threadName(handle, namePtr);

					if (name != null && ThreadSweep.classify(name, false) == ThreadSweep.Action.WORKER
							&& JNI.invokePPP(handle, worker[0], pSetThreadAffinityMask) != 0L) {
						moved++;
					}
				} finally {
					JNI.invokePI(handle, pCloseHandle);
				}

				MemoryUtil.memPutInt(entry, THREADENTRY32_SIZE);
			}
		} catch (Throwable t) {
			HLog.warnOnce("win-sweep", "Hydrogen: thread enumeration failed", t);
			return -1;
		} finally {
			if (snapshot != -1L && snapshot != 0L) {
				JNI.invokePI(snapshot, pCloseHandle);
			}

			if (entry != 0L) {
				MemoryUtil.nmemFree(entry);
			}

			if (namePtr != 0L) {
				MemoryUtil.nmemFree(namePtr);
			}
		}

		return moved;
	}

	/** GetThreadDescription hands back a LocalAlloc'd UTF-16 string. */
	private String threadName(long handle, long namePtr) {
		MemoryUtil.memPutAddress(namePtr, 0L);

		if (JNI.invokePPI(handle, namePtr, pGetThreadDescription) < 0) {
			return null;
		}

		long str = MemoryUtil.memGetAddress(namePtr);

		if (str == 0L) {
			return null;
		}

		try {
			return MemoryUtil.memUTF16(str);
		} finally {
			JNI.invokePP(str, pLocalFree);
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

	/**
	 * Opts out of EcoQoS (Windows 10 1709+): without it Windows may run the game's
	 * threads at efficiency clocks or park them on E-cores, most of all when the
	 * window loses focus. Scoped to this process and gone when it exits. The
	 * timer-resolution flag only exists on Windows 11, so it is tried first and
	 * dropped if the OS rejects it.
	 */
	@Override
	public boolean disablePowerThrottling() {
		if (!Natives.has(pGetCurrentProcess, pSetProcessInformation)) {
			return false;
		}

		long state = 0L;

		try {
			long process = JNI.invokeP(pGetCurrentProcess);
			state = MemoryUtil.nmemCallocChecked(1L, 12L);

			for (int control : new int[] {THROTTLE_EXECUTION_SPEED | THROTTLE_IGNORE_TIMER_RESOLUTION,
					THROTTLE_EXECUTION_SPEED}) {
				MemoryUtil.memPutInt(state, 1); // PROCESS_POWER_THROTTLING_CURRENT_VERSION
				MemoryUtil.memPutInt(state + 4L, control);
				MemoryUtil.memPutInt(state + 8L, 0); // StateMask 0: throttling off for these bits

				if (JNI.invokePPI(process, PROCESS_POWER_THROTTLING_INFO, state, 12, pSetProcessInformation) != 0) {
					return true;
				}
			}

			return false;
		} catch (Throwable t) {
			return false;
		} finally {
			if (state != 0L) {
				MemoryUtil.nmemFree(state);
			}
		}
	}

	// ------------------------------------------------------------- governor

	@Override
	public boolean requestBoost(boolean on) {
		if (!planWritable || !Natives.has(pPowerSetActiveScheme)) {
			return false;
		}

		long guid = 0L;

		try {
			if (on) {
				if (!planChanged) {
					freeSaved();
					savedSchemeGuid = readActiveScheme();

					// Already on High Performance: nothing to switch, nothing to restore.
					if (savedSchemeGuid != 0L && isHighPerformance(savedSchemeGuid)) {
						freeSaved();
						return true;
					}
				}

				guid = MemoryUtil.nmemCallocChecked(1L, 16L);
				writeGuid(guid, HIGH_PERF_GUID);
				writeState();

				if (JNI.invokePPI(0L, guid, pPowerSetActiveScheme) == 0) {
					planChanged = true;
					return true;
				}

				// Not present on this edition (Modern Standby machines often hide it).
				planWritable = false;
				clearState();
				return false;
			}

			if (planChanged && savedSchemeGuid != 0L) {
				boolean restored = JNI.invokePPI(0L, savedSchemeGuid, pPowerSetActiveScheme) == 0;
				planChanged = !restored;

				if (restored) {
					clearState();
				}

				return restored;
			}

			return false;
		} catch (Throwable t) {
			planWritable = false;
			return false;
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

	private static boolean isHighPerformance(long guid) {
		return formatGuid(guid).equals(formatGuid(HIGH_PERF_GUID));
	}

	private static void writeGuid(long address, int[] parts) {
		MemoryUtil.memPutInt(address, parts[0]);
		MemoryUtil.memPutShort(address + 4L, (short) parts[1]);
		MemoryUtil.memPutShort(address + 6L, (short) parts[2]);

		for (int i = 0; i < 8; i++) {
			MemoryUtil.memPutByte(address + 8L + i, (byte) parts[3 + i]);
		}
	}

	private static int[] parseGuid(String text) {
		String hex = text.replace("-", "").trim();

		if (hex.length() != 32) {
			return null;
		}

		try {
			int[] parts = new int[11];
			parts[0] = (int) Long.parseLong(hex.substring(0, 8), 16);
			parts[1] = Integer.parseInt(hex.substring(8, 12), 16);
			parts[2] = Integer.parseInt(hex.substring(12, 16), 16);

			for (int i = 0; i < 8; i++) {
				parts[3 + i] = Integer.parseInt(hex.substring(16 + i * 2, 18 + i * 2), 16);
			}

			return parts;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static String formatGuid(int[] p) {
		StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%08x-%04x-%04x-", p[0], p[1] & 0xFFFF, p[2] & 0xFFFF));

		for (int i = 0; i < 8; i++) {
			sb.append(String.format(Locale.ROOT, "%02x", p[3 + i] & 0xFF));

			if (i == 1) {
				sb.append('-');
			}
		}

		return sb.toString();
	}

	private static String formatGuid(long address) {
		int[] p = new int[11];
		p[0] = MemoryUtil.memGetInt(address);
		p[1] = MemoryUtil.memGetShort(address + 4L);
		p[2] = MemoryUtil.memGetShort(address + 6L);

		for (int i = 0; i < 8; i++) {
			p[3 + i] = MemoryUtil.memGetByte(address + 8L + i);
		}

		return formatGuid(p);
	}

	// --------------------------------------------------------- crash recovery

	@Override
	public void recoverState(Path file) {
		this.stateFile = file;

		if (file == null || !Files.isRegularFile(file) || !Natives.has(pPowerSetActiveScheme)) {
			return;
		}

		Properties props = new Properties();

		try (var r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			props.load(r);
		} catch (IOException e) {
			return;
		}

		int[] previous = parseGuid(props.getProperty("scheme", ""));
		long current = readActiveScheme();

		try {
			// Only undo our own switch; if the user picked a plan since, keep theirs.
			if (previous != null && current != 0L && isHighPerformance(current)) {
				long guid = MemoryUtil.nmemCallocChecked(1L, 16L);

				try {
					writeGuid(guid, previous);

					if (JNI.invokePPI(0L, guid, pPowerSetActiveScheme) == 0) {
						HLog.LOG.info("Hydrogen: restored the power plan left on High Performance by a previous session");
					}
				} finally {
					MemoryUtil.nmemFree(guid);
				}
			}
		} catch (Throwable ignored) {
			// Recovery is best effort.
		} finally {
			if (current != 0L) {
				MemoryUtil.nmemFree(current);
			}
		}

		clearState();
	}

	private void writeState() {
		if (stateFile == null || savedSchemeGuid == 0L) {
			return;
		}

		try {
			Properties props = new Properties();
			props.setProperty("scheme", formatGuid(savedSchemeGuid));

			try (var w = Files.newBufferedWriter(stateFile, StandardCharsets.UTF_8)) {
				props.store(w, "Hydrogen: power plan to restore if the game did not exit cleanly");
			}
		} catch (IOException ignored) {
			// Recovery is a safety net, not a requirement.
		}
	}

	private void clearState() {
		if (stateFile != null) {
			try {
				Files.deleteIfExists(stateFile);
			} catch (IOException ignored) {
				// Harmless; recovery checks the active plan before acting.
			}
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
			freeSaved();
		}
	}

	private void freeSaved() {
		if (savedSchemeGuid != 0L) {
			MemoryUtil.nmemFree(savedSchemeGuid);
			savedSchemeGuid = 0L;
		}
	}
}
