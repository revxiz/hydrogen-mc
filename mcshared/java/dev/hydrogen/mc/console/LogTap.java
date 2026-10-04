package dev.hydrogen.mc.console;

import dev.hydrogen.core.HLog;
import dev.hydrogen.core.console.LogFeed;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;

/**
 * Copies warnings and errors from the game log into {@link LogFeed}, which
 * keeps the last 200 in memory. Nothing leaves the PC unless the console is
 * on, and lines are scrubbed of personal details when they are sent.
 *
 * Every supported Minecraft logs through log4j 2. If a launcher swapped it out,
 * the tap is skipped and the console still shows Hydrogen's own warnings.
 */
public final class LogTap extends AbstractAppender {
	private static volatile boolean installed;

	// The four-argument constructor is the one every log4j on the supported
	// branches has; Forge 1.20.1 compiles against a release without the newer one.
	@SuppressWarnings("deprecation")
	private LogTap() {
		super("HydrogenConsole", null, null, true);
	}

	public static synchronized void install() {
		if (installed) {
			return;
		}

		try {
			LoggerContext context = (LoggerContext) LogManager.getContext(false);
			Configuration config = context.getConfiguration();
			LogTap tap = new LogTap();
			tap.start();
			config.addAppender(tap);
			config.getRootLogger().addAppender(tap, Level.WARN, null);
			context.updateLoggers();
			installed = true;
		} catch (Throwable t) {
			HLog.once("console-logtap", "Hydrogen console: the game log can't be read here, so only Hydrogen's own warnings are shown");
		}
	}

	@Override
	public void append(LogEvent event) {
		// Runs on whichever thread logged. Never log from here, and never throw.
		try {
			Level level = event.getLevel();

			if (level == null || !level.isMoreSpecificThan(Level.WARN)) {
				return;
			}

			String logger = event.getLoggerName();
			String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
			Throwable thrown = event.getThrown();

			if (thrown != null) {
				message = message + " | " + thrown.getClass().getName() + (thrown.getMessage() == null ? "" : ": " + thrown.getMessage());
			}

			LogFeed.INSTANCE.add(level.isMoreSpecificThan(Level.ERROR), LogFeed.shortSource(logger), message, "Hydrogen".equals(logger));
		} catch (Throwable ignored) {
			// A broken message object must not take the game's logging down with it.
		}
	}
}
