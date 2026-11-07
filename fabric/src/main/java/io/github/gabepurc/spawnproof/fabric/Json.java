package io.github.gabepurc.spawnproof.fabric;

import java.util.Collection;
import java.util.Map;

/** Writes the results: objects, arrays, strings, numbers, booleans and null. */
final class Json {
	private Json() {}

	static String write(Object value) {
		StringBuilder out = new StringBuilder();
		write(out, value, -1, 0);
		return out.toString();
	}

	/** Indented, for results.json, which people open too. */
	static String pretty(Object value) {
		StringBuilder out = new StringBuilder();
		write(out, value, 0, 0);
		return out.append('\n').toString();
	}

	private static void write(StringBuilder out, Object value, int indent, int depth) {
		if (value == null) {
			out.append("null");
		} else if (value instanceof String s) {
			string(out, s);
		} else if (value instanceof Boolean || value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
			out.append(value);
		} else if (value instanceof Number n) {
			double d = n.doubleValue();
			if (!Double.isFinite(d)) out.append("null");
			else if (d == Math.rint(d) && Math.abs(d) < 1e15) out.append((long) d);
			else out.append(d);
		} else if (value instanceof Map<?, ?> map) {
			out.append('{');
			boolean first = true;
			for (Map.Entry<?, ?> e : map.entrySet()) {
				if (!first) out.append(',');
				first = false;
				newline(out, indent, depth + 1);
				string(out, String.valueOf(e.getKey()));
				out.append(indent >= 0 ? ": " : ":");
				write(out, e.getValue(), indent, depth + 1);
			}
			if (!map.isEmpty()) newline(out, indent, depth);
			out.append('}');
		} else if (value instanceof Collection<?> list) {
			out.append('[');
			boolean first = true;
			// Short lists of plain values stay on one line: tags, a check's numbers.
			boolean flat = indent < 0 || list.size() <= 8 && list.stream().allMatch(v -> v == null || v instanceof String s && s.length() < 40 || v instanceof Number || v instanceof Boolean);
			for (Object v : list) {
				if (!first) out.append(flat && indent >= 0 ? ", " : ",");
				first = false;
				if (!flat) newline(out, indent, depth + 1);
				write(out, v, indent, depth + 1);
			}
			if (!flat && !list.isEmpty()) newline(out, indent, depth);
			out.append(']');
		} else {
			string(out, value.toString());
		}
	}

	private static void newline(StringBuilder out, int indent, int depth) {
		if (indent < 0) return;
		out.append('\n');
		out.append("  ".repeat(depth));
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
}
