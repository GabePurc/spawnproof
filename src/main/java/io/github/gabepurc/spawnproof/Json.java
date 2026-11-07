package io.github.gabepurc.spawnproof;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The small JSON the daemon and its clients exchange: objects, arrays, strings, whole numbers, booleans and null. */
public final class Json {
	private Json() {}

	public static String write(Object value) {
		StringBuilder out = new StringBuilder();
		write(out, value);
		return out.toString();
	}

	private static void write(StringBuilder out, Object value) {
		if (value == null) {
			out.append("null");
		} else if (value instanceof String s) {
			string(out, s);
		} else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
			out.append(value);
		} else if (value instanceof Number n) {
			double d = n.doubleValue();
			out.append(Double.isFinite(d) ? Double.toString(d) : "null");
		} else if (value instanceof Map<?, ?> map) {
			out.append('{');
			boolean first = true;
			for (Map.Entry<?, ?> e : map.entrySet()) {
				if (!first) out.append(',');
				first = false;
				string(out, String.valueOf(e.getKey()));
				out.append(':');
				write(out, e.getValue());
			}
			out.append('}');
		} else if (value instanceof Collection<?> list) {
			out.append('[');
			boolean first = true;
			for (Object o : list) {
				if (!first) out.append(',');
				first = false;
				write(out, o);
			}
			out.append(']');
		} else {
			string(out, value.toString());
		}
	}

	private static void string(StringBuilder out, String s) {
		out.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
					else out.append(c);
				}
			}
		}
		out.append('"');
	}

	public static Object parse(String text) {
		Parser p = new Parser(text);
		p.space();
		Object value = p.value();
		p.space();
		if (p.i != text.length()) throw p.error("text after the value");
		return value;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> parseObject(String text) {
		Object value = parse(text);
		if (!(value instanceof Map)) throw new IllegalArgumentException("Not a JSON object: " + text);
		return (Map<String, Object>) value;
	}

	public static String str(Map<String, Object> m, String key) {
		Object v = m.get(key);
		return v == null ? null : v.toString();
	}

	public static long num(Map<String, Object> m, String key, long fallback) {
		return m.get(key) instanceof Number n ? n.longValue() : fallback;
	}

	public static boolean bool(Map<String, Object> m, String key) {
		return Boolean.TRUE.equals(m.get(key));
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> obj(Map<String, Object> m, String key) {
		return m.get(key) instanceof Map ? (Map<String, Object>) m.get(key) : Map.of();
	}

	@SuppressWarnings("unchecked")
	public static List<Map<String, Object>> list(Map<String, Object> m, String key) {
		if (!(m.get(key) instanceof List<?> list)) return List.of();
		List<Map<String, Object>> out = new ArrayList<>();
		for (Object o : list) if (o instanceof Map) out.add((Map<String, Object>) o);
		return out;
	}

	public static List<String> strings(Map<String, Object> m, String key) {
		if (!(m.get(key) instanceof List<?> list)) return List.of();
		List<String> out = new ArrayList<>();
		for (Object o : list) if (o != null) out.add(o.toString());
		return out;
	}

	private static final class Parser {
		final String s;
		int i;

		Parser(String s) {
			this.s = s;
		}

		IllegalArgumentException error(String what) {
			return new IllegalArgumentException("Bad JSON (" + what + ") at " + i + ": " + s);
		}

		void space() {
			while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
		}

		boolean peek(char c) {
			return i < s.length() && s.charAt(i) == c;
		}

		void expect(char c) {
			if (!peek(c)) throw error("expected '" + c + "'");
			i++;
		}

		Object value() {
			if (i >= s.length()) throw error("unexpected end");
			char c = s.charAt(i);
			return switch (c) {
				case '{' -> object();
				case '[' -> array();
				case '"' -> string();
				case 't' -> word("true", Boolean.TRUE);
				case 'f' -> word("false", Boolean.FALSE);
				case 'n' -> word("null", null);
				default -> number();
			};
		}

		Object word(String word, Object value) {
			if (!s.startsWith(word, i)) throw error("unknown word");
			i += word.length();
			return value;
		}

		Map<String, Object> object() {
			Map<String, Object> m = new LinkedHashMap<>();
			i++;
			space();
			if (peek('}')) {
				i++;
				return m;
			}
			while (true) {
				space();
				if (!peek('"')) throw error("expected a key");
				String key = string();
				space();
				expect(':');
				space();
				m.put(key, value());
				space();
				if (peek(',')) {
					i++;
					continue;
				}
				expect('}');
				return m;
			}
		}

		List<Object> array() {
			List<Object> list = new ArrayList<>();
			i++;
			space();
			if (peek(']')) {
				i++;
				return list;
			}
			while (true) {
				space();
				list.add(value());
				space();
				if (peek(',')) {
					i++;
					continue;
				}
				expect(']');
				return list;
			}
		}

		String string() {
			i++;
			StringBuilder out = new StringBuilder();
			while (true) {
				if (i >= s.length()) throw error("unterminated string");
				char c = s.charAt(i++);
				if (c == '"') return out.toString();
				if (c != '\\') {
					out.append(c);
					continue;
				}
				if (i >= s.length()) throw error("bad escape");
				char e = s.charAt(i++);
				switch (e) {
					case 'n' -> out.append('\n');
					case 'r' -> out.append('\r');
					case 't' -> out.append('\t');
					case 'b' -> out.append('\b');
					case 'f' -> out.append('\f');
					case 'u' -> {
						if (i + 4 > s.length()) throw error("bad escape");
						out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
						i += 4;
					}
					default -> out.append(e);
				}
			}
		}

		Object number() {
			int start = i;
			while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
			String n = s.substring(start, i);
			if (n.isEmpty()) throw error("unexpected character");
			try {
				return n.matches("-?\\d+") ? (Object) Long.parseLong(n) : (Object) Double.parseDouble(n);
			} catch (NumberFormatException e) {
				throw error("bad number");
			}
		}
	}
}
