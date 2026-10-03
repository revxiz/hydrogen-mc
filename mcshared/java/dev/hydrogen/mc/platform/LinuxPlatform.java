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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Linux implementation.
 *
 * Topology comes from sysfs, which describes hybrid parts, SMT siblings, cache
 * domains and firmware core ranking exactly. Affinity uses sched_setaffinity,
 * which on Linux works per thread and can also be applied to other threads of
 * the same process, which the background sweep relies on. The governor is
 * written through sysfs when the user has permission; when it does not, the
 * caller falls back to a C-state hint.
 */
final class LinuxPlatform implements NativePlatform {
	private static final Path CPU_ROOT = Path.of("/sys/devices/system/cpu");
	private static final int CPU_SET_BYTES = 128; // cpu_set_t covers 1024 CPUs.

	private final long pSchedSetAffinity;
	private final long pSchedGetAffinity;
	private final long pSetPriority;

	private final CpuTopology topology;
	private final List<Path> governorFiles = new ArrayList<>();
	private final Map<Path, String> savedGovernors = new LinkedHashMap<>();
	private boolean governorWritable = true;
	private Path stateFile;

	LinuxPlatform() {
		SharedLibrary libc = Natives.open("libc.so.6", "libc.so");
		this.pSchedSetAffinity = Natives.fn(libc, "sched_setaffinity");
		this.pSchedGetAffinity = Natives.fn(libc, "sched_getaffinity");
		this.pSetPriority = Natives.fn(libc, "setpriority");
		this.topology = readTopology();
		findGovernorFiles();
	}

	@Override
	public String name() {
		return "linux";
	}

	@Override
	public boolean available() {
		return Natives.has(pSchedSetAffinity);
	}

	@Override
	public CpuTopology topology() {
		return topology;
	}

	// ------------------------------------------------------------- topology

	private CpuTopology readTopology() {
		try {
			List<Integer> online = CpuTopology.parseCpuList(readText(CPU_ROOT.resolve("online")));

			if (online.isEmpty()) {
				online = CpuTopology.parseCpuList(readText(CPU_ROOT.resolve("present")));
			}

			// Respect taskset and cgroup cpusets: CPUs the process may not use are not ours to plan with.
			Set<Integer> allowed = new HashSet<>(allowedCpus());

			if (!allowed.isEmpty()) {
				online.removeIf(i -> !allowed.contains(i));
			}

			if (online.isEmpty()) {
				return CpuTopology.flat(Runtime.getRuntime().availableProcessors());
			}

			// Intel hybrid parts expose the split directly.
			List<Integer> pCores = CpuTopology.parseCpuList(readText(Path.of("/sys/devices/cpu_core/cpus")));
			List<Integer> eCores = CpuTopology.parseCpuList(readText(Path.of("/sys/devices/cpu_atom/cpus")));
			boolean intelHybrid = !pCores.isEmpty() || !eCores.isEmpty();

			List<LogicalCpu> cpus = new ArrayList<>();
			long maxFreqSeen = 0L;

			for (int i : online) {
				Path dir = CPU_ROOT.resolve("cpu" + i);
				Path topo = dir.resolve("topology");
				int coreId = parseInt(readText(topo.resolve("core_id")), i);
				int pkgId = parseInt(readText(topo.resolve("physical_package_id")), 0);
				int cluster = parseInt(readText(topo.resolve("cluster_id")), -1);
				long maxKHz = parseLong(readText(dir.resolve("cpufreq/cpuinfo_max_freq")), 0L);
				maxFreqSeen = Math.max(maxFreqSeen, maxKHz);

				// core_id repeats across clusters on some ARM parts; fold the cluster in.
				int uniqueCore = cluster >= 0 ? (cluster << 16) | (coreId & 0xFFFF) : coreId;
				int smtIndex = smtIndex(topo, i);

				CpuClass cls = intelHybrid && eCores.contains(i) ? CpuClass.EFFICIENCY
						: smtIndex == 0 ? CpuClass.PERFORMANCE : CpuClass.PERFORMANCE_SMT;

				long[] cache = lastLevelCache(dir);
				cpus.add(new LogicalCpu(i, uniqueCore, pkgId, smtIndex, maxKHz, cls,
						perfRank(dir), (int) cache[0], cache[1]));
			}

			// No vendor hint but clearly split clocks: treat the slow group as efficiency.
			if (!intelHybrid && maxFreqSeen > 0L) {
				cpus = classifyByFrequency(cpus, maxFreqSeen);
			}

			return new CpuTopology(cpus, "sysfs");
		} catch (Throwable t) {
			HLog.warnOnce("linux-topo", "Hydrogen: could not read sysfs CPU topology", t);
			return CpuTopology.flat(Runtime.getRuntime().availableProcessors());
		}
	}

	/** CPUs this process may run on, from the kernel's own status file. */
	private static List<Integer> allowedCpus() {
		try {
			for (String line : Files.readAllLines(Path.of("/proc/self/status"), StandardCharsets.UTF_8)) {
				if (line.startsWith("Cpus_allowed_list:")) {
					return CpuTopology.parseCpuList(line.substring(line.indexOf(':') + 1));
				}
			}
		} catch (Throwable ignored) {
			// Not fatal; plan with every online CPU.
		}

		return List.of();
	}

	/** Position of this CPU among the hardware threads of its core. */
	private static int smtIndex(Path topo, int cpu) {
		List<Integer> siblings = CpuTopology.parseCpuList(readText(topo.resolve("thread_siblings_list")));

		if (siblings.isEmpty()) {
			siblings = CpuTopology.parseCpuList(readText(topo.resolve("core_cpus_list")));
		}

		int idx = siblings.indexOf(cpu);
		return Math.max(0, idx);
	}

	/**
	 * Firmware's own ranking of this core: AMD's preferred-core order, then ACPI
	 * CPPC highest performance (Intel and AMD), then ARM capacity. 0 when unknown.
	 */
	private static int perfRank(Path cpuDir) {
		int rank = parseInt(readText(cpuDir.resolve("cpufreq/amd_pstate_prefcore_ranking")), 0);

		if (rank <= 0) {
			rank = parseInt(readText(cpuDir.resolve("acpi_cppc/highest_perf")), 0);
		}

		if (rank <= 0) {
			rank = parseInt(readText(cpuDir.resolve("cpu_capacity")), 0);
		}

		return Math.max(0, rank);
	}

	/** {domain id, size in KB} of the highest cache level, or {-1, 0}. */
	private static long[] lastLevelCache(Path cpuDir) {
		int bestLevel = -1;
		long id = -1L;
		long kb = 0L;

		for (int idx = 0; idx < 8; idx++) {
			Path c = cpuDir.resolve("cache/index" + idx);

			if (!Files.isDirectory(c)) {
				break;
			}

			String type = readText(c.resolve("type")).trim();

			if ("Instruction".equalsIgnoreCase(type)) {
				continue;
			}

			int level = parseInt(readText(c.resolve("level")), -1);

			if (level <= bestLevel) {
				continue;
			}

			List<Integer> shared = CpuTopology.parseCpuList(readText(c.resolve("shared_cpu_list")));
			bestLevel = level;
			id = shared.isEmpty() ? -1L : shared.get(0);
			kb = parseSizeKb(readText(c.resolve("size")));
		}

		return new long[] {id, kb};
	}

	/** A max clock 20% below the fastest core marks an efficiency cluster. */
	private static List<LogicalCpu> classifyByFrequency(List<LogicalCpu> cpus, long maxFreq) {
		long cut = (long) (maxFreq * 0.80D);
		List<LogicalCpu> out = new ArrayList<>(cpus.size());

		for (LogicalCpu c : cpus) {
			out.add(c.maxFreqKHz() > 0L && c.maxFreqKHz() < cut ? c.withClass(CpuClass.EFFICIENCY) : c);
		}

		return out;
	}

	// ------------------------------------------------------------- affinity

	@Override
	public boolean bindCurrentThread(long[] mask) {
		return setAffinity(0L, mask);
	}

	/** sched_setaffinity on a thread id; 0 means the calling thread. */
	private boolean setAffinity(long tid, long[] mask) {
		if (!Natives.has(pSchedSetAffinity) || mask == null || mask.length == 0) {
			return false;
		}

		long buffer = 0L;

		try {
			buffer = MemoryUtil.nmemCallocChecked(1L, CPU_SET_BYTES);

			for (int i = 0; i < mask.length && i * 8 < CPU_SET_BYTES; i++) {
				MemoryUtil.memPutLong(buffer + (long) i * 8L, mask[i]);
			}

			return JNI.invokePPI((int) tid, (long) CPU_SET_BYTES, buffer, pSchedSetAffinity) == 0;
		} catch (Throwable t) {
			HLog.warnOnce("linux-affinity", "Hydrogen: sched_setaffinity failed", t);
			return false;
		} finally {
			if (buffer != 0L) {
				MemoryUtil.nmemFree(buffer);
			}
		}
	}

	private long[] getAffinity(long tid) {
		if (!Natives.has(pSchedGetAffinity)) {
			return null;
		}

		long buffer = 0L;

		try {
			buffer = MemoryUtil.nmemCallocChecked(1L, CPU_SET_BYTES);

			if (JNI.invokePPI((int) tid, (long) CPU_SET_BYTES, buffer, pSchedGetAffinity) != 0) {
				return null;
			}

			long[] out = new long[CPU_SET_BYTES / 8];

			for (int i = 0; i < out.length; i++) {
				out[i] = MemoryUtil.memGetLong(buffer + (long) i * 8L);
			}

			return out;
		} catch (Throwable t) {
			return null;
		} finally {
			if (buffer != 0L) {
				MemoryUtil.nmemFree(buffer);
			}
		}
	}

	/** /proc/thread-self links to /proc/&lt;pid&gt;/task/&lt;tid&gt;, no native call needed. */
	@Override
	public long currentThreadId() {
		try {
			String link = Files.readSymbolicLink(Path.of("/proc/thread-self")).toString();
			return Long.parseLong(link.substring(link.lastIndexOf('/') + 1));
		} catch (Throwable t) {
			return -1L;
		}
	}

	@Override
	public int sweepThreads(ThreadSweep sweep) {
		if (!Natives.has(pSchedSetAffinity, pSchedGetAffinity)) {
			return -1;
		}

		int moved = 0;

		try (Stream<Path> tasks = Files.list(Path.of("/proc/self/task"))) {
			for (Path task : tasks.toList()) {
				long tid;

				try {
					tid = Long.parseLong(task.getFileName().toString());
				} catch (NumberFormatException e) {
					continue;
				}

				if (sweep.owns(tid)) {
					continue;
				}

				long[] current = getAffinity(tid);

				if (current == null) {
					continue; // The thread exited mid-sweep.
				}

				String name = readText(task.resolve("comm")).trim();
				long[] target = sweep.maskFor(ThreadSweep.classify(name, sweep.isRenderMask(current)));

				if (target != null && !sameBits(current, target) && setAffinity(tid, target)) {
					moved++;
				}
			}
		} catch (IOException e) {
			return -1;
		}

		return moved;
	}

	private static boolean sameBits(long[] a, long[] b) {
		int n = Math.max(a.length, b.length);

		for (int i = 0; i < n; i++) {
			if ((i < a.length ? a[i] : 0L) != (i < b.length ? b[i] : 0L)) {
				return false;
			}
		}

		return true;
	}

	@Override
	public boolean setCurrentThreadPriority(int level) {
		// Lowering nice needs CAP_SYS_NICE; try, and let the caller fall back.
		if (!Natives.has(pSetPriority)) {
			return false;
		}

		try {
			int nice = level == PRIORITY_REALTIME_ISH ? -10 : level == PRIORITY_HIGH ? -5 : 0;
			// setpriority(PRIO_PROCESS = 0, who = 0 -> caller, nice). On Linux this is per thread.
			return JNI.invokeI(0, 0, nice, pSetPriority) == 0;
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * Linux has no process-wide nice value: setpriority on "this process" only
	 * changes the calling thread. Applying it here would renice whichever loader
	 * thread booted Hydrogen, so the render thread is raised where it is pinned.
	 */
	@Override
	public boolean setProcessPriority(int level) {
		return false;
	}

	// ------------------------------------------------------------- governor

	private void findGovernorFiles() {
		try (Stream<Path> policies = Files.list(CPU_ROOT.resolve("cpufreq"))) {
			policies.filter(p -> p.getFileName().toString().startsWith("policy"))
					.map(p -> p.resolve("scaling_governor"))
					.filter(Files::isRegularFile)
					.sorted()
					.forEach(governorFiles::add);
		} catch (Throwable ignored) {
			// Older kernels expose per-CPU paths instead.
		}

		if (governorFiles.isEmpty()) {
			for (LogicalCpu c : topology.cpus()) {
				Path p = CPU_ROOT.resolve("cpu" + c.index() + "/cpufreq/scaling_governor");

				if (Files.isRegularFile(p)) {
					governorFiles.add(p);
				}
			}
		}
	}

	@Override
	public boolean requestBoost(boolean on) {
		return on ? boost() : unboost();
	}

	private boolean boost() {
		if (governorFiles.isEmpty() || !governorWritable) {
			return false;
		}

		boolean any = false;

		for (Path p : governorFiles) {
			try {
				String prev = readText(p).trim();

				if ("performance".equals(prev)) {
					any = true; // Already at peak; nothing to change or restore.
					continue;
				}

				if (prev.isEmpty()) {
					continue;
				}

				Files.writeString(p, "performance");
				savedGovernors.putIfAbsent(p, prev);
				any = true;
			} catch (IOException | RuntimeException e) {
				// Normally EACCES without root. Policies already switched stay recorded
				// and are put back by unboost, which does not depend on this flag.
				governorWritable = false;
				break;
			}
		}

		writeState();
		return any;
	}

	/** Restores exactly the policies that were changed, and nothing else. */
	private boolean unboost() {
		if (savedGovernors.isEmpty()) {
			return false;
		}

		boolean all = true;

		for (Map.Entry<Path, String> e : new ArrayList<>(savedGovernors.entrySet())) {
			try {
				Files.writeString(e.getKey(), e.getValue());
				savedGovernors.remove(e.getKey());
			} catch (IOException | RuntimeException ex) {
				all = false;
			}
		}

		writeState();
		return all;
	}

	@Override
	public void recoverState(Path file) {
		this.stateFile = file;

		if (file == null || !Files.isRegularFile(file)) {
			return;
		}

		Properties props = new Properties();

		try (var r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			props.load(r);
		} catch (IOException e) {
			return;
		}

		int restored = 0;

		for (String key : props.stringPropertyNames()) {
			if (!key.startsWith("governor.")) {
				continue;
			}

			Path p = Path.of(key.substring("governor.".length()));

			// Only undo our own change; if someone set it since, leave theirs alone.
			if (p.startsWith(CPU_ROOT) && "performance".equals(readText(p).trim())) {
				try {
					Files.writeString(p, props.getProperty(key));
					restored++;
				} catch (IOException | RuntimeException ignored) {
					// Still not writable; nothing else to try.
				}
			}
		}

		try {
			Files.deleteIfExists(file);
		} catch (IOException ignored) {
			// Harmless; the next recovery re-checks every governor anyway.
		}

		if (restored > 0) {
			HLog.LOG.info("Hydrogen: restored {} CPU governors left boosted by a previous session", restored);
		}
	}

	private void writeState() {
		if (stateFile == null) {
			return;
		}

		try {
			if (savedGovernors.isEmpty()) {
				Files.deleteIfExists(stateFile);
				return;
			}

			Properties props = new Properties();
			savedGovernors.forEach((p, g) -> props.setProperty("governor." + p, g));

			try (var w = Files.newBufferedWriter(stateFile, StandardCharsets.UTF_8)) {
				props.store(w, "Hydrogen: CPU governors to restore if the game did not exit cleanly");
			}
		} catch (IOException ignored) {
			// Recovery is a safety net, not a requirement.
		}
	}

	@Override
	public boolean onBattery() {
		boolean mainsOnline = false;
		boolean mainsSeen = false;
		boolean discharging = false;

		try (Stream<Path> supplies = Files.list(Path.of("/sys/class/power_supply"))) {
			for (Path p : supplies.toList()) {
				String type = readText(p.resolve("type")).trim();

				if ("Mains".equalsIgnoreCase(type) || "USB".equalsIgnoreCase(type)) {
					mainsSeen |= "Mains".equalsIgnoreCase(type);
					mainsOnline |= "1".equals(readText(p.resolve("online")).trim());
				} else if ("Battery".equalsIgnoreCase(type)) {
					discharging |= "Discharging".equalsIgnoreCase(readText(p.resolve("status")).trim());
				}
			}
		} catch (Throwable ignored) {
			// Desktops often have no power_supply entries at all.
		}

		return !mainsOnline && (discharging || mainsSeen);
	}

	@Override
	public void restore() {
		unboost();
	}

	// ---------------------------------------------------------------- utils

	private static String readText(Path p) {
		try {
			return Files.readString(p);
		} catch (Throwable t) {
			return "";
		}
	}

	private static int parseInt(String s, int fallback) {
		try {
			return Integer.parseInt(s.trim());
		} catch (Throwable t) {
			return fallback;
		}
	}

	private static long parseLong(String s, long fallback) {
		try {
			return Long.parseLong(s.trim());
		} catch (Throwable t) {
			return fallback;
		}
	}

	/** sysfs cache sizes look like "32768K" or "96M". */
	private static long parseSizeKb(String s) {
		String t = s.trim().toUpperCase(java.util.Locale.ROOT);

		if (t.isEmpty()) {
			return 0L;
		}

		long mul = 1L;
		char last = t.charAt(t.length() - 1);

		if (last == 'K') {
			t = t.substring(0, t.length() - 1);
		} else if (last == 'M') {
			mul = 1024L;
			t = t.substring(0, t.length() - 1);
		} else if (last == 'G') {
			mul = 1024L * 1024L;
			t = t.substring(0, t.length() - 1);
		} else {
			return parseLong(t, 0L) / 1024L;
		}

		return parseLong(t, 0L) * mul;
	}
}
