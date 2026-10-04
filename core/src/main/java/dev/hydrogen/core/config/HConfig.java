package dev.hydrogen.core.config;

import dev.hydrogen.core.HLog;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flat key/value config.
 *
 * Almost every tunable defaults to {@code auto}, which means "derive it from the
 * measured hardware". Writing a number pins that value and switches the matching
 * auto-tuner off, so a user can override one threshold without losing the rest.
 *
 * The file is only written when it is missing or lacks keys added by a newer
 * version, and the user's own values are always carried over. Hydrogen never
 * persists a change it makes at runtime: those live in {@link #override} and are
 * forgotten on exit, so one bad session cannot switch a feature off for good.
 */
public final class HConfig {
	public static final String AUTO = "auto";

	private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
	private static final Map<String, String> NOTES = new LinkedHashMap<>();

	static {
		def("enabled", "true", "Master switch.");
		def("log.verbose", "false", "Log every tuning decision.");

		def("calibration.enabled", "true",
				"Run a silent benchmark on world join to learn this machine's baseline.");
		def("calibration.seconds", "5.0", "Length of the sampling window.");
		def("calibration.warmupSeconds", "2.0",
				"Frames discarded after the world appears, while the first chunks are still meshing.");
		def("calibration.recalibrateOnResize", "true",
				"Re-run after a resolution or monitor change.");

		def("target.frameTimeMs", AUTO,
				"auto = 1000 / active refresh rate, capped by the in-game frame limiter.");
		def("target.toleranceFactor", AUTO,
				"auto = derived from measured frame jitter. Higher tolerates more variance.");

		def("cpu.affinity.enabled", "true", "Pin threads to topology-aware core sets.");
		def("cpu.affinity.renderCores", AUTO,
				"auto = scales with the number of performance cores found.");
		def("cpu.affinity.pinBackground", "true",
				"Also move worker, IO and other mods' threads off the render cores.");
		def("cpu.priority.native", "true", "Raise OS thread priority when permitted.");
		def("cpu.priority.fallbackToJvm", "true",
				"Use Thread.setPriority when the native call is denied.");

		def("cpu.governor.enabled", "true", "Request peak clocks while frames overrun.");
		def("cpu.governor.allowPowerPlanSwitch", AUTO,
				"auto = allowed on AC power, never on battery. Changes are undone on exit and after a crash.");
		def("cpu.governor.spinHintFallback", "true",
				"Hold a core out of deep sleep when the governor is not writable.");
		def("cpu.governor.minDwellMs", AUTO, "auto = 24 frames at the target frame time.");

		def("gc.enabled", "true", "Pull collections into moments the player will not feel.");
		def("gc.heapTriggerPercent", AUTO, "auto = derived from measured allocation churn.");
		def("gc.minIntervalSeconds", AUTO, "auto = derived from churn and heap size.");
		def("gc.combatLockoutMs", "6000", "Never collect within this long after combat.");
		def("gc.allowOnScreenOpen", "true", "Treat an open inventory as a safe window.");
		def("gc.allowWhileIdle", AUTO,
				"auto = only when the collector runs concurrently (ZGC, Shenandoah, or G1 with ExplicitGCInvokesConcurrent).");

		def("drs.enabled", "true", "Scale the 3D viewport only. HUD and text stay native.");
		def("drs.minScale", "0.70",
				"Hard floor for downscaling. This one is yours, auto-tuning never goes below it.");
		def("drs.maxScale", "1.0", "Upper bound, normally native.");
		def("drs.step", AUTO, "auto = derived from viewport size.");
		def("drs.vramHighPercent", AUTO, "auto = derived from total VRAM.");
		def("drs.recoverySeconds", "3.0", "Quiet time required before giving resolution back.");
		def("drs.linearUpscale", "true", "Smooth the upscale. False keeps a sharper, blockier look.");
		def("drs.allowNewBlaze3d", "false",
				"Enable viewport scaling on 1.21.9+ and 26.x, where the render backend is newer.");

		def("vram.enabled", "true", "Evict GPU textures before the driver runs dry.");
		def("vram.evictAtPercent", AUTO, "auto = tighter on small cards, looser on large ones.");
		def("vram.releaseTargetPercent", AUTO, "auto = evict percent minus a derived margin.");
		def("vram.textureIdleSeconds", AUTO,
				"Only textures nobody has drawn for this long are released. auto = derived from VRAM size.");
		def("vram.trimRenderDistance", "true",
				"Temporarily cap render distance when critical. Your saved setting is never changed.");

		def("cull.subpixel.enabled", "true", "Drop draw calls smaller than a physical pixel.");
		def("cull.subpixel.minPixels", AUTO, "auto = one physical pixel, adjusted for DPI scale.");
		def("cull.subpixel.minDistance", AUTO, "auto = derived from render distance.");

		def("chunk.cone.enabled", "true", "Prioritise meshing inside the forward sight cone.");
		def("chunk.cone.degrees", "60", "Width of the forward cone.");
		def("chunk.cone.behindPenalty", AUTO, "auto = derived from core count and render distance.");
		def("chunk.cone.deferBehind", "true", "Delay work behind the player on overrun frames.");

		def("compat.disableDrsOnVulkan", "true",
				"Skip framebuffer scaling when a Vulkan backend is active.");

		// Audio: priority and distance culling for the OpenAL channel pool.
		def("audio.enabled", "true",
				"Protect gameplay sounds when the sound pool saturates.");
		def("audio.poolSize", "247", "Concurrent channels the engine allows.");
		def("audio.pressureAt", "0.75",
				"Fraction of the pool in use before culling starts. Nothing is culled below this.");
		def("audio.cullDistance", AUTO, "auto = derived from render distance.");

		// Rendering: block entity and particle culling.
		def("cull.blockEntities.enabled", "true",
				"Apply the sub-pixel size test to block entity renderers too.");
		def("particle.cullPhysics", "true",
				"Skip the collision sweep for particles the camera cannot see. They still move and age.");
		def("particle.cullBehindOnly", "true",
				"Only cull particles behind the camera. False also culls distant off-screen ones.");
		def("particle.minCullDistance", AUTO, "auto = derived from render distance.");

		// Simulation. Both change behaviour slightly, so both are opt-in.
		def("ai.throttle.enabled", "false",
				"Run passive mob AI one tick in N when no player is near. Changes mob behaviour.");
		def("ai.throttle.distance", "48", "Player distance beyond which passive AI is thinned.");
		def("ai.throttle.interval", "4", "Run AI one tick in this many. 1 would be no throttling.");

		def("hopper.throttle.enabled", "false",
				"Thin the item-entity scan of empty hoppers. Pulling from containers is never delayed.");
		def("hopper.throttle.interval", "4", "Scan one tick in this many while idle.");

		// Remote console. Off until the player turns it on with Alt+H in game.
		def("console.enabled", "false",
				"Watch this game from a browser. Alt+H in game turns it on and shows a one-time pairing code.");
		def("console.url", AUTO, "auto = the console this build was made for. Only https addresses are accepted.");
		def("console.gameLog", "true",
				"Also send warnings from Minecraft and other mods, not only Hydrogen's own. Personal details are removed first.");
	}

	private static void def(String k, String v, String note) {
		DEFAULTS.put(k, v);
		NOTES.put(k, note);
	}

	private final Properties props = new Properties();
	private final Map<String, String> overrides = new ConcurrentHashMap<>();
	private final Path path;

	public HConfig(Path path) {
		this.path = path;
		load();
	}

	private void load() {
		props.clear();
		DEFAULTS.forEach(props::setProperty);

		if (path == null) {
			return;
		}

		if (!Files.isRegularFile(path)) {
			save();
			return;
		}

		Properties file = new Properties();

		try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			file.load(r);
		} catch (IOException | IllegalArgumentException e) {
			HLog.warnOnce("cfg-load", "Hydrogen: config unreadable, using defaults", e);
			return;
		}

		boolean missing = false;

		for (String key : DEFAULTS.keySet()) {
			String v = file.getProperty(key);

			if (v == null) {
				missing = true;
			} else {
				props.setProperty(key, stripInlineComment(v));
			}
		}

		// Only rewrite when a newer version added keys, and keep every user value.
		if (missing) {
			save();
		}
	}

	/**
	 * java.util.Properties only treats '#' as a comment at the start of a line, so
	 * {@code drs.minScale=0.70  # floor} would otherwise parse as an invalid number
	 * and {@code gc.enabled=true  # on} as false.
	 */
	static String stripInlineComment(String value) {
		for (int i = 1; i < value.length(); i++) {
			char c = value.charAt(i);

			if ((c == '#' || c == '!') && Character.isWhitespace(value.charAt(i - 1))) {
				return value.substring(0, i).trim();
			}
		}

		return value.trim();
	}

	/** Writes every known key, keeping current values. Written atomically. */
	public void save() {
		if (path == null) {
			return;
		}

		try {
			if (path.getParent() != null) {
				Files.createDirectories(path.getParent());
			}

			Path tmp = path.resolveSibling(path.getFileName() + ".tmp");

			try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
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

			try {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException e) {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			HLog.warnOnce("cfg-save", "Hydrogen: could not write config", e);
		}
	}

	public void reload() {
		load();
	}

	public boolean isAuto(String key) {
		return AUTO.equalsIgnoreCase(raw(key));
	}

	public boolean bool(String key) {
		return Boolean.parseBoolean(raw(key));
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
			return Integer.parseInt(raw(key));
		} catch (NumberFormatException e) {
			return derived;
		}
	}

	public double number(String key, double derived) {
		if (isAuto(key)) {
			return derived;
		}

		try {
			double v = Double.parseDouble(raw(key));
			return Double.isFinite(v) ? v : derived;
		} catch (NumberFormatException e) {
			return derived;
		}
	}

	/** For keys that always carry a literal value. */
	public double fixed(String key) {
		try {
			double v = Double.parseDouble(raw(key));

			if (Double.isFinite(v)) {
				return v;
			}
		} catch (NumberFormatException ignored) {
			// Fall through to the shipped default.
		}

		return Double.parseDouble(DEFAULTS.get(key));
	}

	/** Persisted value, written on the next save. */
	public void set(String key, String value) {
		props.setProperty(key, value);
	}

	/**
	 * Session-only value that wins over the file and is never saved. Used when a
	 * feature disables itself after a failure.
	 */
	public void override(String key, String value) {
		overrides.put(key, value);
	}

	public String raw(String key) {
		String o = overrides.get(key);

		if (o != null) {
			return o;
		}

		String v = props.getProperty(key);
		return (v != null ? v : DEFAULTS.getOrDefault(key, "")).trim();
	}

	public static Map<String, String> defaults() {
		return Collections.unmodifiableMap(DEFAULTS);
	}

	public static String note(String key) {
		return NOTES.getOrDefault(key, "");
	}
}
