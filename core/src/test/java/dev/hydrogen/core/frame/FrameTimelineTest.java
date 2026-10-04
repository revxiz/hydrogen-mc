package dev.hydrogen.core.frame;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrameTimelineTest {
	@Test
	void snapshotMatchesTheIndividualQueries() {
		FrameTimeline t = new FrameTimeline();

		for (int i = 1; i <= 100; i++) {
			t.push(i * 1_000_000L);
		}

		FrameStats s = t.snapshot(80.0D);

		assertEquals(t.percentileMs(0.50D), s.p50Ms(), 1.0E-9);
		assertEquals(t.percentileMs(0.95D), s.p95Ms(), 1.0E-9);
		assertEquals(t.percentileMs(0.99D), s.p99Ms(), 1.0E-9);
		assertEquals(0.20D, s.stallRatio(), 1.0E-9);
		assertEquals(t.stallRatio(80.0D), s.stallRatio(), 1.0E-9);
		assertEquals(100, s.samples());
	}

	@Test
	void ringKeepsOnlyTheNewestFrames() {
		FrameTimeline t = new FrameTimeline();

		for (int i = 0; i < FrameTimeline.CAPACITY; i++) {
			t.push(50_000_000L);
		}

		for (int i = 0; i < FrameTimeline.CAPACITY; i++) {
			t.push(5_000_000L);
		}

		assertEquals(5.0D, t.snapshot(10.0D).p99Ms(), 1.0E-9);
		assertEquals(0.0D, t.snapshot(10.0D).stallRatio(), 1.0E-9);
	}

	@Test
	void absurdFramesAreIgnored() {
		FrameTimeline t = new FrameTimeline();
		t.push(-1L);
		t.push(5_000_000_000L);

		assertEquals(0, t.samples());
		assertEquals(FrameStats.EMPTY, t.snapshot(10.0D));
	}
}
