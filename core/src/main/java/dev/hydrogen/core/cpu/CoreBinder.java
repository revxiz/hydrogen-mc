package dev.hydrogen.core.cpu;

import dev.hydrogen.core.HLog;
import dev.hydrogen.core.config.HConfig;
import dev.hydrogen.core.hw.Budget;
import dev.hydrogen.core.platform.NativePlatform;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds one affinity mask per {@link ThreadRole} and applies it from inside the
 * thread being pinned, which is the only portable way to reach a thread handle.
 *
 * When the native call is unavailable or denied, the thread still gets a JVM
 * priority hint. Nothing here throws: a machine that refuses to be pinned simply
 * runs unpinned.
 */
public final class CoreBinder {
	private final NativePlatform platform;
	private final HConfig config;
	private final Budget budget;

	private final Map<ThreadRole, long[]> masks = new EnumMap<>(ThreadRole.class);
	private final Map<ThreadRole, String> plan = new EnumMap<>(ThreadRole.class);
	private final AtomicInteger nativeBound = new AtomicInteger();
	private final AtomicInteger jvmOnly = new AtomicInteger();
	private final ThreadLocal<Boolean> done = ThreadLocal.withInitial(() -> Boolean.FALSE);

	private boolean planned;
	private String note = "not built";

	public CoreBinder(NativePlatform platform, HConfig config, Budget budget) {
		this.platform = platform;
		this.config = config;
		this.budget = budget;
	}

	/** Deferred until the topology and budget are known. */
	public synchronized void buildPlan() {
		masks.clear();
		plan.clear();
		planned = false;

		CpuTopology topo = platform.topology();
		int total = topo.logicalCount();

		if (total < 4) {
			note = "too few CPUs (" + total + ")";
			return;
		}

		List<LogicalCpu> fast = topo.fastPrimaries();
		List<LogicalCpu> background = topo.backgroundPool();

		int renderWanted = Math.max(1, Math.min(budget.renderCores(), Math.max(1, fast.size() - 1)));
		List<LogicalCpu> render = new ArrayList<>(fast.subList(0, renderWanted));

		List<LogicalCpu> server = new ArrayList<>();

		for (LogicalCpu c : fast) {
			if (!render.contains(c)) {
				server.add(c);
			}
		}

		if (server.isEmpty()) {
			server.addAll(render);
		}

		// Meshing gets everything the frame path does not own, so it never
		// preempts a frame but still scales across all spare hardware.
		List<LogicalCpu> chunk = new ArrayList<>(background);

		for (LogicalCpu c : fast) {
			if (!render.contains(c)) {
				chunk.add(c);
			}
		}

		if (chunk.isEmpty()) {
			chunk.addAll(topo.cpus());
		}

		List<LogicalCpu> bg = background.isEmpty() ? chunk : background;

		put(ThreadRole.RENDER, render, total);
		put(ThreadRole.SERVER, server, total);
		put(ThreadRole.CHUNK_BUILD, chunk, total);
		put(ThreadRole.BACKGROUND, bg, total);

		planned = true;
		note = "render=" + plan.get(ThreadRole.RENDER) + " mesh=" + plan.get(ThreadRole.CHUNK_BUILD);

		if (config.bool("log.verbose")) {
			HLog.LOG.info("Hydrogen affinity plan: {}", note);
		}
	}

	private void put(ThreadRole role, List<LogicalCpu> cpus, int total) {
		masks.put(role, CpuTopology.mask(cpus, total));
		plan.put(role, describe(cpus));
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

			if (!config.bool("cpu.affinity.enabled") || !planned) {
				return;
			}

			if (role == ThreadRole.BACKGROUND && !config.bool("cpu.affinity.pinBackground")) {
				return;
			}

			long[] mask = masks.get(role);

			if (mask != null && platform.bindCurrentThread(mask)) {
				nativeBound.incrementAndGet();

				if (role == ThreadRole.RENDER && config.bool("cpu.priority.native")) {
					platform.setCurrentThreadPriority(NativePlatform.PRIORITY_HIGH);
				}

				if (config.bool("log.verbose")) {
					HLog.LOG.info("Hydrogen pinned {} -> CPUs {}",
							Thread.currentThread().getName(), plan.get(role));
				}
			} else {
				jvmOnly.incrementAndGet();
				HLog.once("bind-fallback",
						"Hydrogen: thread pinning unavailable, falling back to JVM thread priorities");
			}
		} catch (Throwable t) {
			// Pinning is an optimisation; never let it break a game thread.
			HLog.warnOnce("bind-error", "Hydrogen: thread binding failed, continuing unpinned", t);
		}
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

	public int nativeBoundThreads() {
		return nativeBound.get();
	}

	public int jvmOnlyThreads() {
		return jvmOnly.get();
	}

	public boolean planned() {
		return planned;
	}

	public String note() {
		return note;
	}

	public String planFor(ThreadRole role) {
		return plan.getOrDefault(role, "-");
	}
}
