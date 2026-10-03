package dev.hydrogen.core.cpu;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadSweepTest {
	@Test
	void workersAlwaysLeaveTheRenderCores() {
		assertEquals(ThreadSweep.Action.WORKER, ThreadSweep.classify("Worker-Main-12", false));
		assertEquals(ThreadSweep.Action.WORKER, ThreadSweep.classify("Chunk Render Ta", false));
		assertEquals(ThreadSweep.Action.WORKER, ThreadSweep.classify("IO-Worker-3", true));
	}

	@Test
	void jvmInternalsAreNeverTouched() {
		assertEquals(ThreadSweep.Action.LEAVE, ThreadSweep.classify("GC Thread#3", true));
		assertEquals(ThreadSweep.Action.LEAVE, ThreadSweep.classify("C2 CompilerThre", true));
		assertEquals(ThreadSweep.Action.LEAVE, ThreadSweep.classify("ZWorker#1", true));
		assertEquals(ThreadSweep.Action.LEAVE, ThreadSweep.classify("Hydrogen affinity", true));
	}

	@Test
	void inheritedDriverAndAudioThreadsGetEveryCore() {
		assertEquals(ThreadSweep.Action.FULL, ThreadSweep.classify("Render thread", true));
		assertEquals(ThreadSweep.Action.FULL, ThreadSweep.classify("alsoft-mixer", true));
		assertEquals(ThreadSweep.Action.FULL, ThreadSweep.classify("llvmpipe-3", true));
	}

	@Test
	void unknownThreadsOnlyMoveWhenTheyInheritedTheRenderMask() {
		assertEquals(ThreadSweep.Action.LEAVE, ThreadSweep.classify("Netty Client IO", false));
		assertEquals(ThreadSweep.Action.WORKER, ThreadSweep.classify("Netty Client IO", true));
	}

	@Test
	void ownershipAndInheritanceChecks() {
		ThreadSweep s = new ThreadSweep(10L, Set.of(11L), new long[] {0b11L}, new long[] {0b1100L}, new long[] {0b1111L});

		assertTrue(s.owns(10L));
		assertTrue(s.owns(11L));
		assertFalse(s.owns(12L));
		assertTrue(s.isRenderMask(new long[] {0b11L, 0L}));
		assertFalse(s.isRenderMask(new long[] {0b1111L}));
	}
}
