package dev.hydrogen.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Shared logger. {@link #once} keeps degradation notices from spamming the log. */
public final class HLog {
	public static final Logger LOG = LoggerFactory.getLogger("Hydrogen");

	private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

	private HLog() {
	}

	public static void once(String key, String message) {
		if (SEEN.add(key)) {
			LOG.info("{}", message);
		}
	}

	public static void warnOnce(String key, String message, Throwable t) {
		if (SEEN.add(key)) {
			LOG.warn("{} ({}: {})", message, t.getClass().getSimpleName(), String.valueOf(t.getMessage()));
		}
	}
}
