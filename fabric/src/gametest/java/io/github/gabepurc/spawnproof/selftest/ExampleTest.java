package io.github.gabepurc.spawnproof.selftest;

import io.github.gabepurc.spawnproof.test.GameClock;
import io.github.gabepurc.spawnproof.test.LogWatch;
import io.github.gabepurc.spawnproof.test.SpawnproofTest;
import io.github.gabepurc.spawnproof.test.TestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.util.Set;

/** What a test does, all of it passing: a world, checks of each kind, a screenshot, a log watch, a file. */
public final class ExampleTest implements SpawnproofTest {
	@Override
	public Set<String> tags() {
		return Set.of("selftest");
	}

	@Override
	public void run(TestContext t) throws Exception {
		ClientGameTestContext game = t.game();
		try (TestSingleplayerContext world = game.worldBuilder()
			.adjustSettings(settings -> settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE))
			.create()) {
			world.getServer().runCommand("time set noon");
			world.getConnection().waitForChunksRender();

			t.section("A flat world");
			t.require("the player is in the world", game.computeOnClient(mc -> mc.player != null));
			t.check("standing on the ground", game.computeOnClient(mc -> mc.player.getY()), -61, -59);
			t.checkEqual("in creative", "CREATIVE", game.computeOnClient(mc -> mc.gameMode.getPlayerMode().name()));
			t.screenshot("noon");

			t.section("The game's clock");
			long nanos = GameClock.nanos();
			long ticks = GameClock.ticks();
			game.waitTicks(100);
			double seconds = (GameClock.nanos() - nanos) / 1e9;
			t.check("100 ticks are about 5 s of the game's clock", seconds, 4.0, 6.0);
			t.checkEqual("ticks counted", 100L, GameClock.ticks() - ticks);
			t.note("the game runs on %s", GameClock.onFrames() ? "drawn frames" : "real time");
			Files.writeString(t.file("clock.txt"), "100 ticks took " + seconds + " s of the game's clock\n");

			t.section("Watching the log");
			try (LogWatch watch = t.watchLog()) {
				LoggerFactory.getLogger("spawnproof-selftest").warn("a warning the watch should see");
				LoggerFactory.getLogger("spawnproof-selftest").warn("one it should leave out");
				watch.ignore("leave out");
				LoggerFactory.getLogger("spawnproof-selftest").warn("one it should leave out, now ignored");
				t.checkEqual("the watch saw the warning", 1, watch.matching("the watch should see").size());
				t.checkEqual("and left out the ignored one", 2, watch.lines().size());
			}
		}
	}
}
