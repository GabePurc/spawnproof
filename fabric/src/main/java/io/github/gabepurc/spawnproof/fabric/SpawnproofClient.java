package io.github.gabepurc.spawnproof.fabric;

import net.fabricmc.api.ClientModInitializer;

/**
 * Makes Spawnproof the one client game test Fabric runs: its runner then runs every test itself, Spawnproof's and plain
 * Fabric ones alike, so one that fails or throws can't stop the others. A mod filter given to Fabric
 * ({@code fabric.client.gametest.modid}) still applies: the runner picks tests from those mods.
 */
public final class SpawnproofClient implements ClientModInitializer {
	static final String FABRIC_FILTER = "fabric.client.gametest.modid";
	/** The filter as it was given, before Spawnproof took it over. */
	static String modFilter;

	@Override
	public void onInitializeClient() {
		if (!Settings.TEST_RUN) return;
		modFilter = System.getProperty(FABRIC_FILTER);
		System.setProperty(FABRIC_FILTER, "spawnproof");
	}
}
