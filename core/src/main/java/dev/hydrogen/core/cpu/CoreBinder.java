package dev.hydrogen.core.cpu;

import dev.hydrogen.core.HLog;
import dev.hydrogen.core.config.HConfig;
import dev.hydrogen.core.hw.Budget;
import dev.hydrogen.core.platform.NativePlatform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Builds one affinity mask per {@link ThreadRole} and applies it from inside the
 * thread being pinned, which is the only portable way to reach a thread handle.
 *
 * The thread that owns the frame (the render thread on a client, the server
 * thread on a dedicated server) gets the best physical cores inside one cache
 * domain. Everything else is kept off those cores and their SMT siblings, which
 * is what actually stops a chunk build from landing on the core drawing the
 * frame. Threads Hydrogen cannot reach from the inside are placed by a slow
 * background sweep, see {@link ThreadSweep}.
 *
 * When the native call is unavailable or denied, the thread still gets a JVM
 * priority hint. Nothing here throws: a machine that refuses to be pinned simply
 * runs unpinned.
 */
public final class CoreBinder {
	/** Immutable, so binding threads never see a half-built plan. */
	private record Plan(Map<ThreadRole, long[]> masks, Map<ThreadRole, String> text,
			long[] primary, long[] worker, long[] full) {
	}

	private static final long SWEEP_MS = 5_000L;

	private final NativePlatform platform;
	private final HConfig config;
	private final Budget budget;

	private final AtomicInteger nativeBound = new AtomicInteger();
	private final AtomicInteger hinted = new AtomicInteger();
	private final AtomicInteger jvmOnly = new AtomicInteger();
	private final Set<Long> ownedTids = ConcurrentHashMap.newKeySet();
	private final ThreadLocal<Boolean> done = ThreadLocal.withInitial(() -> Boolean.FALSE);

	private volatile Plan plan;
	private volatile String note = "not built";
	private volatile boolean dedicatedServer;
	private volatile long primaryTid = -1L;
	private volatile int lastSweepMoved;

	private Thread sweeper;
	private volatile boolean sweeping;

	public CoreBinder(NativePlatform platform, HConfig config, Budget budget) {
		this.platform = platform;
		this.config = config;
		this.budget = budget;
	}

	/**
	 * @param dedicated true on a dedicated server, where the server thread owns
	 *                  the frame and gets the cores a client gives its render thread
	 */
	public synchronized void buildPlan(boolean dedicated) {
		this.dedicatedServer = dedicated;

		CpuTopology topo = platform.topology();
		int total = topo.logicalCount();

		if (total < 4) {
			plan = null;
			note = "too few CPUs (" + total + ")";
			return;
		}

		List<LogicalCpu> fast = topo.fastPrimaries();
		LogicalCpu best = fast.get(0);

		// A frame thread migrating between CCDs or clusters pays for it in cache
		// misses, so its cores come from the best core's own cache domain.
		List<LogicalCpu> domain = new ArrayList<>();

		for (LogicalCpu c : fast) {
			if (best.cacheId() < 0 || c.cacheId() == best.cacheId()) {
				domain.add(c);
			}
		}

		int wanted = Math.max(1, Math.min(budget.renderCores(), domain.size()));
		List<LogicalCpu> primary = new ArrayList<>(domain.subList(0, wanted));
		List<LogicalCpu> reserved = topo.siblingsOf(primary);

		List<LogicalCpu> second = new ArrayList<>();

		for (LogicalCpu c : fast) {
			if (!primary.contains(c)) {
				second.add(c);
			}
		}

		if (second.isEmpty()) {
			second.addAll(primary);
		}

		List<LogicalCpu> worker = topo.select(c -> !reserved.contains(c));

		if (worker.isEmpty()) {
			worker = new ArrayList<>(topo.cpus());
		}

		List<LogicalCpu> background = new ArrayList<>(topo.backgroundPool());
		background.removeAll(reserved);

		if (background.isEmpty()) {
			background = worker;
		}

		int span = topo.indexSpan();
		Map<ThreadRole, long[]> masks = new EnumMap<>(ThreadRole.class);
		Map<ThreadRole, String> text = new EnumMap<>(ThreadRole.class);

		put(masks, text, ThreadRole.RENDER, primary, span);
		put(masks, text, ThreadRole.SERVER, dedicated ? primary : second, span);
		put(masks, text, ThreadRole.CHUNK_BUILD, worker, span);
		put(masks, text, ThreadRole.BACKGROUND, background, span);

		plan = new Plan(Collections.unmodifiableMap(masks), Collections.unmodifiableMap(text),
				CpuTopology.mask(primary, span), CpuTopology.mask(worker, span), CpuTopology.mask(topo.cpus(), span));
		String previous = note;
		note = (dedicated ? "server=" : "render=") + describe(primary) + " workers=" + describe(worker);

		// Rebuilt once graphics are up; only worth a line when the answer moved.
		if (config.bool("log.verbose") && !note.equals(previous)) {
			HLog.LOG.info("Hydrogen affinity plan: {}", note);
		}
	}

	private static void put(Map<ThreadRole, long[]> masks, Map<ThreadRole, String> text,
			ThreadRole role, List<LogicalCpu> cpus, int span) {
		masks.put(role, CpuTopology.mask(cpus, span));
		text.put(role, describe(cpus));
	}

	private static String describe(List<LogicalCpu> cpus) {
		StringBuilder sb = new StringBuilder();

		for (LogicalCpu c : cpus) {
			if (sb.length() > 0) {
				sb.append(',');
			}

			sb.append(c.index());
		}

		return sb.length() == 0 ? "-" : sb.toString();
	}

	/** Pins the calling thread once. Later calls from the same thread are free. */
	public void bindCurrent(ThreadRole role) {
		if (done.get()) {
			return;
		}

		done.set(Boolean.TRUE);

		try {
			applyJvmPriority(role);

			Plan p = plan;
			boolean owner = role == (dedicatedServer ? ThreadRole.SERVER : ThreadRole.RENDER);

			if (!config.bool("cpu.affinity.enabled") || p == null) {
				hint(role);
				return;
			}

			if (role == ThreadRole.BACKGROUND && !config.bool("cpu.affinity.pinBackground")) {
				return;
			}

			long[] mask = p.masks().get(role);

			if (mask != null && platform.bindCurrentThread(mask)) {
				nativeBound.incrementAndGet();
				long tid = platform.currentThreadId();

				if (tid >= 0L) {
					ownedTids.add(tid);

					if (owner) {
						primaryTid = tid;
					}
				}

				if (owner && config.bool("cpu.priority.native")) {
					platform.setCurrentThreadPriority(NativePlatform.PRIORITY_HIGH);
				}

				if (config.bool("log.verbose")) {
					HLog.LOG.info("Hydrogen pinned {} -> CPUs {}", Thread.currentThread().getName(), p.text().get(role));
				}
			} else if (!hint(role)) {
				jvmOnly.incrementAndGet();
				HLog.once("bind-fallback",
						"Hydrogen: thread pinning unavailable, falling back to JVM thread priorities");
			}
		} catch (Throwable t) {
			// Pinning is an optimisation; never let it break a game thread.
			HLog.warnOnce("bind-error", "Hydrogen: thread binding failed, continuing unpinned", t);
		}
	}

	private boolean hint(ThreadRole role) {
		if (platform.hintCurrentThread(role)) {
			hinted.incrementAndGet();
			return true;
		}

		return false;
	}

	/** Always applied, and the only lever left when native calls are denied. */
	private void applyJvmPriority(ThreadRole role) {
		if (!config.bool("cpu.priority.fallbackToJvm")) {
			return;
		}

		try {
			Thread t = Thread.currentThread();

			switch (role) {
				case RENDER -> t.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 2));
				case SERVER -> t.setPriority(Thread.NORM_PRIORITY + 1);
				case CHUNK_BUILD -> t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
				case BACKGROUND -> t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
			}
		} catch (Throwable ignored) {
			// A security manager or a locked thread group; harmless.
		}
	}

	/**
	 * Starts the background sweep once the frame thread is pinned. Without a known
	 * frame thread id the sweep could mistake that thread for one that inherited
	 * its mask, so nothing runs until then.
	 */
	public void startSweeper() {
		// Called from every client tick; the common case is a single volatile read.
		if (sweeping || plan == null || primaryTid < 0L) {
			return;
		}

		startSweeperLocked();
	}

	private synchronized void startSweeperLocked() {
		if (sweeper != null) {
			return;
		}

		if (!config.bool("cpu.affinity.enabled") || !config.bool("cpu.affinity.pinBackground")) {
			return;
		}

		sweeping = true;
		sweeper = new Thread(this::sweepLoop, "Hydrogen affinity");
		sweeper.setDaemon(true);
		sweeper.setPriority(Thread.MIN_PRIORITY);
		sweeper.start();
	}

	private void sweepLoop() {
		bindCurrent(ThreadRole.BACKGROUND);
		long delay = 1_000L;

		while (sweeping) {
			LockSupport.parkNanos(delay * 1_000_000L);

			if (!sweeping) {
				break;
			}

			if (sweepNow() < 0) {
				HLog.once("sweep-unsupported",
						"Hydrogen: this OS does not let other threads be placed, only threads Hydrogen starts are pinned");
				break;
			}

			// Catch the burst of threads created right after world join, then settle.
			delay = Math.min(SWEEP_MS, delay * 2L);
		}
	}

	/** One pass. Returns threads moved, or -1 when the platform cannot enumerate threads. */
	public int sweepNow() {
		Plan p = plan;

		if (p == null || primaryTid < 0L) {
			return 0;
		}

		try {
			int moved = platform.sweepThreads(new ThreadSweep(primaryTid, Set.copyOf(ownedTids),
					p.primary(), p.worker(), p.full()));
			lastSweepMoved = moved;

			if (moved > 0 && config.bool("log.verbose")) {
				HLog.LOG.info("Hydrogen: moved {} threads off the frame cores", moved);
			}

			return moved;
		} catch (Throwable t) {
			HLog.warnOnce("sweep-error", "Hydrogen: thread sweep failed, stopping it", t);
			return -1;
		}
	}

	public synchronized void stopSweeper() {
		sweeping = false;

		if (sweeper != null) {
			LockSupport.unpark(sweeper);
			sweeper = null;
		}
	}

	public int nativeBoundThreads() {
		return nativeBound.get();
	}

	public int hintedThreads() {
		return hinted.get();
	}

	public int jvmOnlyThreads() {
		return jvmOnly.get();
	}

	public int lastSweepMoved() {
		return lastSweepMoved;
	}

	public boolean planned() {
		return plan != null;
	}

	public String note() {
		return note;
	}

	public String planFor(ThreadRole role) {
		Plan p = plan;
		return p == null ? "-" : p.text().getOrDefault(role, "-");
	}
}
