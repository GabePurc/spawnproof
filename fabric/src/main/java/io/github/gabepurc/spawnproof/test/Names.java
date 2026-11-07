package io.github.gabepurc.spawnproof.test;

import java.util.Locale;

/** A test's name from its class: HatTest is "hat", RideFeelTest is "ride-feel", ServerRideTests is "server-ride". */
public final class Names {
	private Names() {}

	public static String of(Class<?> type) {
		String name = type.getSimpleName();
		return of(name.isEmpty() ? type.getName().substring(type.getName().lastIndexOf('.') + 1) : name);
	}

	/** From a class's simple name. */
	public static String of(String simpleClassName) {
		String name = simpleClassName;
		for (String suffix : new String[] {"Tests", "Test"}) {
			if (name.endsWith(suffix) && name.length() > suffix.length()) {
				name = name.substring(0, name.length() - suffix.length());
				break;
			}
		}
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			boolean wordStart = Character.isUpperCase(c) && i > 0
				&& (Character.isLowerCase(name.charAt(i - 1)) || i + 1 < name.length() && Character.isLowerCase(name.charAt(i + 1)));
			if (wordStart) out.append('-');
			out.append(c == '$' || c == '_' ? '-' : c);
		}
		return out.toString().toLowerCase(Locale.ROOT);
	}
}
