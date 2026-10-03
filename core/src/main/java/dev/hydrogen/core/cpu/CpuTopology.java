package dev.hydrogen.core.cpu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
		copy.sort(Comparator.comparingInt(LogicalCpu::index));
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

	/** Highest OS processor index plus one. Masks are sized from this, not the count. */
	public int indexSpan() {
		int max = -1;

		for (LogicalCpu c : cpus) {
			max = Math.max(max, c.index());
		}

		return max + 1;
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

	/** True when cores sit behind last-level caches of different sizes, as on X3D parts. */
	public boolean mixedCache() {
		long size = -1L;

		for (LogicalCpu c : cpus) {
			if (c.cacheKb() <= 0L) {
				continue;
			}

			if (size < 0L) {
				size = c.cacheKb();
			} else if (size != c.cacheKb()) {
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

	/**
	 * Best candidates for latency-critical threads first.
	 *
	 * On a part with uneven caches (a Ryzen X3D), the large cache wins over a
	 * slightly higher clock: Minecraft's render thread is far more sensitive to
	 * cache misses than to a few hundred MHz, which is also why AMD's own driver
	 * parks the frequency CCD for games. After that the firmware's own ranking
	 * (CPPC preferred cores, scheduling class) and finally the advertised clock.
	 */
	public Comparator<LogicalCpu> preference() {
		Comparator<LogicalCpu> order = Comparator.comparingInt(LogicalCpu::perfRank).reversed()
				.thenComparing(Comparator.comparingLong(LogicalCpu::maxFreqKHz).reversed())
				.thenComparingInt(LogicalCpu::index);

		if (mixedCache()) {
			order = Comparator.comparingLong(LogicalCpu::cacheKb).reversed().thenComparing(order);
		}

		return order;
	}

	/** Performance primaries ordered by preference, best candidates first. */
	public List<LogicalCpu> fastPrimaries() {
		List<LogicalCpu> out = select(c -> c.isPrimaryThread() && c.cpuClass() != CpuClass.EFFICIENCY);

		if (out.isEmpty()) {
			out = select(LogicalCpu::isPrimaryThread);
		}

		if (out.isEmpty()) {
			out = new ArrayList<>(cpus);
		}

		out.sort(preference());
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

	/** Every logical CPU sharing a physical core with one of {@code owners}. */
	public List<LogicalCpu> siblingsOf(List<LogicalCpu> owners) {
		return select(c -> {
			for (LogicalCpu o : owners) {
				if (c.sameCore(o)) {
					return true;
				}
			}

			return false;
		});
	}

	/** Packs processor indices into a 64-bit-per-group affinity mask. */
	public static long[] mask(List<LogicalCpu> selection, int indexSpan) {
		int groups = Math.max(1, (indexSpan + 63) / 64);

		for (LogicalCpu c : selection) {
			groups = Math.max(groups, (c.index() >>> 6) + 1);
		}

		long[] bits = new long[groups];

		for (LogicalCpu c : selection) {
			int i = c.index();

			if (i >= 0) {
				bits[i >>> 6] |= 1L << (i & 63);
			}
		}

		return bits;
	}

	/** Parses kernel CPU lists such as {@code 0-7,16-23}. Malformed parts are skipped. */
	public static List<Integer> parseCpuList(String text) {
		List<Integer> out = new ArrayList<>();

		if (text == null || text.isBlank()) {
			return out;
		}

		for (String part : text.trim().split(",")) {
			String p = part.trim();

			if (p.isEmpty()) {
				continue;
			}

			int dash = p.indexOf('-');

			try {
				if (dash < 0) {
					out.add(Integer.parseInt(p));
				} else {
					int from = Integer.parseInt(p.substring(0, dash).trim());
					int to = Integer.parseInt(p.substring(dash + 1).trim());

					// A corrupt range should not turn into millions of entries.
					for (int i = from; i <= to && i - from < 8192; i++) {
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

		if (mixedCache()) {
			sb.append(", mixed L3");
		}

		sb.append(" [").append(source).append(']');
		return sb.toString();
	}
}
