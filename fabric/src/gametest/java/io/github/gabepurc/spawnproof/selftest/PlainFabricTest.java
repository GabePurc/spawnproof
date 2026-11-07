package io.github.gabepurc.spawnproof.selftest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** A plain Fabric client game test, not written for Spawnproof: the runner runs it as one test. */
public final class PlainFabricTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		context.waitTicks(5);
	}
}
