package dev.hydrogen.core.console;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * The processor's marketing name where the OS hands it over without running
 * anything: /proc/cpuinfo on Linux, an environment variable on Windows. Null
 * elsewhere; the core and thread counts are shown either way.
 */
final class CpuName {
	private static volatile String cached;
	private static volatile boolean read;

	private CpuName() {
	}

	static String read() {
		if (!read) {
			cached = probe();
			read = true;
		}

		return cached;
	}

	private static String probe() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

		try {
			if (os.contains("linux")) {
				Path info = Path.of("/proc/cpuinfo");

				if (Files.isReadable(info)) {
					List<String> lines = Files.readAllLines(info, StandardCharsets.UTF_8);

					for (String line : lines) {
						if (line.startsWith("model name") && line.indexOf(':') > 0) {
							return clean(line.substring(line.indexOf(':') + 1));
						}
					}
				}
			} else if (os.contains("windows")) {
				// "AMD64 Family 25 Model 97 Stepping 2, AuthenticAMD": not the brand, but the family.
				return clean(System.getenv("PROCESSOR_IDENTIFIER"));
			}
		} catch (IOException | RuntimeException ignored) {
			// Fall through; the counts alone are fine.
		}

		return null;
	}

	private static String clean(String s) {
		if (s == null) {
			return null;
		}

		String t = s.replaceAll("\\s+", " ").trim();
		return t.isEmpty() ? null : t.length() > 120 ? t.substring(0, 120) : t;
	}
}
