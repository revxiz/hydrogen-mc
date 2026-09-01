package dev.hydrogen.core.cpu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Snapshot of the machine's CPU layout plus helpers for building affinity masks. */
public final class CpuTopology {
	private final List<LogicalCpu> cpus;
	private final String source;

	public CpuTopology(List<LogicalCpu> cpus, String source) {
		List<LogicalCpu> copy = new ArrayList<>(cpus);
		copy.sort((a, b) -> Integer.compare(a.index(), b.index()));
		this.cpus = Collections.unmodifiableList(copy);
		this.source = source;
	}

	/** Flat fallback used when the OS refuses to describe itself. */
	public static CpuTopology flat(int count) {
		List<LogicalCpu> list = new ArrayList<>(count);

		for (int i = 0; i < count; i++) {
			list.add(new LogicalCpu(i, i, 0, 0, 0L, CpuClass.UNKNOWN));
		}

		return new CpuTopology(list, "fallback");
	}

	public List<LogicalCpu> cpus() {
		return cpus;
	}

	public String source() {
		return source;
	}

	public int logicalCount() {
		return cpus.size();
	}

	public int physicalCount() {
		Set<Long> seen = new LinkedHashSet<>();

		for (LogicalCpu c : cpus) {
			seen.add(((long) c.packageId() << 32) | (c.coreId() & 0xFFFFFFFFL));
		}

		return seen.size();
	}

	public boolean hybrid() {
		boolean p = false;
		boolean e = false;

		for (LogicalCpu c : cpus) {
			p |= c.cpuClass() == CpuClass.PERFORMANCE || c.cpuClass() == CpuClass.PERFORMANCE_SMT;
			e |= c.cpuClass() == CpuClass.EFFICIENCY;
		}

		return p && e;
	}

	public boolean smt() {
		for (LogicalCpu c : cpus) {
			if (c.smtIndex() > 0) {
				return true;
			}
		}

		return false;
	}

	public List<LogicalCpu> select(Predicate<LogicalCpu> filter) {
		List<LogicalCpu> out = new ArrayList<>();

		for (LogicalCpu c : cpus) {
			if (filter.test(c)) {
				out.add(c);
			}
		}

		return out;
	}

	/** Performance primaries ordered by descending clock, best candidates first. */
	public List<LogicalCpu> fastPrimaries() {
		List<LogicalCpu> out = select(c -> c.isPrimaryThread() && c.cpuClass() != CpuClass.EFFICIENCY);

		if (out.isEmpty()) {
			out = select(LogicalCpu::isPrimaryThread);
		}

		if (out.isEmpty()) {
			out = new ArrayList<>(cpus);
		}

		out.sort((a, b) -> Long.compare(b.maxFreqKHz(), a.maxFreqKHz()));
		return out;
	}

	/** Everything that is not a fast primary: E-cores first, then SMT siblings. */
	public List<LogicalCpu> backgroundPool() {
		List<LogicalCpu> out = select(c -> c.cpuClass() == CpuClass.EFFICIENCY);
		out.addAll(select(c -> c.cpuClass() != CpuClass.EFFICIENCY && !c.isPrimaryThread()));

		if (out.isEmpty()) {
			out = new ArrayList<>(cpus);
		}

		return out;
	}

	/** Packs processor indices into a 64-bit-per-group affinity mask. */
	public static long[] mask(List<LogicalCpu> selection, int logicalCount) {
		int groups = Math.max(1, (logicalCount + 63) / 64);
		long[] bits = new long[groups];

		for (LogicalCpu c : selection) {
			int i = c.index();

			if (i >= 0 && i < groups * 64) {
				bits[i >>> 6] |= 1L << (i & 63);
			}
		}

		return bits;
	}

	public String describe() {
		StringBuilder sb = new StringBuilder();
		sb.append(logicalCount()).append(" logical / ").append(physicalCount()).append(" physical");

		if (hybrid()) {
			int p = select(c -> c.cpuClass() == CpuClass.PERFORMANCE).size();
			int e = select(c -> c.cpuClass() == CpuClass.EFFICIENCY).size();
			sb.append(", hybrid ").append(p).append("P/").append(e).append("E");
		}

		if (smt()) {
			sb.append(", SMT");
		}

		sb.append(" [").append(source).append(']');
		return sb.toString();
	}
}
