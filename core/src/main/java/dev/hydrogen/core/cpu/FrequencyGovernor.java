package dev.hydrogen.core.cpu;

import dev.hydrogen.core.HLog;
import dev.hydrogen.core.config.HConfig;
import dev.hydrogen.core.frame.FrameStats;
import dev.hydrogen.core.hw.Budget;
import dev.hydrogen.core.platform.NativePlatform;

/**
 * Watches frame pacing against the display's own target and asks the OS governor
 * for peak clocks while frames overrun it. A dwell time derived from the target
 * frame time stops the power plan from flapping.
 *
 * A boost that reached the OS is always released through the OS again, even if
 * the switch has since become disallowed (a laptop unplugged mid-session), and
 * unplugging releases it immediately rather than at the next calm period.
 */
public final class FrequencyGovernor {
	private final NativePlatform platform;
	private final HConfig config;
	private final Budget budget;
	private SpinHint spinHint;

	private boolean boosted;
	private boolean planActive;
	private boolean spinActive;
	private long lastSwitchMs;
	private long boostedMs;
	private long boostEnteredMs;
	private int switches;
	private boolean governorReachable = true;
	private boolean usingSpinFallback;

	// Power source changes rarely; asking the OS every control tick meant a sysfs
	// directory walk on Linux twenty times a second on the render thread.
	private boolean batteryCached;
	private long batteryCheckedMs;

	public FrequencyGovernor(NativePlatform platform, HConfig config, Budget budget) {
		this.platform = platform;
		this.config = config;
		this.budget = budget;
	}

	public void update(FrameStats stats, long nowMs) {
		if (!config.bool("cpu.governor.enabled")) {
			if (boosted) {
				apply(false, nowMs);
			}

			return;
		}

		// Unplugged while the power plan was switched: give it back now.
		if (planActive && !planSwitchAllowed()) {
			apply(false, nowMs);
			return;
		}

		if (!stats.usable()) {
			return;
		}

		if (!planSwitchAllowed() && !config.bool("cpu.governor.spinHintFallback")) {
			return;
		}

		double stall = budget.stallMs();
		double release = budget.releaseMs();
		double ratio = budget.stallRatio();

		boolean want = boosted
				? stats.p95Ms() > release || stats.stallRatio() > ratio * 0.5D
				: stats.p95Ms() > stall || stats.stallRatio() > ratio;

		if (want == boosted || nowMs - lastSwitchMs < budget.governorDwellMs()) {
			return;
		}

		apply(want, nowMs);
	}

	/** auto resolves to "yes on AC power, never on battery". */
	private boolean planSwitchAllowed() {
		return config.boolAuto("cpu.governor.allowPowerPlanSwitch", !onBattery());
	}

	private boolean onBattery() {
		long now = System.currentTimeMillis();

		if (batteryCheckedMs == 0L || now - batteryCheckedMs > 10_000L) {
			batteryCheckedMs = now;
			batteryCached = platform.onBattery();
		}

		return batteryCached;
	}

	private void apply(boolean on, long nowMs) {
		if (on == boosted) {
			return;
		}

		if (on) {
			boolean reached = planSwitchAllowed() && platform.requestBoost(true);
			planActive = reached;

			if (!reached && governorReachable && planSwitchAllowed()) {
				governorReachable = false;
				HLog.once("gov-denied",
						"Hydrogen: OS power governor not writable, using a C-state hint thread instead");
			}

			if (!reached && config.bool("cpu.governor.spinHintFallback")) {
				usingSpinFallback = true;
				spinActive = true;
				spin().set(true);
			}
		} else {
			if (planActive) {
				platform.requestBoost(false);
				planActive = false;
			}

			if (spinActive) {
				spin().set(false);
				spinActive = false;
			}
		}

		boosted = on;
		lastSwitchMs = nowMs;
		switches++;

		if (on) {
			boostEnteredMs = nowMs;
		} else if (boostEnteredMs > 0L) {
			boostedMs += nowMs - boostEnteredMs;
			boostEnteredMs = 0L;
		}
	}

	/** Drops any boost straight away, for example when the player leaves the world. */
	public void release(long nowMs) {
		if (boosted) {
			apply(false, nowMs);
		}
	}

	private SpinHint spin() {
		if (spinHint == null) {
			spinHint = new SpinHint(platform);
		}

		return spinHint;
	}

	public void shutdown() {
		if (boosted) {
			apply(false, System.currentTimeMillis());
		}

		if (spinHint != null) {
			spinHint.close();
			spinHint = null;
		}

		platform.restore();
	}

	public boolean boosted() {
		return boosted;
	}

	public int switchCount() {
		return switches;
	}

	public long boostedMillis(long nowMs) {
		return boostedMs + (boosted && boostEnteredMs > 0L ? nowMs - boostEnteredMs : 0L);
	}

	public String mode() {
		if (!config.bool("cpu.governor.enabled")) {
			return "off";
		}

		if (governorReachable && planSwitchAllowed()) {
			return "governor";
		}

		return usingSpinFallback ? "c-state hint" : "observe";
	}
}
