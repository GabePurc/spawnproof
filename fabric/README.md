# Spawnproof for Fabric

The in-game half of Spawnproof: a small library a mod's client game tests are written with. It runs them, records every
check, and writes results that people, programs and CI can read. It also makes test games fast: their clock runs on drawn
frames, not real time.

It builds on Fabric's own client game test API (`fabric-client-gametest-api-v1`): worlds, input, waiting for ticks and
screenshots are Fabric's. Spawnproof adds what a test suite needs around them.

Version 0.2, in progress. Minecraft 26.3 on Fabric for now.

## What it does

- **Named checks that don't stop the test.** `t.check("hat drawn", ok)` records the check and goes on, so one run shows
  every check that fails, not only the first. `t.require(...)` is for the checks the rest of a test needs.
- **One test at a time, each on its own.** Every test runs in the same game, one after another. One that fails or throws is
  recorded and the next one runs.
- **Results for everyone.** In the game folder's `spawnproof/`:
  - `results.json`, for programs and AI agents.
  - `report.txt`, for people.
  - `junit.xml`, for CI.
  - `events.jsonl`, written as the run goes, so a crashed run still says how far it got.
  - The tests' screenshots and files.
- **Fast test games.** The game's clock counts drawn frames, so a test game runs as fast as the machine can draw, usually
  several times normal speed. It doesn't slow down when minimized or covered. It runs on the lowest settings, makes no
  sound and never grabs the cursor.
- **Plain Fabric tests still run.** Tests written as `FabricClientGameTest` run inside Spawnproof as they are, so a mod can
  move its tests over one at a time.

## Setup

Spawnproof isn't published yet. Build it and put it in Maven Local:

```bash
./gradlew :fabric:publishToMavenLocal
```

Then, in the mod's build, with Loom's client game tests set up (`fabricApi { configureTests { ... } }`):

```groovy
repositories {
	exclusiveContent {
		forRepository { mavenLocal() }
		filter { includeModule 'io.github.gabepurc', 'spawnproof-fabric' }
	}
}
dependencies {
	gametestImplementation 'io.github.gabepurc:spawnproof-fabric:0.2.0-SNAPSHOT'
}
```

## Writing a test

A test is a class that implements `SpawnproofTest`:

```java
public final class HatTest implements SpawnproofTest {
	@Override
	public void run(TestContext t) {
		ClientGameTestContext game = t.game();
		try (TestSingleplayerContext world = game.worldBuilder().create()) {
			t.section("A supporter wearing the hat");
			game.runOnClient(mc -> Supporters.addForTesting(mc.player.getUUID()));
			game.waitTicks(5);
			t.checkEqual("hat drawn in the chosen colour", "#8e2b2b", drawnColour(game));
			t.check("helmet still worn", game.computeOnClient(mc -> !mc.player.getItemBySlot(EquipmentSlot.HEAD).isEmpty()));
			t.screenshot("front");
		}
	}
}
```

List it in the test mod's `fabric.mod.json`, under `spawnproof`:

```json
"entrypoints": {
	"spawnproof": ["com.example.gametest.HatTest"]
}
```

Run it with Loom's `./gradlew runClientGameTest`.

### What a test can do

| | |
| --- | --- |
| `t.game()` | Fabric's test context: `worldBuilder()`, `waitTicks`, `runOnClient`, `computeOnClient`, `getInput()`, ... |
| `t.section(name)` | Starts a part of the test; the checks after it are listed under it |
| `t.check(name, ok)` | A check; the test goes on if it fails |
| `t.check(name, ok, detail)` | With what went wrong, recorded when it fails |
| `t.check(name, value, min, max)` | A number in a range; the value is recorded either way |
| `t.checkEqual(name, expected, actual)` | Two things equal; both recorded when not |
| `t.require(name, ok)` | A check the rest of the test needs: when it fails, the test stops |
| `t.fail(reason)` | Stops the test, failed |
| `t.note(format, args...)` | A line in the report |
| `t.screenshot(name)` | Saved as `screenshots/<test>/<name>.png` and listed with the results |
| `t.file(name)` | A file for the test's own output, in `files/<test>/` |
| `t.watchLog()` | Collects the log's warnings and errors until closed, to check for ones that shouldn't be there |
| `GameClock.nanos()` | The game's clock, for code that times itself between frames |

A test may also override:

- `name()`: the name it's picked by. By default it's the class name without `Test`: `HatTest` is `hat`.
- `tags()`: more names to pick it by.
- `runByDefault()`: `false` for a test that runs only when asked for by name or tag, such as a performance run.

A test must close every world and server it opens. Use try-with-resources. A test that leaves one open stops the run,
since the tests after it couldn't start clean.

## Running

Spawnproof reads these system properties:

| Property | |
| --- | --- |
| `spawnproof.tests` | Tests to run, by name, tag, class or mod id, comma separated; `*` matches anything. Unset: every test that runs by default |
| `spawnproof.skip` | Tests to leave out, the same way |
| `spawnproof.clock` | `frames` (default) or `real` |
| `spawnproof.graphics` | `low` (default) or `normal`, for screenshots people will see |
| `spawnproof.output` | The results folder; `spawnproof/` in the game folder by default |

To pass them from the command line, in the mod's build:

```groovy
tasks.named('runClientGameTest') {
	['spawnproof.tests', 'spawnproof.skip', 'spawnproof.clock', 'spawnproof.graphics'].each { name ->
		def value = providers.gradleProperty(name).orNull
		if (value != null) systemProperty name, value
	}
}
```

Then `./gradlew runClientGameTest -Pspawnproof.tests=hat`.

A name in `spawnproof.tests` that matches no test fails the run before anything starts. A typo would otherwise run
nothing and pass.

The game's exit code fails the Gradle task when any test fails. The console ends with a short summary: what failed, and
where the results are.

### Results

`report.txt`:

```text
Spawnproof results: failed
2 tests: 1 passed, 1 failed. 23 checks, 1 failed. 6.1s, frame clock at 62 ticks a second.

## hat: FAILED (5.5s, 325 ticks, 1 of 16 checks failed)
== A supporter wearing the hat
  FAIL  hat drawn in the chosen colour: expected #8e2b2b, got none
  PASS  helmet not drawn
  screenshot front -> screenshots/hat/front.png
```

`results.json` holds the same, plus the game's versions and mods. Each test has its status (`passed`, `failed`, `error` or
`not-run`), duration, ticks, checks, screenshots, error and the warnings and errors logged while it ran. Its `format` and
`version` fields name the format, and `version` goes up when a field's meaning changes.

## The frame clock

A test game spends most of its time waiting: a tick is 50 ms of the game's clock, however little of it the game needs. On
the frame clock, each drawn frame counts as the time a frame takes in a game at its default limit (5.9 a tick). So a test
game runs as fast as the machine can draw, and a tick gets the same frames on a busy machine as on an idle one. The
server keeps step with the client and stops waiting between ticks, as in `/tick sprint`.

The game reads its clock through `Util.getNanos()`. A mod's own code that times itself on `System.nanoTime()` would run at
real speed while the game around it runs faster. Use `GameClock.nanos()` there, or redirect the call in a test mixin.
`-Dspawnproof.clock=real` runs at normal speed, to tell a problem with the clock from a real one. A game that draws no
frame for 2 minutes logs its threads and stops, failing the run.

## Building

`./gradlew :fabric:build` builds it. `./gradlew :fabric:runClientGameTest` runs Spawnproof's own tests of the runner in a
real game (`src/gametest`). `-Pspawnproof.tests=selftest-failing,plain-fabric` runs the ones that fail, throw and leave a
world open on purpose, to see how each is reported.
