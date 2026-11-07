package io.github.gabepurc.spawnproof;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonTest {
	@Test
	void roundTrips() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("text", "a \"quoted\"\nline\t\\ ü \u0001");
		m.put("n", 42L);
		m.put("negative", -7L);
		m.put("yes", true);
		m.put("none", null);
		m.put("list", Arrays.asList(1L, "two", null, List.of()));
		m.put("nested", Map.of("k", "v"));
		assertEquals(m, Json.parse(Json.write(m)));
	}

	@Test
	void readsWhitespaceAndNumbers() {
		assertEquals(Map.of("a", List.of(1L, 2.5, -3L)), Json.parse(" { \"a\" : [ 1 , 2.5, -3 ] } "));
	}

	@Test
	void refusesBrokenText() {
		assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":"));
		assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1} x"));
	}
}
