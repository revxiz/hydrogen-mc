package dev.hydrogen.core.cpu;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Decides where threads Hydrogen did not start itself should run.
 *
 * Two problems are solved here. On Linux a new thread inherits the affinity of
 * the thread that created it, and the render thread creates most of the game's
 * threads after it has been pinned: the worker pool, Sodium's chunk builders,
 * the integrated server, Netty. Left alone they would all be squeezed onto the
 * render cores, which is the opposite of the point. Separately, known heavy
 * worker threads are moved off the render cores on every platform, so meshing
 * never lands on the core drawing the frame.
 *
 * Everything else is left exactly as the OS placed it. JVM internals in
 * particular keep every core, so garbage collection pauses stay as short as
 * the machine allows.
 */
public final class ThreadSweep {
	public enum Action {
		/** Not ours to touch. */
		LEAVE,
		/** Undo an inherited render mask without restricting the thread. */
		FULL,
		/** Keep it off the render cores. */
		WORKER
	}

	private static final String[] JVM_PREFIXES = {
			"gc thread", "g1 ", "gc ", "zworker", "zdirector", "zdriver", "zstat", "zunmapper", "zuncommitter",
			"shenandoah", "c1 compilerthre", "c2 compilerthre", "compilerthread", "vm thread", "vm periodic",
			"reference handl", "finalizer", "signal dispatch", "service thread", "notification th",
			"monitor deflati", "common-cleaner", "jfr ", "attach listener", "sweeper"
	};

	/** Latency-sensitive helpers that may have inherited the render mask: audio and GPU driver threads. */
	private static final String[] HELPER_PREFIXES = {
			"render thread", "alsoft", "openal", "llvmpipe", "glthread", "gdrv", "nv", "radeon", "si_shader",
			"shader", "amdgpu", "iris", "crocus", "zink", "disk_cache", "pulse", "pipewire", "jack", "gmain"
	};

	/** Heavy workers that should never share the render cores. */
	private static final String[] WORKER_MARKERS = {
			"worker-", "chunk", "mesh", "c2me", "builder", "download-", "io-worker"
	};

	private final long renderTid;
	private final Set<Long> owned;
	private final long[] renderMask;
	private final long[] workerMask;
	private final long[] fullMask;

	/**
	 * @param renderTid  OS id of the thread that owns the frame
	 * @param owned      threads Hydrogen pinned itself, never touched by the sweep
	 * @param renderMask cores reserved for the frame thread
	 * @param workerMask everything else, minus the SMT siblings of the reserved cores
	 * @param fullMask   every core
	 */
	public ThreadSweep(long renderTid, Set<Long> owned, long[] renderMask, long[] workerMask, long[] fullMask) {
		this.renderTid = renderTid;
		this.owned = Set.copyOf(owned);
		this.renderMask = renderMask.clone();
		this.workerMask = workerMask.clone();
		this.fullMask = fullMask.clone();
	}

	public long renderTid() {
		return renderTid;
	}

	/** Threads Hydrogen pinned from the inside, including the frame thread. */
	public boolean owns(long tid) {
		return tid == renderTid || owned.contains(tid);
	}

	public long[] renderMask() {
		return renderMask.clone();
	}

	public long[] workerMask() {
		return workerMask.clone();
	}

	public long[] fullMask() {
		return fullMask.clone();
	}

	/** True when {@code current} is exactly the render set, meaning it was inherited. */
	public boolean isRenderMask(long[] current) {
		return sameBits(current, renderMask);
	}

	public long[] maskFor(Action action) {
		return switch (action) {
			case FULL -> fullMask.clone();
			case WORKER -> workerMask.clone();
			case LEAVE -> null;
		};
	}

	/**
	 * @param name            native thread name, possibly truncated to 15 characters
	 * @param inheritedRender the thread currently runs on exactly the render cores
	 */
	public static Action classify(String name, boolean inheritedRender) {
		String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);

		if (n.startsWith("hydrogen") || startsWithAny(n, JVM_PREFIXES)) {
			return Action.LEAVE;
		}

		if (containsAny(n, WORKER_MARKERS)) {
			return Action.WORKER;
		}

		if (!inheritedRender) {
			return Action.LEAVE;
		}

		// Driver and audio threads are spawned by the render thread and often keep its
		// name. They work in lockstep with it, so they get every core rather than the
		// worker set.
		return startsWithAny(n, HELPER_PREFIXES) ? Action.FULL : Action.WORKER;
	}

	private static boolean startsWithAny(String n, String[] prefixes) {
		for (String p : prefixes) {
			if (n.startsWith(p)) {
				return true;
			}
		}

		return false;
	}

	private static boolean containsAny(String n, String[] markers) {
		for (String m : markers) {
			if (n.contains(m)) {
				return true;
			}
		}

		return false;
	}

	static boolean sameBits(long[] a, long[] b) {
		int n = Math.max(a.length, b.length);

		for (int i = 0; i < n; i++) {
			long x = i < a.length ? a[i] : 0L;
			long y = i < b.length ? b[i] : 0L;

			if (x != y) {
				return false;
			}
		}

		return true;
	}

	@Override
	public String toString() {
		return "ThreadSweep[render=" + Arrays.toString(renderMask) + " worker=" + Arrays.toString(workerMask) + "]";
	}
}
