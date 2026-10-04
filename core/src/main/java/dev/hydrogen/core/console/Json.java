package dev.hydrogen.core.console;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON for the console link: maps, lists, strings, numbers,
 * booleans and null. Minecraft ships Gson, but not every loader exposes it to
 * mods the same way, and this keeps core free of game libraries.
 */
public final class Json {
	private static final int MAX_DEPTH = 32;

	private Json() {
	}

	// ----------------------------------------------------------------- writing

	public static String write(Object value) {
		StringBuilder sb = new StringBuilder(256);
		write(sb, value);
		return sb.toString();
	}

	private static void write(StringBuilder sb, Object v) {
		if (v == null) {
			sb.append("null");
		} else if (v instanceof String s) {
			string(sb, s);
		} else if (v instanceof Boolean b) {
			sb.append(b.booleanValue());
		} else if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
			sb.append(((Number) v).longValue());
		} else if (v instanceof Number n) {
			number(sb, n.doubleValue());
		} else if (v instanceof Map<?, ?> m) {
			sb.append('{');
			boolean first = true;

			for (Map.Entry<?, ?> e : m.entrySet()) {
				if (e.getValue() == null) {
					continue;
				}

				if (!first) {
					sb.append(',');
				}

				first = false;
				string(sb, String.valueOf(e.getKey()));
				sb.append(':');
				write(sb, e.getValue());
			}

			sb.append('}');
		} else if (v instanceof Iterable<?> it) {
			sb.append('[');
			boolean first = true;

			for (Object o : it) {
				if (!first) {
					sb.append(',');
				}

				first = false;
				write(sb, o);
			}

			sb.append(']');
		} else {
			string(sb, String.valueOf(v));
		}
	}

	/** Two decimals are all the console ever shows; NaN and infinity become null. */
	private static void number(StringBuilder sb, double d) {
		if (!Double.isFinite(d)) {
			sb.append("null");
			return;
		}

		double r = Math.round(d * 100.0D) / 100.0D;

		if (r == Math.rint(r) && Math.abs(r) < 1.0E15D) {
			sb.append((long) r);
		} else {
			sb.append(r);
		}
	}

	private static void string(StringBuilder sb, String s) {
		sb.append('"');

		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);

			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20 || c == 0x2028 || c == 0x2029) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}

		sb.append('"');
	}

	// ----------------------------------------------------------------- reading

	/** @throws IllegalArgumentException on anything that is not exactly one JSON value */
	public static Object parse(String text) {
		Parser p = new Parser(text);
		p.space();
		Object v = p.value(0);
		p.space();

		if (p.pos != text.length()) {
			throw new IllegalArgumentException("trailing data at " + p.pos);
		}

		return v;
	}

	/** The object at the top of a message, or null when it is anything else. */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> object(String text) {
		try {
			Object v = parse(text);
			return v instanceof Map ? (Map<String, Object>) v : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public static String str(Map<String, Object> m, String key) {
		Object v = m.get(key);
		return v instanceof String s ? s : null;
	}

	public static long num(Map<String, Object> m, String key, long fallback) {
		Object v = m.get(key);
		return v instanceof Number n && Double.isFinite(n.doubleValue()) ? n.longValue() : fallback;
	}

	@SuppressWarnings("unchecked")
	public static List<Object> list(Map<String, Object> m, String key) {
		Object v = m.get(key);
		return v instanceof List ? (List<Object>) v : List.of();
	}

	private static final class Parser {
		private final String s;
		private int pos;

		Parser(String s) {
			this.s = s;
		}

		void space() {
			while (pos < s.length()) {
				char c = s.charAt(pos);

				if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
					break;
				}

				pos++;
			}
		}

		IllegalArgumentException fail(String what) {
			return new IllegalArgumentException(what + " at " + pos);
		}

		Object value(int depth) {
			if (depth > MAX_DEPTH) {
				throw fail("nested too deep");
			}

			if (pos >= s.length()) {
				throw fail("unexpected end");
			}

			char c = s.charAt(pos);

			return switch (c) {
				case '{' -> object(depth);
				case '[' -> array(depth);
				case '"' -> string();
				case 't' -> literal("true", Boolean.TRUE);
				case 'f' -> literal("false", Boolean.FALSE);
				case 'n' -> literal("null", null);
				default -> number();
			};
		}

		Object literal(String word, Object v) {
			if (!s.startsWith(word, pos)) {
				throw fail("bad literal");
			}

			pos += word.length();
			return v;
		}

		Map<String, Object> object(int depth) {
			Map<String, Object> m = new LinkedHashMap<>();
			pos++;
			space();

			if (peek('}')) {
				pos++;
				return m;
			}

			while (true) {
				space();

				if (!peek('"')) {
					throw fail("expected a key");
				}

				String key = string();
				space();
				expect(':');
				space();
				m.put(key, value(depth + 1));
				space();

				if (peek(',')) {
					pos++;
					continue;
				}

				expect('}');
				return m;
			}
		}

		List<Object> array(int depth) {
			List<Object> l = new ArrayList<>();
			pos++;
			space();

			if (peek(']')) {
				pos++;
				return l;
			}

			while (true) {
				space();
				l.add(value(depth + 1));
				space();

				if (peek(',')) {
					pos++;
					continue;
				}

				expect(']');
				return l;
			}
		}

		String string() {
			expect('"');
			StringBuilder sb = new StringBuilder();

			while (true) {
				if (pos >= s.length()) {
					throw fail("unterminated string");
				}

				char c = s.charAt(pos++);

				if (c == '"') {
					return sb.toString();
				}

				if (c != '\\') {
					sb.append(c);
					continue;
				}

				if (pos >= s.length()) {
					throw fail("bad escape");
				}

				char e = s.charAt(pos++);

				switch (e) {
					case '"', '\\', '/' -> sb.append(e);
					case 'b' -> sb.append('\b');
					case 'f' -> sb.append('\f');
					case 'n' -> sb.append('\n');
					case 'r' -> sb.append('\r');
					case 't' -> sb.append('\t');
					case 'u' -> {
						if (pos + 4 > s.length()) {
							throw fail("bad unicode escape");
						}

						try {
							sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
						} catch (NumberFormatException ex) {
							throw fail("bad unicode escape");
						}

						pos += 4;
					}
					default -> throw fail("bad escape");
				}
			}
		}

		Double number() {
			int start = pos;

			while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
				pos++;
			}

			if (start == pos) {
				throw fail("unexpected character");
			}

			try {
				return Double.valueOf(s.substring(start, pos));
			} catch (NumberFormatException e) {
				throw fail("bad number");
			}
		}

		boolean peek(char c) {
			return pos < s.length() && s.charAt(pos) == c;
		}

		void expect(char c) {
			if (!peek(c)) {
				throw fail("expected '" + c + "'");
			}

			pos++;
		}
	}
}
