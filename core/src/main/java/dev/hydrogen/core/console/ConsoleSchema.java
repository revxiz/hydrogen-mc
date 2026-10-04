package dev.hydrogen.core.console;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a browser may change. The Worker checks the same two lists before it
 * queues anything; the game checks again before acting, so a compromised or
 * buggy server still cannot reach any other setting.
 */
public final class ConsoleSchema {
	/** Config switches a browser may flip for this session. */
	public static final List<String> FEATURES = List.of(
			"enabled",
			"drs.enabled",
			"cpu.governor.enabled",
			"gc.enabled",
			"vram.enabled",
			"cull.subpixel.enabled",
			"cull.blockEntities.enabled",
			"chunk.cone.enabled",
			"audio.enabled",
			"particle.cullPhysics",
			"ai.throttle.enabled",
			"hopper.throttle.enabled");

	public static final Set<String> ACTIONS = Set.of("gc", "recalibrate", "resetScale", "consoleOff");

	private static final Set<String> FEATURE_SET = Set.copyOf(FEATURES);

	private ConsoleSchema() {
	}

	/** A command the game is willing to run. */
	public record Command(String id, String key, Boolean value, String action) {
		public boolean toggle() {
			return key != null;
		}
	}

	/** Null for anything not on the lists, including a well-formed command with a value of the wrong type. */
	public static Command parse(Object raw) {
		if (!(raw instanceof Map<?, ?> m)) {
			return null;
		}

		Object id = m.get("id");

		if (!(id instanceof String s) || !s.matches("[A-Za-z0-9_-]{1,32}")) {
			return null;
		}

		Object type = m.get("type");

		if ("toggle".equals(type) && m.get("key") instanceof String key && FEATURE_SET.contains(key)
				&& m.get("value") instanceof Boolean value) {
			return new Command(s, key, value, null);
		}

		if ("action".equals(type) && m.get("name") instanceof String name && ACTIONS.contains(name)) {
			return new Command(s, null, null, name);
		}

		return null;
	}
}
