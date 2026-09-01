package dev.hydrogen.core.config;

import dev.hydrogen.core.HLog;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Flat key/value config.
 *
 * Almost every tunable defaults to {@code auto}, which means "derive it from the
 * measured hardware". Writing a number pins that value and switches the matching
 * auto-tuner off, so a user can override one threshold without losing the rest.
 */
public final class HConfig {
	public static final String AUTO = "auto";

	private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
	private static final Map<String, String> NOTES = new LinkedHashMap<>();

	static {
		def("enabled", "true", "Master switch.");
		def("hud.overlay", "false", "Draw the Hydrogen metrics overlay.");
		def("log.verbose", "false", "Log every tuning decision.");

		def("calibration.enabled", "true",
				"Run a silent benchmark on world join to learn this machine's baseline.");
		def("calibration.seconds", "5.0", "Length of the sampling window.");
		def("calibration.warmupSeconds", "1.0", "Frames discarded before sampling starts.");
		def("calibration.recalibrateOnResize", "true",
				"Re-run after a resolution or monitor change.");

		def("target.frameTimeMs", AUTO,
				"auto = 1000 / active refresh rate, capped by the in-game frame limiter.");
		def("target.toleranceFactor", AUTO,
				"auto = derived from measured frame jitter. Higher tolerates more variance.");

		def("cpu.affinity.enabled", "true", "Pin threads to topology-aware core sets.");
		def("cpu.affinity.renderCores", AUTO,
				"auto = scales with the number of performance cores found.");
		def("cpu.affinity.pinBackground", "true", "Also pin worldgen and IO pools.");
		def("cpu.priority.native", "true", "Raise OS thread priority when permitted.");
		def("cpu.priority.fallbackToJvm", "true",
				"Use Thread.setPriority when the native call is denied.");

		def("cpu.governor.enabled", "true", "Request peak clocks while frames overrun.");
		def("cpu.governor.allowPowerPlanSwitch", AUTO,
				"auto = allowed on AC power, never on battery.");
		def("cpu.governor.spinHintFallback", "true",
				"Hold a core out of deep sleep when the governor is not writable.");
		def("cpu.governor.minDwellMs", AUTO, "auto = 24 frames at the target frame time.");

		def("gc.enabled", "true", "Pull collections into moments the player will not feel.");
		def("gc.heapTriggerPercent", AUTO, "auto = derived from measured allocation churn.");
		def("gc.minIntervalSeconds", AUTO, "auto = derived from churn and heap size.");
		def("gc.combatLockoutMs", "6000", "Never collect within this long after combat.");
		def("gc.allowOnScreenOpen", "true", "Treat an open inventory as a safe window.");

		def("drs.enabled", "true", "Scale the 3D viewport only. HUD and text stay native.");
		def("drs.minScale", "0.70",
				"Hard floor for downscaling. This one is yours, auto-tuning never goes below it.");
		def("drs.maxScale", "1.0", "Upper bound, normally native.");
		def("drs.step", AUTO, "auto = derived from viewport size.");
		def("drs.vramHighPercent", AUTO, "auto = derived from total VRAM.");
		def("drs.recoverySeconds", "3.0", "Quiet time required before giving resolution back.");
		def("drs.linearUpscale", "true", "Smooth the upscale. False keeps a sharper, blockier look.");
		def("drs.allowNewBlaze3d", "false",
				"Enable viewport scaling on 1.21.9+ and 26.x. Experimental on the new render backend.");

		def("vram.enabled", "true", "Evict GPU textures before the driver runs dry.");
		def("vram.evictAtPercent", AUTO, "auto = tighter on small cards, looser on large ones.");
		def("vram.releaseTargetPercent", AUTO, "auto = evict percent minus a derived margin.");
		def("vram.textureIdleSeconds", AUTO, "auto = derived from VRAM pressure.");
		def("vram.minTextureBytes", "262144", "Ignore textures smaller than this.");
		def("vram.trimRenderDistance", "true", "Temporarily shrink render distance when critical.");

		def("cull.subpixel.enabled", "true", "Drop draw calls smaller than a physical pixel.");
		def("cull.subpixel.minPixels", AUTO, "auto = one physical pixel, adjusted for DPI scale.");
		def("cull.subpixel.minDistance", AUTO, "auto = derived from render distance.");
		def("cull.subpixel.blockEntities", "true", "Apply the same test to block entities.");

		def("chunk.cone.enabled", "true", "Prioritise meshing inside the forward sight cone.");
		def("chunk.cone.degrees", "60", "Width of the forward cone.");
		def("chunk.cone.behindPenalty", AUTO, "auto = derived from core count and render distance.");
		def("chunk.cone.deferBehind", "true", "Delay work behind the player on overrun frames.");

		def("compat.disableDrsOnVulkan", "true",
				"Skip framebuffer scaling when a Vulkan backend is active.");
	}

	private static void def(String k, String v, String note) {
		DEFAULTS.put(k, v);
		NOTES.put(k, note);
	}

	private final Properties props = new Properties();
	private final Path path;

	public HConfig(Path path) {
		this.path = path;
		load();
	}

	private void load() {
		DEFAULTS.forEach(props::setProperty);

		if (path == null || !Files.isRegularFile(path)) {
			save();
			return;
		}

		try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			props.load(r);
		} catch (IOException e) {
			HLog.warnOnce("cfg-load", "Hydrogen: config unreadable, using defaults", e);
		}
	}

	public void save() {
		if (path == null) {
			return;
		}

		try {
			if (path.getParent() != null) {
				Files.createDirectories(path.getParent());
			}

			try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				w.write("# Hydrogen");
				w.newLine();
				w.write("# 'auto' means Hydrogen measures your hardware and picks the value.");
				w.newLine();
				w.write("# Replace any 'auto' with a number to pin it.");
				w.newLine();

				String section = null;

				for (String key : DEFAULTS.keySet()) {
					String head = key.contains(".") ? key.substring(0, key.indexOf('.')) : key;

					if (!head.equals(section)) {
						section = head;
						w.newLine();
					}

					w.write("# " + NOTES.get(key));
					w.newLine();
					w.write(key + "=" + props.getProperty(key, DEFAULTS.get(key)));
					w.newLine();
				}
			}
		} catch (IOException e) {
			HLog.warnOnce("cfg-save", "Hydrogen: could not write config", e);
		}
	}

	public void reload() {
		load();
	}

	public boolean isAuto(String key) {
		return AUTO.equalsIgnoreCase(raw(key).trim());
	}

	public boolean bool(String key) {
		return Boolean.parseBoolean(raw(key).trim());
	}

	/** Tri-state flag: true, false or auto. */
	public boolean boolAuto(String key, boolean derived) {
		return isAuto(key) ? derived : bool(key);
	}

	public int integer(String key, int derived) {
		if (isAuto(key)) {
			return derived;
		}

		try {
			return Integer.parseInt(raw(key).trim());
		} catch (NumberFormatException e) {
			return derived;
		}
	}

	public double number(String key, double derived) {
		if (isAuto(key)) {
			return derived;
		}

		try {
			return Double.parseDouble(raw(key).trim());
		} catch (NumberFormatException e) {
			return derived;
		}
	}

	/** For keys that always carry a literal value. */
	public double fixed(String key) {
		try {
			return Double.parseDouble(raw(key).trim());
		} catch (NumberFormatException e) {
			return Double.parseDouble(DEFAULTS.get(key));
		}
	}

	public void set(String key, String value) {
		props.setProperty(key, value);
	}

	public String raw(String key) {
		String v = props.getProperty(key);
		return v != null ? v : DEFAULTS.getOrDefault(key, "");
	}

	public static Map<String, String> defaults() {
		return DEFAULTS;
	}

	public static String note(String key) {
		return NOTES.getOrDefault(key, "");
	}
}
