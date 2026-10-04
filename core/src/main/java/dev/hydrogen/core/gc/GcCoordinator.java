package dev.hydrogen.core.gc;

import com.sun.management.HotSpotDiagnosticMXBean;
import dev.hydrogen.core.HLog;
import dev.hydrogen.core.config.HConfig;
import dev.hydrogen.core.hw.Budget;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Listens to GC notifications and pulls collections forward into moments the
 * player will not feel: a paused game, a screen open, or standing still.
 *
 * The JVM owns the final decision. All this does is move the request earlier so
 * the collector is less likely to fire mid-swing. Trigger levels come from the
 * measured allocation rate, not from a fixed percentage.
 *
 * The request is made from a background thread, never the render thread, so a
 * concurrent collector (ZGC, Shenandoah, or G1 with ExplicitGCInvokesConcurrent)
 * costs the frame nothing. When the collector would stop the world, idle moments
 * in the world are skipped and only paused or menu time is used.
 */
public final class GcCoordinator {
	private static final String GC_NOTIFICATION = "com.sun.management.gc.notification";

	private final HConfig config;
	private final Budget budget;
	private final List<NotificationEmitter> emitters = new ArrayList<>();
	private final NotificationListener listener = this::onNotification;
	private final AtomicBoolean collecting = new AtomicBoolean();

	private MemoryMXBean memory;
	private boolean explicitGcDisabled;
	private boolean concurrentExplicit;
	private String collector = "unknown";
	private boolean installed;

	private volatile long lastSweepMs;
	private volatile long lastActionMs;
	private volatile long sweeps;
	private volatile long pauses;
	private volatile long pauseMsTotal;
	private volatile long worstPauseMs;
	private volatile long pausesDuringAction;
	private volatile long reclaimedMb;

	public GcCoordinator(HConfig config, Budget budget) {
		this.config = config;
		this.budget = budget;
	}

	/** Safe on a JVM without the management module; failure disables the feature. */
	public void install() {
		if (installed) {
			return;
		}

		try {
			memory = ManagementFactory.getMemoryMXBean();
			explicitGcDisabled = vmFlag("DisableExplicitGC");

			StringBuilder names = new StringBuilder();

			for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
				names.append(bean.getName()).append(';');

				if (bean instanceof NotificationEmitter emitter) {
					emitter.addNotificationListener(listener, null, null);
					emitters.add(emitter);
				}
			}

			String all = names.toString().toLowerCase(Locale.ROOT);

			if (all.contains("zgc")) {
				collector = "ZGC";
				concurrentExplicit = true;
			} else if (all.contains("shenandoah")) {
				collector = "Shenandoah";
				concurrentExplicit = true;
			} else if (all.contains("g1")) {
				collector = "G1";
				concurrentExplicit = vmFlag("ExplicitGCInvokesConcurrent");
			} else if (all.contains("ps ") || all.contains("parallel")) {
				collector = "Parallel";
			} else if (all.contains("copy") || all.contains("marksweep")) {
				collector = "Serial";
			}

			installed = true;

			if (explicitGcDisabled) {
				HLog.once("gc-disabled",
						"Hydrogen: -XX:+DisableExplicitGC is set, GC coordination limited to reporting");
			}
		} catch (Throwable t) {
			HLog.warnOnce("gc-install", "Hydrogen: GC coordination unavailable on this JVM", t);
		}
	}

	/** Reads the live VM flag, which also covers JAVA_TOOL_OPTIONS and argument files. */
	private static boolean vmFlag(String name) {
		try {
			HotSpotDiagnosticMXBean hs = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);

			if (hs != null) {
				return Boolean.parseBoolean(hs.getVMOption(name).getValue());
			}
		} catch (Throwable ignored) {
			// Not HotSpot, or the flag does not exist. Fall back to the command line.
		}

		try {
			for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
				if (arg.contains("+" + name)) {
					return true;
				}
			}
		} catch (Throwable ignored) {
			// Argument list is not always readable.
		}

		return false;
	}

	private void onNotification(Notification n, Object handback) {
		if (!GC_NOTIFICATION.equals(n.getType()) || !(n.getUserData() instanceof CompositeData data)) {
			return;
		}

		try {
			String name = String.valueOf(data.get("gcName")).toLowerCase(Locale.ROOT);

			// Concurrent cycles report their whole length, which is not a pause.
			if (name.contains("cycles") || name.contains("concurrent")) {
				return;
			}

			CompositeData info = (CompositeData) data.get("gcInfo");
			long durationMs = (Long) info.get("duration");

			pauses++;
			pauseMsTotal += durationMs;

			if (durationMs > worstPauseMs) {
				worstPauseMs = durationMs;
			}

			if (System.currentTimeMillis() - lastActionMs < config.fixed("gc.combatLockoutMs")) {
				pausesDuringAction++;
			}
		} catch (Throwable ignored) {
			// Vendor-specific payload shape; these counters are cosmetic.
		}
	}

	/** Called whenever the player attacks, is hurt, or uses an item. */
	public void markAction() {
		lastActionMs = System.currentTimeMillis();
	}

	/**
	 * @param movementSpeed blocks per tick of horizontal motion
	 * @param screenOpen    a non-render screen such as inventory or a chest is open
	 * @param paused        the game loop is paused
	 */
	public void tick(double movementSpeed, boolean screenOpen, boolean paused) {
		if (!installed || !config.bool("gc.enabled") || explicitGcDisabled || memory == null || collecting.get()) {
			return;
		}

		long now = System.currentTimeMillis();

		if (now - lastSweepMs < budget.gcMinIntervalSeconds() * 1000.0D) {
			return;
		}

		if (now - lastActionMs < config.fixed("gc.combatLockoutMs")) {
			return;
		}

		boolean idle = movementSpeed <= 0.012D && config.boolAuto("gc.allowWhileIdle", concurrentExplicit);
		boolean window = paused || idle || (screenOpen && config.bool("gc.allowOnScreenOpen"));

		if (!window || heapPercent() < budget.gcHeapTriggerPercent()) {
			return;
		}

		lastSweepMs = now;
		collectAsync();
	}

	/**
	 * A collection asked for from the console. It goes through the same
	 * background thread as the automatic ones, so the game never waits for it.
	 */
	public boolean collectNow() {
		if (memory == null || explicitGcDisabled) {
			return false;
		}

		lastSweepMs = System.currentTimeMillis();
		collectAsync();
		return true;
	}

	/** The render thread only hands the request over; it never waits on the collector. */
	private void collectAsync() {
		if (!collecting.compareAndSet(false, true)) {
			return;
		}

		Thread t = new Thread(() -> {
			try {
				long before = used();
				sweeps++;
				System.gc();
				long after = used();

				if (after < before) {
					reclaimedMb += (before - after) / 1048576L;
				}
			} catch (Throwable ignored) {
				// Nothing to recover; the next window tries again.
			} finally {
				collecting.set(false);
			}
		}, "Hydrogen GC request");

		t.setDaemon(true);
		t.start();
	}

	private long used() {
		try {
			return memory.getHeapMemoryUsage().getUsed();
		} catch (Throwable t) {
			return 0L;
		}
	}

	public double heapPercent() {
		if (memory == null) {
			return 0.0D;
		}

		try {
			MemoryUsage u = memory.getHeapMemoryUsage();
			long max = u.getMax() > 0L ? u.getMax() : u.getCommitted();
			return max > 0L ? 100.0D * u.getUsed() / max : 0.0D;
		} catch (Throwable t) {
			return 0.0D;
		}
	}

	public long heapUsedBytes() {
		return used();
	}

	public String collector() {
		return collector + (concurrentExplicit ? " (concurrent)" : "");
	}

	public GcStats stats() {
		return new GcStats(
				sweeps,
				pauses,
				pausesDuringAction,
				pauses == 0L ? 0.0D : (double) pauseMsTotal / pauses,
				worstPauseMs,
				heapPercent(),
				reclaimedMb,
				installed && !explicitGcDisabled);
	}

	public void shutdown() {
		for (NotificationEmitter emitter : emitters) {
			try {
				emitter.removeNotificationListener(listener);
			} catch (Throwable ignored) {
				// Already detached during teardown.
			}
		}

		emitters.clear();
		installed = false;
	}
}
