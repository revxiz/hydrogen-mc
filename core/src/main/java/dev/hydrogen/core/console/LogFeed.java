package dev.hydrogen.core.console;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The last warnings and errors the game logged, numbered so the console can
 * ask for "everything after line n" after a reconnect.
 *
 * Capturing is a copy into a ring: scrubbing waits until a line is actually
 * sent. A mod that floods the log is cut off at {@link #PER_SECOND} lines a
 * second, with one line saying how many were left out.
 */
public final class LogFeed {
	public static final LogFeed INSTANCE = new LogFeed();

	static final int CAPACITY = 200;
	static final int PER_SECOND = 20;
	private static final int MAX_MESSAGE = 600;

	/**
	 * @param own true for Hydrogen's own logger, which is sent even when the
	 *            player turned the rest of the game log off
	 */
	public record Line(long seq, long time, boolean error, String source, String message, boolean own) {
	}

	private final ArrayDeque<Line> lines = new ArrayDeque<>(CAPACITY);
	private long next;
	private long windowStart;
	private int inWindow;
	private int dropped;

	LogFeed() {
	}

	public void add(boolean error, String source, String message, boolean own) {
		add(System.currentTimeMillis(), error, source, message, own);
	}

	synchronized void add(long now, boolean error, String source, String message, boolean own) {
		if (now - windowStart >= 1000L) {
			if (dropped > 0) {
				push(now, false, "hydrogen/console", dropped + " more lines in one second were left out", true);
			}

			windowStart = now;
			inWindow = 0;
			dropped = 0;
		}

		if (++inWindow > PER_SECOND) {
			dropped++;
			return;
		}

		String m = message == null ? "" : message;
		push(now, error, source == null ? "game" : source, m.length() > MAX_MESSAGE ? m.substring(0, MAX_MESSAGE) : m, own);
	}

	private void push(long now, boolean error, String source, String message, boolean own) {
		if (lines.size() == CAPACITY) {
			lines.removeFirst();
		}

		lines.addLast(new Line(next++, now, error, source, message, own));
	}

	/** Lines numbered above {@code seq}, oldest first, at most {@code max} of the newest. */
	public synchronized List<Line> after(long seq, int max) {
		List<Line> out = new ArrayList<>();

		for (Line l : lines) {
			if (l.seq() > seq) {
				out.add(l);
			}
		}

		return out.size() > max ? new ArrayList<>(out.subList(out.size() - max, out.size())) : out;
	}

	/**
	 * Short logger name: the part after the last dot. Minecraft's own loggers
	 * read "minecraft/ClassName", or just "minecraft" where the loader renamed
	 * its classes to numbered ones like class_3176.
	 */
	public static String shortSource(String logger) {
		if (logger == null || logger.isEmpty()) {
			return "game";
		}

		int dot = logger.lastIndexOf('.');
		String last = dot >= 0 && dot < logger.length() - 1 ? logger.substring(dot + 1) : logger;

		if (logger.startsWith("net.minecraft.") || logger.startsWith("com.mojang.")) {
			return last.matches("(class|method|field)_\\d+") ? "minecraft" : "minecraft/" + last;
		}

		return last;
	}
}
