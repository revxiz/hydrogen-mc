package dev.hydrogen.mc.platform;

import dev.hydrogen.core.HLog;
import dev.hydrogen.core.cpu.CpuClass;
import dev.hydrogen.core.cpu.CpuTopology;
import dev.hydrogen.core.cpu.LogicalCpu;
import dev.hydrogen.core.platform.NativePlatform;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Linux implementation.
 *
 * Topology comes from sysfs, which describes hybrid parts and SMT siblings
 * exactly. Affinity uses sched_setaffinity on the calling thread (pid 0 means
 * "this thread" on Linux). The governor is written through sysfs when the user
 * has permission; when it does not, the caller falls back to a C-state hint.
 */
final class LinuxPlatform implements NativePlatform {
	private static final Path CPU_ROOT = Path.of("/sys/devices/system/cpu");
	private static final int CPU_SET_BYTES = 128; // cpu_set_t covers 1024 CPUs.

	private final SharedLibrary libc;
	private final long pSchedSetAffinity;
	private final long pSetPriority;

	private final CpuTopology topology;
	private final List<Path> governorFiles = new ArrayList<>();
	private final Map<Path, String> savedGovernors = new LinkedHashMap<>();
	private boolean governorWritable = true;

	LinuxPlatform() {
		SharedLibrary lib = Natives.open("libc.so.6", "libc.so");
		this.libc = lib;
		this.pSchedSetAffinity = Natives.fn(lib, "sched_setaffinity");
		this.pSetPriority = Natives.fn(lib, "setpriority");
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
			List<Integer> present = parseCpuList(readText(CPU_ROOT.resolve("present")));

			if (present.isEmpty()) {
				return CpuTopology.flat(Runtime.getRuntime().availableProcessors());
			}

			// Intel hybrid parts expose the split directly.
			List<Integer> pCores = parseCpuList(readText(Path.of("/sys/devices/cpu_core/cpus")));
			List<Integer> eCores = parseCpuList(readText(Path.of("/sys/devices/cpu_atom/cpus")));

			List<LogicalCpu> cpus = new ArrayList<>();
			Map<String, Integer> smtSeen = new LinkedHashMap<>();
			long maxFreqSeen = 0L;

			for (int i : present) {
				Path topo = CPU_ROOT.resolve("cpu" + i + "/topology");
				int coreId = parseInt(readText(topo.resolve("core_id")), i);
				int pkgId = parseInt(readText(topo.resolve("physical_package_id")), 0);
				long maxKHz = parseLong(readText(CPU_ROOT.resolve("cpu" + i + "/cpufreq/cpuinfo_max_freq")), 0L);
				maxFreqSeen = Math.max(maxFreqSeen, maxKHz);

				String key = pkgId + ":" + coreId;
				int smtIndex = smtSeen.merge(key, 0, (a, b) -> a + 1);

				CpuClass cls;

				if (!pCores.isEmpty() || !eCores.isEmpty()) {
					cls = eCores.contains(i) ? CpuClass.EFFICIENCY
							: smtIndex == 0 ? CpuClass.PERFORMANCE : CpuClass.PERFORMANCE_SMT;
				} else {
					cls = smtIndex == 0 ? CpuClass.PERFORMANCE : CpuClass.PERFORMANCE_SMT;
				}

				cpus.add(new LogicalCpu(i, coreId, pkgId, smtIndex, maxKHz, cls));
			}

			// No vendor hint but clearly split clocks: treat the slow group as efficiency.
			if (pCores.isEmpty() && eCores.isEmpty() && maxFreqSeen > 0L) {
				cpus = classifyByFrequency(cpus, maxFreqSeen);
			}

			return new CpuTopology(cpus, "sysfs");
		} catch (Throwable t) {
			HLog.warnOnce("linux-topo", "Hydrogen: could not read sysfs CPU topology", t);
			return CpuTopology.flat(Runtime.getRuntime().availableProcessors());
		}
	}

	/** A max clock 20% below the fastest core marks an efficiency cluster. */
	private static List<LogicalCpu> classifyByFrequency(List<LogicalCpu> cpus, long maxFreq) {
		long cut = (long) (maxFreq * 0.80D);
		List<LogicalCpu> out = new ArrayList<>(cpus.size());

		for (LogicalCpu c : cpus) {
			CpuClass cls = c.cpuClass();

			if (c.maxFreqKHz() > 0L && c.maxFreqKHz() < cut) {
				cls = CpuClass.EFFICIENCY;
			}

			out.add(new LogicalCpu(c.index(), c.coreId(), c.packageId(), c.smtIndex(), c.maxFreqKHz(), cls));
		}

		return out;
	}

	/** Parses sysfs lists such as "0-7,16-23". */
	private static List<Integer> parseCpuList(String text) {
		List<Integer> out = new ArrayList<>();

		if (text == null || text.isBlank()) {
			return out;
		}

		for (String part : text.trim().split(",")) {
			if (part.isBlank()) {
				continue;
			}

			int dash = part.indexOf('-');

			try {
				if (dash < 0) {
					out.add(Integer.parseInt(part.trim()));
				} else {
					int from = Integer.parseInt(part.substring(0, dash).trim());
					int to = Integer.parseInt(part.substring(dash + 1).trim());

					for (int i = from; i <= to; i++) {
						out.add(i);
					}
				}
			} catch (NumberFormatException ignored) {
				// Skip malformed ranges.
			}
		}

		out.sort(Comparator.naturalOrder());
		return out;
	}

	// ------------------------------------------------------------- affinity

	@Override
	public boolean bindCurrentThread(long[] mask) {
		if (!Natives.has(pSchedSetAffinity) || mask == null || mask.length == 0) {
			return false;
		}

		long buffer = 0L;

		try {
			buffer = MemoryUtil.nmemCallocChecked(1L, CPU_SET_BYTES);

			for (int i = 0; i < mask.length && i * 8 < CPU_SET_BYTES; i++) {
				MemoryUtil.memPutLong(buffer + (long) i * 8L, mask[i]);
			}

			// sched_setaffinity(0, size, mask): pid 0 targets the calling thread.
			return JNI.invokePPI(0, (long) CPU_SET_BYTES, buffer, pSchedSetAffinity) == 0;
		} catch (Throwable t) {
			HLog.warnOnce("linux-affinity", "Hydrogen: sched_setaffinity failed", t);
			return false;
		} finally {
			if (buffer != 0L) {
				MemoryUtil.nmemFree(buffer);
			}
		}
	}

	@Override
	public boolean setCurrentThreadPriority(int level) {
		// Lowering nice needs CAP_SYS_NICE; try, and let the caller fall back.
		if (!Natives.has(pSetPriority)) {
			return false;
		}

		try {
			int nice = level == PRIORITY_REALTIME_ISH ? -10 : level == PRIORITY_HIGH ? -5 : 0;
			// setpriority(PRIO_PROCESS = 0, who = 0 -> caller, nice)
			return JNI.invokeI(0, 0, nice, pSetPriority) == 0;
		} catch (Throwable t) {
			return false;
		}
	}

	@Override
	public boolean setProcessPriority(int level) {
		return setCurrentThreadPriority(level);
	}

	// ------------------------------------------------------------- governor

	private void findGovernorFiles() {
		try (Stream<Path> policies = Files.list(CPU_ROOT.resolve("cpufreq"))) {
			policies.filter(p -> p.getFileName().toString().startsWith("policy"))
					.map(p -> p.resolve("scaling_governor"))
					.filter(Files::isRegularFile)
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
		if (governorFiles.isEmpty() || !governorWritable) {
			return false;
		}

		boolean any = false;

		for (Path p : governorFiles) {
			try {
				if (on) {
					savedGovernors.putIfAbsent(p, readText(p).trim());
					Files.writeString(p, "performance");
				} else {
					String prev = savedGovernors.remove(p);
					Files.writeString(p, prev == null || prev.isBlank() ? "powersave" : prev);
				}

				any = true;
			} catch (IOException | RuntimeException e) {
				governorWritable = false;
				return false;
			}
		}

		return any;
	}

	@Override
	public boolean onBattery() {
		try (Stream<Path> supplies = Files.list(Path.of("/sys/class/power_supply"))) {
			for (Path p : supplies.toList()) {
				String type = readText(p.resolve("type")).trim();

				if ("Mains".equalsIgnoreCase(type)) {
					return "0".equals(readText(p.resolve("online")).trim());
				}
			}
		} catch (Throwable ignored) {
			// Desktops often have no power_supply entries at all.
		}

		return false;
	}

	@Override
	public void restore() {
		if (!savedGovernors.isEmpty()) {
			requestBoost(false);
		}
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
}
