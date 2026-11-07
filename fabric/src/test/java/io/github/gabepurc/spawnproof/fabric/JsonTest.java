package io.github.gabepurc.spawnproof.fabric;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonTest {
	@Test
	void writesValues() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", "a \"quoted\"\nline\t\u0001");
		m.put("whole", 5.0);
		m.put("fraction", 0.25);
		m.put("nan", Double.NaN);
		m.put("list", Arrays.asList(1, true, null));
		m.put("empty", Map.of());
		assertEquals("{\"name\":\"a \\\"quoted\\\"\\nline\\t\\u0001\",\"whole\":5,\"fraction\":0.25,\"nan\":null,\"list\":[1,true,null],\"empty\":{}}",
			Json.write(m));
	}

	@Test
	void prettyKeepsShortListsOnOneLine() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("tags", List.of("a", "b"));
		m.put("checks", List.of(Map.of("ok", true)));
		assertEquals("{\n  \"tags\": [\"a\", \"b\"],\n  \"checks\": [\n    {\n      \"ok\": true\n    }\n  ]\n}\n", Json.pretty(m));
	}
}
