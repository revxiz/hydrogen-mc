package dev.hydrogen.core;

import dev.hydrogen.core.chunk.ConePriority;
import dev.hydrogen.core.compat.CompatState;
import dev.hydrogen.core.config.HConfig;
import dev.hydrogen.core.cpu.CoreBinder;
import dev.hydrogen.core.cpu.FrequencyGovernor;
import dev.hydrogen.core.cpu.ThreadRole;
import dev.hydrogen.core.cull.SubPixelCuller;
import dev.hydrogen.core.frame.FrameStats;
import dev.hydrogen.core.frame.FrameTimeline;
import dev.hydrogen.core.gc.GcCoordinator;
import dev.hydrogen.core.gpu.EvictionController;
import dev.hydrogen.core.gpu.ResolutionController;
import dev.hydrogen.core.gpu.VramSnapshot;
import dev.hydrogen.core.hw.Budget;
import dev.hydrogen.core.hw.DisplayInfo;
import dev.hydrogen.core.hw.GpuInfo;
import dev.hydrogen.core.hw.HardwareProfile;
import dev.hydrogen.core.platform.NativePlatform;
import dev.hydrogen.core.tune.Calibrator;

import java.nio.file.Path;

/**
 * Holds every subsystem and the once-per-frame update. Version modules talk to
 * this and nothing else, which keeps the Minecraft-facing layer thin.
 */
public final class Hydrogen {
	private static volatile Hydrogen instance;

	private final HConfig config;
	private final NativePlatform platform;
	private final HardwareProfile hardware = new HardwareProfile();
	private final CompatState compat = new CompatState();
	private final Budget budget;

	private final FrameTimeline timeline = new FrameTimeline();
	private final Calibrator calibrator;
	private final CoreBinder binder;
	private final FrequencyGovernor governor;
	private final GcCoordinator gc;
	private final ResolutionController resolution;
	private final EvictionController eviction;
	private final SubPixelCuller culler;
	private final ConePriority cone;

	private volatile VramSnapshot vram = VramSnapshot.UNKNOWN;
	private volatile FrameStats stats = FrameStats.EMPTY;
	private volatile EvictionController.Action pendingEviction = EvictionController.Action.NONE;

	private long lastControlMs;
	private long lastFrameNanos;
	private double appliedScale = 1.0D;

	private Hydrogen(Path configFile, NativePlatform platform) {
		this.config = new HConfig(configFile);
		this.platform = platform;
		this.budget = new Budget(config, hardware);
		this.calibrator = new Calibrator(config, budget);
		this.binder = new CoreBinder(platform, config, budget);
		this.governor = new FrequencyGovernor(platform, config, budget);
		this.gc = new GcCoordinator(config, budget);
		this.resolution = new ResolutionController(config, budget);
		this.eviction = new EvictionController(config, budget);
		this.culler = new SubPixelCuller(config, budget);
		this.cone = new ConePriority(config, budget);
	}

	public static Hydrogen boot(Path configFile, NativePlatform platform) {
		Hydrogen h = instance;

		if (h == null) {
			synchronized (Hydrogen.class) {
				h = instance;

				if (h == null) {
					h = new Hydrogen(configFile, platform);
					instance = h;
					h.start();
				}
			}
		}

		return h;
	}

	public static Hydrogen get() {
		return instance;
	}

	private void start() {
		hardware.setCpu(platform.topology());
		hardware.refreshHeap();
		binder.buildPlan();

		HLog.LOG.info("Hydrogen on {} | {}", platform.name(), hardware.cpu().describe());

		if (!platform.available()) {
			HLog.once("no-native",
					"Hydrogen: native scheduling calls unavailable, using JVM-level priorities only");
		}

		if (config.bool("cpu.priority.native")) {
			platform.setProcessPriority(NativePlatform.PRIORITY_HIGH);
		}

		gc.install();
	}

	public boolean enabled() {
		return config.bool("enabled");
	}

	/** Called after the GL context exists and the window is known. */
	public void onGraphicsReady(DisplayInfo display, GpuInfo gpu) {
		hardware.setDisplay(display);
		hardware.setGpu(gpu);
		compat.setBackend(gpu.backend());
		compat.setGpu(gpu.vendor(), gpu.renderer());
		culler.updateProjection(display, resolution.scale(), hardware.fovDegrees());
		binder.buildPlan();
		HLog.LOG.info("Hydrogen: {} | {}", gpu.describe(), display.describe());
		HLog.LOG.info("Hydrogen: {}", budget.describe());
	}

	public void onDisplayChanged(DisplayInfo display) {
		hardware.setDisplay(display);
		culler.updateProjection(display, resolution.scale(), hardware.fovDegrees());
		calibrator.onResize(System.currentTimeMillis());
	}

	public void onWorldJoin() {
		resolution.reset();
		timeline.reset();
		calibrator.request(System.currentTimeMillis());
	}

	public void onWorldLeave() {
		calibrator.abort();
		resolution.reset();
	}

	/**
	 * Called at the end of every rendered frame from the render thread.
	 *
	 * @param frameNanos wall time the frame took
	 */
	public void onFrameEnd(long frameNanos) {
		lastFrameNanos = frameNanos;
		timeline.push(frameNanos);

		long now = System.currentTimeMillis();

		if (calibrator.active()) {
			// Hold every adaptive feature still so the baseline is honest.
			calibrator.onFrame(frameNanos, now, gc.heapUsedBytes(), vram.freeKb());
			return;
		}

		// Controllers run at roughly 20 Hz; per-frame cost stays a ring buffer write.
		if (now - lastControlMs < 50L) {
			return;
		}

		lastControlMs = now;
		stats = timeline.snapshot(budget.stallMs());

		governor.update(stats, now);
		resolution.update(stats, vram, now);

		if (Math.abs(resolution.scale() - appliedScale) > 1.0E-4D) {
			appliedScale = resolution.scale();
			culler.updateProjection(hardware.display(), appliedScale, hardware.fovDegrees());
		}

		EvictionController.Action action = eviction.decide(vram, now);

		if (action == EvictionController.Action.HARD) {
			resolution.emergencyDrop(now);
		}

		if (action != EvictionController.Action.NONE) {
			pendingEviction = action;
		}
	}

	/** Consumed by the render module, which owns the GL context. */
	public EvictionController.Action takeEvictionAction() {
		EvictionController.Action a = pendingEviction;
		pendingEviction = EvictionController.Action.NONE;
		return a;
	}

	public void bindCurrentThread(ThreadRole role) {
		binder.bindCurrent(role);
	}

	public void setVram(VramSnapshot snapshot) {
		this.vram = snapshot == null ? VramSnapshot.UNKNOWN : snapshot;
	}

	public void shutdown() {
		governor.shutdown();
		gc.shutdown();
		platform.setProcessPriority(NativePlatform.PRIORITY_NORMAL);
		config.save();
		HLog.LOG.info("Hydrogen stopped: {} clock switches, {} DRS downshifts, {} GC sweeps, {} MB VRAM released",
				governor.switchCount(), resolution.downshifts(), gc.stats().scheduledSweeps(),
				eviction.releasedKb() / 1024L);
	}

	public HConfig config() {
		return config;
	}

	public NativePlatform platform() {
		return platform;
	}

	public HardwareProfile hardware() {
		return hardware;
	}

	public Budget budget() {
		return budget;
	}

	public Calibrator calibrator() {
		return calibrator;
	}

	public CompatState compat() {
		return compat;
	}

	public FrameTimeline timeline() {
		return timeline;
	}

	public FrameStats stats() {
		return stats;
	}

	public double lastFrameMs() {
		return lastFrameNanos / 1_000_000.0D;
	}

	public CoreBinder binder() {
		return binder;
	}

	public FrequencyGovernor governor() {
		return governor;
	}

	public GcCoordinator gc() {
		return gc;
	}

	public ResolutionController resolution() {
		return resolution;
	}

	public EvictionController eviction() {
		return eviction;
	}

	public SubPixelCuller culler() {
		return culler;
	}

	public ConePriority cone() {
		return cone;
	}

	public VramSnapshot vram() {
		return vram;
	}
}
