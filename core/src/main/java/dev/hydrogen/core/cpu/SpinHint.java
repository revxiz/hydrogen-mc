package dev.hydrogen.core.cpu;

import dev.hydrogen.core.platform.NativePlatform;

import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * Fallback for machines where the power governor is not writable. Keeping one
 * spare core out of deep C-states lets the package ramp without root access.
 * The thread parks most of its duty cycle, so the cost is a fraction of a core.
 */
final class SpinHint implements AutoCloseable {
	private final NativePlatform platform;
	private volatile boolean active;
	private volatile boolean closed;
	private Thread thread;

	SpinHint(NativePlatform platform) {
		this.platform = platform;
	}

	synchronized void set(boolean on) {
		active = on;

		if (on && thread == null && !closed) {
			thread = new Thread(this::loop, "Hydrogen C-state hint");
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			thread.start();
		}
	}

	private void loop() {
		CpuTopology topo = platform.topology();
		List<LogicalCpu> pool = topo.backgroundPool();

		if (!pool.isEmpty()) {
			platform.bindCurrentThread(CpuTopology.mask(pool.subList(0, 1), topo.logicalCount()));
		}

		while (!closed) {
			if (!active) {
				LockSupport.parkNanos(20_000_000L);
				continue;
			}

			// Roughly 5% duty: enough to hold a shallow C-state, cheap enough to ignore.
			long until = System.nanoTime() + 50_000L;

			while (System.nanoTime() < until) {
				Thread.onSpinWait();
			}

			LockSupport.parkNanos(950_000L);
		}
	}

	@Override
	public synchronized void close() {
		closed = true;
		active = false;

		if (thread != null) {
			LockSupport.unpark(thread);
			thread = null;
		}
	}
}
