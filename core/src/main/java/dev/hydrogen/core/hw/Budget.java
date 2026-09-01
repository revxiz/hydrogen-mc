package dev.hydrogen.core.hw;

import dev.hydrogen.core.config.HConfig;
import dev.hydrogen.core.cpu.CpuTopology;
import dev.hydrogen.core.tune.Baseline;

/**
 * Every threshold Hydrogen acts on, derived from probed hardware and the
 * calibration baseline. Nothing here is a fixed constant tied to one machine:
 * the frame target comes from the panel, VRAM limits from the driver, pixel
 * limits from the framebuffer and DPI, and GC limits from measured churn.
 *
 * A user value in the config replaces the derived one for that key alone.
 */
public final class Budget {
	private final HConfig config;
	private final HardwareProfile hw;

	private volatile Baseline baseline = Baseline.NONE;

	public Budget(HConfig config, HardwareProfile hw) {
		this.config = config;
		this.hw = hw;
	}

	public void setBaseline(Baseline baseline) {
		this.baseline = baseline == null ? Baseline.NONE : baseline;
	}

	public Baseline baseline() {
		return baseline;
	}

	// ---------------------------------------------------------------- frame

	/**
	 * Frame time the tuner aims at.
	 *
	 * Normally the panel's own rate. When calibration shows the machine cannot
	 * hold that even with scaling at its floor, the target steps down through
	 * refresh divisors (144 to 72 to 48) and settles on the fastest one it can
	 * actually keep. Chasing an impossible number would just pin the governor on
	 * and park resolution at the floor for no gain.
	 */
	public double targetFrameMs() {
		if (!config.isAuto("target.frameTimeMs")) {
			return config.number("target.frameTimeMs", hw.display().targetFrameMs());
		}

		double panel = hw.display().targetFrameMs();

		if (!baseline.valid()) {
			return panel;
		}

		double reachable = reachableMs();

		if (reachable <= panel) {
			return panel;
		}

		for (int divisor = 2; divisor <= 4; divisor++) {
			if (reachable <= panel * divisor) {
				return panel * divisor;
			}
		}

		return panel * 4.0D;
	}

	/**
	 * Best frame time the machine could plausibly reach. GPU cost tracks pixel
	 * count, so scaling to the floor buys back roughly scale^1.5 once the CPU
	 * side is accounted for.
	 */
	private double reachableMs() {
		double p95 = baseline.p95Ms();

		if (!config.bool("drs.enabled")) {
			return p95;
		}

		return p95 * Math.pow(drsMinScale(), 1.5D);
	}

	/**
	 * Multiplier applied to the target before a frame counts as a stall.
	 * A machine with noisy frame pacing gets more slack, so the governor does not
	 * chase normal variance.
	 */
	public double toleranceFactor() {
		double derived = 1.15D;

		if (baseline.valid()) {
			double relJitter = baseline.jitterMs() / Math.max(0.5D, baseline.p50Ms());
			derived = 1.05D + Math.min(0.30D, relJitter * 1.5D);
		}

		return config.number("target.toleranceFactor", derived);
	}

	/**
	 * Frame time resolution scaling works against.
	 *
	 * Deliberately tighter than the governor's stall threshold: jitter slack
	 * belongs to power decisions, not to picture quality. Widening this too would
	 * double-discount the target and leave scaling asleep on a machine that needs
	 * it.
	 */
	public double drsBudgetMs() {
		return targetFrameMs() * 1.05D;
	}

	/** Frame time above which the governor asks for peak clocks. */
	public double stallMs() {
		return targetFrameMs() * toleranceFactor();
	}

	/** Frame time below which the governor lets the clocks fall back. */
	public double releaseMs() {
		return targetFrameMs() * Math.max(0.80D, toleranceFactor() - 0.25D);
	}

	/** Fraction of the window allowed to overrun before acting. */
	public double stallRatio() {
		return baseline.valid() && baseline.headroom(targetFrameMs()) < 1.0D ? 0.25D : 0.12D;
	}

	public long governorDwellMs() {
		return (long) config.number("cpu.governor.minDwellMs", targetFrameMs() * 24.0D);
	}

	// ------------------------------------------------------------------ cpu

	/**
	 * How many performance cores the frame path gets. Scales with what is
	 * actually present instead of assuming a desktop part.
	 */
	public int renderCores() {
		CpuTopology topo = hw.cpu();
		int fast = topo.fastPrimaries().size();
		int derived;

		if (fast <= 2) {
			derived = 1;
		} else if (fast <= 4) {
			derived = 2;
		} else if (fast <= 8) {
			derived = 3;
		} else {
			derived = 4;
		}

		return Math.max(1, Math.min(config.integer("cpu.affinity.renderCores", derived), Math.max(1, fast - 1)));
	}

	// ------------------------------------------------------------------- gc

	/**
	 * Heap fill level that makes a sweep worthwhile. Heavy allocators sweep
	 * earlier because they will reach the wall sooner.
	 */
	public double gcHeapTriggerPercent() {
		double derived = 65.0D;

		if (baseline.valid() && baseline.churnMbPerSec() > 0.0D) {
			double heapMb = hw.heapMaxBytes() / 1048576.0D;
			double secondsToFill = heapMb / Math.max(1.0D, baseline.churnMbPerSec());

			// Under 8 s of runway means collections are frequent; get ahead of them.
			derived = secondsToFill < 8.0D ? 52.0D : secondsToFill < 25.0D ? 62.0D : 72.0D;
		}

		return clamp(config.number("gc.heapTriggerPercent", derived), 30.0D, 92.0D);
	}

	public double gcMinIntervalSeconds() {
		double derived = 25.0D;

		if (baseline.valid() && baseline.churnMbPerSec() > 0.0D) {
			double heapMb = hw.heapMaxBytes() / 1048576.0D;
			derived = clamp(heapMb / Math.max(1.0D, baseline.churnMbPerSec()) * 0.5D, 8.0D, 90.0D);
		}

		return clamp(config.number("gc.minIntervalSeconds", derived), 5.0D, 300.0D);
	}

	// ------------------------------------------------------------------ drs

	/** User floor is authoritative; auto-tuning never dips below it. */
	public double drsMinScale() {
		return clamp(config.fixed("drs.minScale"), 0.40D, 1.0D);
	}

	public double drsMaxScale() {
		return clamp(config.fixed("drs.maxScale"), drsMinScale(), 1.0D);
	}

	/**
	 * Step size in scale units. Large framebuffers can afford finer steps because
	 * each step still frees a lot of pixels.
	 */
	public double drsStep() {
		long px = hw.display().pixels();
		double derived = px >= 7_000_000L ? 0.025D : px >= 3_500_000L ? 0.04D : 0.06D;
		return clamp(config.number("drs.step", derived), 0.01D, 0.25D);
	}

	/**
	 * VRAM fill level that starts pulling resolution down. Small cards react
	 * earlier because the fall off a full card is much steeper.
	 */
	public double drsVramHighPercent() {
		GpuInfo gpu = hw.gpu();
		double derived = gpu.tightVram() ? 82.0D : gpu.roomyVram() ? 92.0D : 88.0D;
		return clamp(config.number("drs.vramHighPercent", derived), 50.0D, 99.0D);
	}

	// ----------------------------------------------------------------- vram

	public double vramEvictPercent() {
		GpuInfo gpu = hw.gpu();
		double derived = gpu.tightVram() ? 85.0D : gpu.roomyVram() ? 94.0D : 90.0D;
		return clamp(config.number("vram.evictAtPercent", derived), 55.0D, 99.0D);
	}

	public double vramReleaseTargetPercent() {
		double margin = hw.gpu().tightVram() ? 10.0D : 6.0D;
		return clamp(config.number("vram.releaseTargetPercent", vramEvictPercent() - margin),
				40.0D, vramEvictPercent());
	}

	public double vramTextureIdleSeconds() {
		double derived = hw.gpu().tightVram() ? 25.0D : 60.0D;
		return clamp(config.number("vram.textureIdleSeconds", derived), 5.0D, 600.0D);
	}

	// --------------------------------------------------------------- culling

	/**
	 * Minimum on-screen size worth drawing, in framebuffer pixels.
	 *
	 * The test runs in physical pixels, so a HiDPI panel needs a proportionally
	 * larger figure to represent the same perceived size. Anything below one
	 * physical pixel cannot alter the image.
	 */
	public double subPixelThreshold() {
		DisplayInfo d = hw.display();
		double derived = Math.max(1.0D, d.contentScale());
		return clamp(config.number("cull.subpixel.minPixels", derived), 0.25D, 8.0D);
	}

	/** Never cull anything nearer than this, in blocks. */
	public double subPixelMinDistance() {
		double derived = Math.max(16.0D, hw.renderDistanceChunks() * 16.0D * 0.25D);
		return clamp(config.number("cull.subpixel.minDistance", derived), 4.0D, 512.0D);
	}

	// ---------------------------------------------------------------- chunks

	/**
	 * How hard sections outside the sight cone are pushed back. More worker
	 * threads and shorter view distances mean the queue drains fast enough that a
	 * gentler penalty already keeps the cone ahead.
	 */
	public double conePenalty() {
		int workers = Math.max(1, hw.cpu().backgroundPool().size());
		int rd = hw.renderDistanceChunks();
		double derived = rd >= 24 ? 8.0D : rd >= 16 ? 6.0D : 4.0D;
		derived /= workers >= 8 ? 1.6D : workers >= 4 ? 1.2D : 1.0D;
		return clamp(config.number("chunk.cone.behindPenalty", derived), 1.0D, 20.0D);
	}

	public double coneDegrees() {
		return clamp(config.fixed("chunk.cone.degrees"), 15.0D, 360.0D);
	}

	/** Frame time above which work behind the player is postponed. */
	public double coneDeferFrameMs() {
		return stallMs();
	}

	// ----------------------------------------------------------------- misc

	public String describe() {
		return String.format(
				"target %.2fms stall %.2fms | drs %.2f-%.2f step %.3f | vram evict %.0f%% | subpixel %.2fpx | cone %.1fx",
				targetFrameMs(), stallMs(), drsMinScale(), drsMaxScale(), drsStep(),
				vramEvictPercent(), subPixelThreshold(), conePenalty());
	}

	private static double clamp(double v, double lo, double hi) {
		return v < lo ? lo : Math.min(v, hi);
	}
}
