package io.github.gabepurc.spawnproof.fabric;

import io.github.gabepurc.spawnproof.test.Names;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectionTest {
	private static final Candidate HAT = test("hat", Set.of("cosmetics"), true, "dev.horsingaround.gametest.HatTest");
	private static final Candidate RIDE = test("ride-feel", Set.of(), true, "dev.horsingaround.gametest.RideFeelTest");
	private static final Candidate PERF = test("perf", Set.of("slow"), false, "dev.horsingaround.gametest.PerfTest");
	private static final List<Candidate> ALL = List.of(HAT, RIDE, PERF);

	private static Candidate test(String name, Set<String> tags, boolean byDefault, String className) {
		return new Candidate(name, tags, byDefault, "horsingaround-gametest", className, Candidate.Kind.SPAWNPROOF, null);
	}

	private static List<String> picked(List<String> include, List<String> exclude) {
		Selection s = new Selection(include, exclude);
		return ALL.stream().filter(s::picks).map(Candidate::name).toList();
	}

	@Test
	void nothingPickedRunsWhatRunsByDefault() {
		assertEquals(List.of("hat", "ride-feel"), picked(List.of(), List.of()));
	}

	@Test
	void picksByNameTagOrClass() {
		assertEquals(List.of("hat"), picked(List.of("hat"), List.of()));
		assertEquals(List.of("hat"), picked(List.of("cosmetics"), List.of()));
		assertEquals(List.of("ride-feel"), picked(List.of("ridefeeltest"), List.of()));
		assertEquals(List.of("ride-feel"), picked(List.of("dev.horsingaround.gametest.ridefeeltest"), List.of()));
	}

	@Test
	void aTestOnRequestRunsOnlyWhenNamedOrTagged() {
		assertEquals(List.of("perf"), picked(List.of("perf"), List.of()));
		assertEquals(List.of("perf"), picked(List.of("slow"), List.of()));
		assertEquals(List.of("hat", "ride-feel"), picked(List.of("*"), List.of()));
		assertEquals(List.of("hat", "ride-feel"), picked(List.of("horsingaround-gametest"), List.of()));
	}

	@Test
	void patternsAndSkips() {
		assertEquals(List.of("ride-feel"), picked(List.of("ride*"), List.of()));
		assertEquals(List.of("ride-feel"), picked(List.of(), List.of("hat")));
		assertEquals(List.of(), picked(List.of("perf"), List.of("slow")));
	}

	@Test
	void namesThatPickNothingAreFound() {
		Selection s = new Selection(List.of("hat", "hta", "rid*", "zzz*"), List.of());
		assertEquals(List.of("hta", "zzz*"), s.unmatched(ALL));
		assertTrue(new Selection(List.of("perf"), List.of()).unmatched(ALL).isEmpty());
	}

	@Test
	void namesComeFromClasses() {
		assertEquals("hat", Names.of(HatTest.class));
		assertEquals("ride-feel", Names.of(RideFeelTest.class));
		assertEquals("http-server", Names.of(HTTPServerTests.class));
		assertEquals("test-summary", Names.of(TestSummary.class));
		assertFalse(Names.of(Test.class).isEmpty());
	}

	private static final class HatTest {}

	private static final class RideFeelTest {}

	private static final class HTTPServerTests {}

	private static final class TestSummary {}
}
