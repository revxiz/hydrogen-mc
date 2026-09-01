package dev.hydrogen.mc;

import net.minecraft.client.Minecraft;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Sections whose rebuild was postponed because they sat behind the player during
 * an over-budget frame.
 *
 * Deferred work is always replayed: dropping a dirty section outright would
 * leave stale geometry on screen, so each one is remembered and re-marked once
 * frames are comfortable again. When the buffer is full nothing is deferred, so
 * the queue can never grow without bound.
 */
public final class DeferredSections {
	private static final int CAPACITY = 4096;
	private static final int REPLAY_PER_TICK = 24;

	private static final Set<Long> pending = new LinkedHashSet<>();

	private DeferredSections() {
	}

	public static synchronized boolean offer(int x, int y, int z) {
		if (pending.size() >= CAPACITY) {
			return false;
		}

		pending.add(pack(x, y, z));
		return true;
	}

	/** Called from the client tick once the frame budget is being met again. */
	public static void replay(Minecraft mc, double frameMs, double budgetMs) {
		if (mc.levelRenderer == null || frameMs > budgetMs) {
			return;
		}

		long[] batch;

		synchronized (DeferredSections.class) {
			if (pending.isEmpty()) {
				return;
			}

			int n = Math.min(REPLAY_PER_TICK, pending.size());
			batch = new long[n];
			var it = pending.iterator();

			for (int i = 0; i < n && it.hasNext(); i++) {
				batch[i] = it.next();
				it.remove();
			}
		}

		for (long packed : batch) {
			// x: bits 38-63, y: bits 26-37, z: bits 0-25, all sign extended.
			SectionDirtyBridge.markDirty(mc,
					(int) (packed >> 38),
					(int) (packed << 26 >> 52),
					(int) (packed << 38 >> 38));
		}
	}

	public static synchronized int pendingCount() {
		return pending.size();
	}

	public static synchronized void clear() {
		pending.clear();
	}

	private static long pack(int x, int y, int z) {
		return ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) << 26 | ((long) z & 0x3FFFFFFL);
	}
}
