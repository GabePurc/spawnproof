package io.github.gabepurc.spawnproof.fabric;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Which tests run ({@code spawnproof.tests}, {@code spawnproof.skip}): by name, tag, mod id or class name, with {@code *}
 * for anything. With nothing picked, every test that runs by default does. A test that doesn't run by default runs only
 * when named, or picked by a tag: not by its mod's id or a pattern.
 */
record Selection(List<String> include, List<String> exclude) {
	boolean picks(Candidate c) {
		if (matchesAny(exclude, c)) return false;
		if (include.isEmpty()) return c.runByDefault();
		for (String pattern : include) {
			if (names(pattern, c) || c.runByDefault() && matches(pattern, c)) return true;
		}
		return false;
	}

	/** Whether the pattern is this test's name, one of its tags, or its class's name, exactly. */
	private static boolean names(String pattern, Candidate c) {
		if (pattern.contains("*")) return false;
		String simple = c.className().substring(c.className().lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
		return pattern.equals(c.name()) || c.tags().contains(pattern) || pattern.equals(simple)
			|| pattern.equals(c.className().toLowerCase(Locale.ROOT));
	}

	/** The names in {@code spawnproof.tests} that pick no test at all: a typo would otherwise run nothing, and pass. */
	List<String> unmatched(List<Candidate> all) {
		List<String> out = new ArrayList<>();
		for (String pattern : include) {
			if (all.stream().noneMatch(c -> matches(pattern, c))) out.add(pattern);
		}
		return out;
	}

	private static boolean matchesAny(List<String> patterns, Candidate c) {
		for (String p : patterns) if (matches(p, c)) return true;
		return false;
	}

	static boolean matches(String pattern, Candidate c) {
		Pattern glob = glob(pattern);
		if (glob.matcher(c.name()).matches() || glob.matcher(c.mod()).matches()) return true;
		for (String tag : c.tags()) if (glob.matcher(tag).matches()) return true;
		String simple = c.className().substring(c.className().lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
		return glob.matcher(simple).matches() || glob.matcher(c.className().toLowerCase(Locale.ROOT)).matches();
	}

	private static Pattern glob(String pattern) {
		StringBuilder regex = new StringBuilder();
		for (String part : pattern.toLowerCase(Locale.ROOT).split("\\*", -1)) {
			if (!regex.isEmpty()) regex.append(".*");
			regex.append(Pattern.quote(part));
		}
		return Pattern.compile(regex.toString());
	}
}
