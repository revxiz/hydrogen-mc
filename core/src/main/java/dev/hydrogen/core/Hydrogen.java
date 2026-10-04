package dev.hydrogen.core;

import dev.hydrogen.core.audio.SoundGate;
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
import dev.hydrogen.core.hw.Tuning;
import dev.hydrogen.core.platform.NativePlatform;
import dev.hydrogen.core.sim.AiThrottle;
import dev.hydrogen.core.sim.HopperThrottle;
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
	private final Path stateFile;
	private final boolean dedicatedServer;
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
	private final SoundGate soundGate;
	private final AiThrottle aiThrottle;
	private final HopperThrottle hopperThrottle;

	private volatile VramSnapshot vram = VramSnapshot.UNKNOWN;
	private volatile FrameStats stats = FrameStats.EMPTY;
	private volatile EvictionController.Action pendingEviction = EvictionController.Action.NONE;

	private long lastControlMs;
	private volatile long lastFrameNanos;
	private double appliedScale = 1.0D;

	private Hydrogen(Path configFile, NativePlatform platform, boolean dedicatedServer) {
		this.config = new HConfig(configFile);
		this.platform = platform;
		this.dedicatedServer = dedicatedServer;
		this.stateFile = configFile == null ? null : configFile.resolveSibling("hydrogen-restore.state");
		this.budget = new Budget(config, hardware);
		this.calibrator = new Calibrator(config, budget);
		this.binder = new CoreBinder(platform, config, budget);
		this.governor = new FrequencyGovernor(platform, config, budget);
		this.gc = new GcCoordinator(config, budget);
		this.resolution = new ResolutionController(config, budget);
		this.eviction = new EvictionController(config, budget);
		this.culler = new SubPixelCuller(budget);
		this.cone = new ConePriority(budget);
		this.soundGate = new SoundGate(budget);
		this.aiThrottle = new AiThrottle(budget);
		this.hopperThrottle = new HopperThrottle(budget);
	}

	/**
	 * @param configFile      config/hydrogen.properties
	 * @param platform        native layer for this OS
	 * @param dedicatedServer true when no client will ever exist in this process
	 */
	public static Hydrogen boot(Path configFile, NativePlatform platform, boolean dedicatedServer) {
		Hydrogen h = instance;

		if (h == null) {
			synchronized (Hydrogen.class) {
				h = instance;

				if (h == null) {
					h = new Hydrogen(configFile, platform, dedicatedServer);
					h.start();
					instance = h;
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
		budget.refresh();

		// A previous session killed while boosted left the power plan or governor
		// changed. Put it back before anything else touches it.
		if (stateFile != null) {
			platform.recoverState(stateFile);
		}

		binder.buildPlan(dedicatedServer);

		// When native calls are missing, the platform layer has already said why.
		HLog.LOG.info("Hydrogen on {} | {}", platform.name(), hardware.cpu().describe());

		if (config.bool("cpu.priority.native")) {
			platform.setProcessPriority(NativePlatform.PRIORITY_HIGH);
		}

		if (config.bool("cpu.governor.enabled") && platform.disablePowerThrottling() && config.bool("log.verbose")) {
			HLog.LOG.info("Hydrogen: opted out of OS power throttling for this process");
		}

		gc.install();
	}

	public boolean enabled() {
		return budget.tuning().enabled();
	}

	/** Hook snapshot. Hot paths read this instead of the config. */
	public Tuning tuning() {
		return budget.tuning();
	}

	/** Called after the GL context exists and the window is known. */
	public void onGraphicsReady(DisplayInfo display, GpuInfo gpu) {
		hardware.setDisplay(display);
		hardware.setGpu(gpu);
		compat.setBackend(gpu.backend());
		compat.setGpu(gpu.vendor(), gpu.renderer());
		culler.updateProjection(display, resolution.scale(), hardware.fovDegrees());
		binder.buildPlan(dedicatedServer);
		budget.refresh();
		HLog.LOG.info("Hydrogen: {} | {}", gpu.describe(), display.describe());
		HLog.LOG.info("Hydrogen: {} | gc {}", budget.describe(), gc.collector());
	}

	/**
	 * @param display  freshly probed display
	 * @param geometry the framebuffer size or refresh rate changed, as opposed to
	 *                 only the frame limiter or vsync setting
	 */
	public void onDisplayChanged(DisplayInfo display, boolean geometry) {
		hardware.setDisplay(display);
		culler.updateProjection(display, resolution.scale(), hardware.fovDegrees());
		budget.refresh();

		if (geometry) {
			calibrator.onResize(System.currentTimeMillis());
		}
	}

	public void onWorldJoin() {
		resolution.reset();
		timeline.reset();
		eviction.clearCap();
		calibrator.request(System.currentTimeMillis());
	}

	public void onWorldLeave() {
		calibrator.abort();
		resolution.reset();
		eviction.clearCap();
		governor.release(System.currentTimeMillis());
	}

	/**
	 * Called at the end of every rendered frame from the render thread.
	 *
	 * @param frameNanos wall time the frame took
	 * @param inWorld    a level is loaded; menus alone never boost clocks or scale anything
	 * @param screenOpen a screen covers the world, so calibration should not sample
	 */
	public void onFrameEnd(long frameNanos, boolean inWorld, boolean screenOpen) {
		lastFrameNanos = frameNanos;
		timeline.push(frameNanos);

		long now = System.currentTimeMillis();

		if (calibrator.active()) {
			// Hold every adaptive feature still so the baseline is honest.
			calibrator.onFrame(frameNanos, now, gc.heapUsedBytes(), vram.freeKb(), !inWorld || screenOpen);
			return;
		}

		if (!inWorld) {
			return;
		}

		// Controllers run at roughly 20 Hz; per-frame cost stays a ring buffer write.
		if (now - lastControlMs < 50L) {
			return;
		}

		lastControlMs = now;
		budget.refresh();
		stats = timeline.snapshot(budget.stallMs());

		governor.update(stats, now);
		resolution.update(stats, vram, now);

		if (Math.abs(resolution.scale() - appliedScale) > 1.0E-4D) {
			appliedScale = resolution.scale();
			culler.updateProjection(hardware.display(), appliedScale, hardware.fovDegrees());

			if (config.bool("log.verbose")) {
				HLog.LOG.info("Hydrogen: world scale {}% ({})", Math.round(appliedScale * 100.0D), resolution.reason());
			}
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

		if (role == (dedicatedServer ? ThreadRole.SERVER : ThreadRole.RENDER)) {
			binder.startSweeper();
		}
	}

	public void setVram(VramSnapshot snapshot) {
		this.vram = snapshot == null ? VramSnapshot.UNKNOWN : snapshot;
	}

	public void shutdown() {
		binder.stopSweeper();
		governor.shutdown();
		gc.shutdown();
		platform.setProcessPriority(NativePlatform.PRIORITY_NORMAL);
		HLog.LOG.info("Hydrogen stopped: {} clock switches, {} DRS downshifts, {} GC requests, {} textures released",
				governor.switchCount(), resolution.downshifts(), gc.stats().scheduledSweeps(),
				eviction.releasedTextures());
	}

	public boolean dedicatedServer() {
		return dedicatedServer;
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

	public SoundGate soundGate() {
		return soundGate;
	}

	public AiThrottle aiThrottle() {
		return aiThrottle;
	}

	public HopperThrottle hopperThrottle() {
		return hopperThrottle;
	}

	public VramSnapshot vram() {
		return vram;
	}
}
