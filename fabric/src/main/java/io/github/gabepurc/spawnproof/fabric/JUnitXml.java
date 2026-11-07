package io.github.gabepurc.spawnproof.fabric;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * junit.xml, the test report format CI reads (GitHub test reporters, GitLab, Jenkins): a test suite per test, a test case
 * per check. A test that threw, or didn't run, gets a test case of its own saying so.
 */
final class JUnitXml {
	private JUnitXml() {}

	static String of(Results results) {
		StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		int failures = 0;
		int errors = 0;
		int cases = 0;
		StringBuilder suites = new StringBuilder();
		for (TestRecord t : results.tests()) {
			Suite suite = suite(t);
			suites.append(suite.xml());
			failures += suite.failures();
			errors += suite.errors();
			cases += suite.cases();
		}
		out.append("<testsuites name=\"spawnproof\" tests=\"").append(cases).append("\" failures=\"").append(failures).append("\" errors=\"")
			.append(errors).append("\" time=\"").append(seconds(results.durationMs())).append("\">\n");
		out.append(suites).append("</testsuites>\n");
		return out.toString();
	}

	/** One test's suite, with its counts: a test run merges its games' suites into one junit.xml. */
	record Suite(String xml, int cases, int failures, int errors) {
		Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("xml", xml);
			m.put("cases", cases);
			m.put("failures", failures);
			m.put("errors", errors);
			return m;
		}
	}

	static Suite suite(TestRecord t) {
		synchronized (t) {
			StringBuilder suite = new StringBuilder();
			int suiteFailures = 0;
			int suiteErrors = 0;
			int suiteCases = 0;
			String classPrefix = t.test.mod() + "." + t.test.name();
			for (Map<String, Object> c : t.checks) {
				suiteCases++;
				String section = (String) c.get("section");
				suite.append("    <testcase classname=\"").append(attr(section != null ? classPrefix + "." + section : classPrefix))
					.append("\" name=\"").append(attr(String.valueOf(c.get("name")))).append("\" time=\"0\"");
				if (Boolean.TRUE.equals(c.get("ok"))) {
					suite.append("/>\n");
					continue;
				}
				suiteFailures++;
				suite.append(">\n      <failure message=\"").append(attr(failureMessage(c))).append("\">")
					.append(text(c.containsKey("detail") ? String.valueOf(c.get("detail")) : failureMessage(c))).append("</failure>\n    </testcase>\n");
			}
			if (t.error != null) {
				suiteCases++;
				suiteErrors++;
				suite.append("    <testcase classname=\"").append(attr(classPrefix)).append("\" name=\"(the test stopped with an error)\" time=\"0\">\n")
					.append("      <error type=\"").append(attr(String.valueOf(t.error.get("type")))).append("\" message=\"")
					.append(attr(String.valueOf(t.error.get("message")))).append("\">")
					.append(text(String.valueOf(t.error.getOrDefault("stack", t.error.get("message"))))).append("</error>\n    </testcase>\n");
			} else if (t.status.equals(TestRecord.NOT_RUN)) {
				suiteCases++;
				suite.append("    <testcase classname=\"").append(attr(classPrefix)).append("\" name=\"(not run)\" time=\"0\">\n")
					.append("      <skipped message=\"").append(attr(String.valueOf(t.stopReason))).append("\"/>\n    </testcase>\n");
			} else if (t.checks.isEmpty()) {
				// A test with no checks of its own (a plain Fabric test) still shows as one case.
				suiteCases++;
				suite.append("    <testcase classname=\"").append(attr(classPrefix)).append("\" name=\"").append(attr(t.test.name()))
					.append("\" time=\"").append(seconds(t.durationMs())).append("\"/>\n");
			}
			if (t.stopReason != null && !t.status.equals(TestRecord.NOT_RUN) && t.checks.stream().noneMatch(c -> Boolean.TRUE.equals(c.get("required")) && !Boolean.TRUE.equals(c.get("ok")))) {
				suiteCases++;
				suiteFailures++;
				suite.append("    <testcase classname=\"").append(attr(classPrefix)).append("\" name=\"(stopped)\" time=\"0\">\n")
					.append("      <failure message=\"").append(attr(t.stopReason)).append("\"/>\n    </testcase>\n");
			}
			String xml = "  <testsuite name=\"" + attr(t.test.name()) + "\" tests=\"" + suiteCases + "\" failures=\"" + suiteFailures + "\" errors=\""
				+ suiteErrors + "\" skipped=\"" + (t.status.equals(TestRecord.NOT_RUN) ? 1 : 0) + "\" time=\""
				+ seconds(t.status.equals(TestRecord.NOT_RUN) ? 0 : t.durationMs()) + "\">\n" + suite + "  </testsuite>\n";
			return new Suite(xml, suiteCases, suiteFailures, suiteErrors);
		}
	}

	private static String failureMessage(Map<String, Object> c) {
		if (c.containsKey("value")) {
			return String.format(Locale.ROOT, "%s = %.3f, outside %.3f..%.3f", c.get("name"), num(c.get("value")), num(c.get("min")), num(c.get("max")));
		}
		if (c.containsKey("expected")) return "expected " + c.get("expected") + ", got " + c.get("actual");
		return "failed: " + c.get("name");
	}

	private static double num(Object o) {
		return o instanceof Number n ? n.doubleValue() : Double.NaN;
	}

	private static String seconds(long ms) {
		return String.format(Locale.ROOT, "%.3f", ms / 1000.0);
	}

	private static String attr(String s) {
		return text(s).replace("\"", "&quot;").replace("\n", "&#10;");
	}

	private static String text(String s) {
		StringBuilder out = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '&' -> out.append("&amp;");
				case '<' -> out.append("&lt;");
				case '>' -> out.append("&gt;");
				default -> {
					// XML 1.0 has no place for most control characters.
					if (c < 0x20 && c != '\n' && c != '\t' && c != '\r') out.append('?');
					else out.append(c);
				}
			}
		}
		return out.toString();
	}
}
