package io.github.gabepurc.spawnproof.fabric;

import io.github.gabepurc.spawnproof.test.Names;
import io.github.gabepurc.spawnproof.test.SpawnproofTest;
import io.github.gabepurc.spawnproof.test.TestContext;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A test the runner found: one of Spawnproof's ({@code "spawnproof"} entrypoints) or a plain Fabric client game test
 * ({@code "fabric-client-gametest"}), which runs as it is, only with a test of its own around it.
 */
record Candidate(String name, Set<String> tags, boolean runByDefault, String mod, String className, Kind kind, Object instance) {
	enum Kind {
		SPAWNPROOF, FABRIC;

		String label() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	void run(ClientGameTestContext game, TestContext t) throws Exception {
		if (instance instanceof SpawnproofTest test) test.run(t);
		else if (instance instanceof FabricClientGameTest test) test.runTest(game);
		else throw new IllegalStateException("couldn't make the test " + className + " (see the cause)", (Throwable) instance);
	}

	/** A test whose class couldn't be made (misspelt in fabric.mod.json, or its constructor threw): it runs as an error. */
	private static Candidate broken(EntrypointContainer<?> c, String mod, Kind kind, RuntimeException e) {
		String className = c.getDefinition();
		String simple = className.substring(className.lastIndexOf('.') + 1);
		String name = simple.isEmpty() ? className : Names.of(simple);
		return new Candidate(name, Set.of(), true, mod, className, kind, e);
	}

	/**
	 * Every test, Spawnproof's first, each kind in the order Fabric lists them (a mod's in the order its fabric.mod.json
	 * gives). {@code modFilter} is Fabric's filter ({@code fabric.client.gametest.modid}), if one was given.
	 */
	static List<Candidate> findAll(String modFilter) {
		Set<String> mods = new LinkedHashSet<>();
		if (modFilter != null) {
			Arrays.stream(modFilter.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(mods::add);
		}
		FabricLoader loader = FabricLoader.getInstance();
		List<Candidate> out = new ArrayList<>();
		for (EntrypointContainer<SpawnproofTest> c : loader.getEntrypointContainers("spawnproof", SpawnproofTest.class)) {
			String mod = c.getProvider().getMetadata().getId();
			if (!mods.isEmpty() && !mods.contains(mod)) continue;
			SpawnproofTest test;
			try {
				test = c.getEntrypoint();
			} catch (RuntimeException e) {
				out.add(broken(c, mod, Kind.SPAWNPROOF, e));
				continue;
			}
			Set<String> tags = new LinkedHashSet<>();
			for (String tag : test.tags()) tags.add(tag.toLowerCase(Locale.ROOT));
			out.add(new Candidate(test.name().toLowerCase(Locale.ROOT), tags, test.runByDefault(), mod, test.getClass().getName(), Kind.SPAWNPROOF, test));
		}
		for (EntrypointContainer<FabricClientGameTest> c : loader.getEntrypointContainers("fabric-client-gametest", FabricClientGameTest.class)) {
			String mod = c.getProvider().getMetadata().getId();
			if (mod.equals("spawnproof") || !mods.isEmpty() && !mods.contains(mod)) continue;
			FabricClientGameTest test;
			try {
				test = c.getEntrypoint();
			} catch (RuntimeException e) {
				out.add(broken(c, mod, Kind.FABRIC, e));
				continue;
			}
			out.add(new Candidate(Names.of(test.getClass()), Set.of(), true, mod, test.getClass().getName(), Kind.FABRIC, test));
		}
		return out;
	}
}
