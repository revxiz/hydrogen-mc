package dev.hydrogen.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Facts stamped into the jar at build time: the mod version and the console address. */
public final class BuildInfo {
	private static final Properties PROPS = new Properties();

	static {
		try (InputStream in = BuildInfo.class.getResourceAsStream("/dev/hydrogen/build.properties")) {
			if (in != null) {
				PROPS.load(in);
			}
		} catch (IOException ignored) {
			// Running from unit tests or a hand-built jar: defaults below.
		}
	}

	private BuildInfo() {
	}

	public static String version() {
		String v = PROPS.getProperty("version", "").trim();
		return v.isEmpty() || v.startsWith("$") ? "dev" : v;
	}

	/** The console this build points at, or an empty string when it has none. */
	public static String consoleUrl() {
		String v = PROPS.getProperty("console_url", "").trim();
		return v.startsWith("$") ? "" : v;
	}
}
