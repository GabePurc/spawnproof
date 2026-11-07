# Spawnproof

Spawnproofing in Minecraft stops mobs from spawning where you don't want them. Spawnproof does the same for test games: it
queues Minecraft test games across every Gradle build on your machine, so two builds never run games over each other, and
it runs them on a hidden display, so they don't pop up on your desktop.

It's made for mod developers who run several builds at once: a few worktrees, a few AI coding sessions, or just you and a
test run. Each build used to start its games whenever it liked. With Spawnproof, they take turns.

Version 0.3. Works with Fabric Loom and NeoForge ModDevGradle builds. The queue works anywhere Java 17 runs;
the hidden display is Linux only for now.

Spawnproof is growing into a test framework for Minecraft mods. Its first piece is [Spawnproof for Fabric](fabric/README.md):
a library a mod's client game tests are written with. It records named checks, runs each test on its own, and writes
results for people (`report.txt`), programs (`results.json`) and CI (`junit.xml`). It also runs test games several times
faster than normal on a clock that counts drawn frames.

## What it does

- **One queue for the whole machine.** A small background service (the daemon) hands out game slots, two by default. A
  game waits for a slot just before it starts, and gives it back when it closes. Compiling and building jars don't wait,
  only the games do.
- **Hidden games.** Test games draw on a hidden display: [weston](https://wayland.pages.freedesktop.org/weston/) running
  headless, which draws on your GPU at full speed (tried on an NVIDIA card), or Xvfb, which draws in software. Production runs (Loom's `ClientProductionRunTask`)
  are hidden too.
- **Playing comes first.** While you play (`runClient`, or any `play*` task), new test games wait until you close the game.
  Your game never waits.
- **Games that need the machine to themselves** (performance runs, recordings) can ask to run alone.
- **Timeouts count from when the game starts.** A test game task's own Gradle `timeout` is taken over and counted from
  the moment its game starts, so waiting in line never uses it up.
- **Nothing breaks without it.** On CI, or if the daemon can't be reached, games run directly as they always did.
- **Crash-proof slots.** A game holds its slot by holding a connection to the daemon. If its build dies, the slot frees
  itself.
- **A mod's tests across several games.** `spawnproof test` shares a mod's client game tests out between several games at
  once, the longest first by past runs, and merges their results into one set. `--only-failures` reruns what failed last
  time.

## Setup

Download `spawnproof-0.3.0.jar` from the [latest release](https://github.com/GabePurc/spawnproof/releases/latest) and install
it for every build on your machine:

```bash
java -jar spawnproof-0.3.0.jar install --init-script
```

That puts a `spawnproof` command in `~/.local/bin` and an init script in `~/.gradle/init.d/` that applies the plugin to every
Gradle build you run. Delete `~/.gradle/init.d/spawnproof.gradle` to stop. To update, run the same `install` with the new
jar: it's copied under a name made from its contents, so running Gradle daemons load the new one, and the background
service replaces itself once its games are done.

Or a build can apply it itself, from the Gradle Plugin Portal, in `settings.gradle` (covers every project):

```groovy
plugins {
	id 'io.github.gabepurc.spawnproof' version '0.3.0'
}
```

To run changes that aren't released yet, build a clone (`./gradlew build`) and install
`build/libs/spawnproof-0.3.0-SNAPSHOT.jar` the same way.

### The hidden display (Linux)

Install weston (with Xwayland) or Xvfb. `glxinfo` (mesa-utils) is optional: with it, `spawnproof status` shows what draws the
games.

```bash
sudo apt install weston xvfb mesa-utils
```

Spawnproof picks weston if it's there, and Xvfb otherwise. `spawnproof display <auto|weston|xvfb|none>` chooses; `none` shows
games on your desktop again.

## What gets queued

Most builds need no settings. By task name:

| Task | Counts as |
| --- | --- |
| `runClient`, and `runFabricClient`, `runNeoForgeClient` and the like in multi-loader builds | Playing |
| Anything starting with `play` (`playCompatIcys`) | Playing |
| Any other run with `client` in its name (`runClientGameTest`, `runAuditClient`) | Test game |
| Anything with `smoke` in its name | Test game |
| Loom production client runs (`ClientProductionRunTask`) | Test game |
| Everything else, including `runServer` and server game tests | Left alone |

`./gradlew spawnproofTasks` lists a project's game tasks and how they queue, without starting anything.

For anything else, in `settings.gradle` (every project) or a build script (that project):

```groovy
spawnproof {
	tests 'runVisualCheck', ':fabric:runCustom*'   // test games
	play 'runDemo'                                  // you playing
	exclusive 'runPerf*'                            // test games that run alone
	ignore 'runClientGameTestHeadless'             // leave these be
	hidden = false                                  // show test games on the desktop
}
```

Or on one task, for what a name can't say:

```groovy
tasks.named('runClientGameTest') {
	spawnproof.exclusive = providers.gradleProperty('tests').getOrElse('').contains('perf')
}
```

For one build: `-Pspawnproof.exclusive` (run this build's games alone), `-Pspawnproof.visible` (show them),
`-Pspawnproof.off` (skip the queue). `SPAWNPROOF=off` in the environment also skips it, and so does `CI`.

## Running a mod's tests: spawnproof test

In a mod's folder:

```bash
spawnproof test                       # every test, across as many games as the queue has slots
spawnproof test hat ride --games 3    # some tests, across 3 games
spawnproof test --only-failures       # only what failed last time
```

It runs the mod's `runClientGameTest` (`--task` picks another) with the tests shared out between several games. The games
don't split them up front: each takes one test at a time, the longest first by past runs, so a game that waits longer for
its slot, or stops early, leaves its share to the others. A game that crashes takes only the test it was running with it;
the rest go on in the other games, or in one started in its place.

The results are merged into the folder one game would have written them to (`spawnproof/` in the run folder):
`results.json`, `report.txt`, `junit.xml` and the screenshots, as from a single game, with which game ran what. Each game
runs in a folder of its own, `games/<n>/` in the run folder, with a copy of what the build put there (resource packs,
options, the EULA), its own `logs/`, and its console in `output.log`. The task fails if any test did, and the console ends
with what failed and where to look:

```text
[spawnproof] 5 tests: 2 passed, 1 failed, 2 errors. 14 checks, 4 failed. 15s across 3 games, frame clock at 37 ticks a second.
[spawnproof] FAIL failing > Checks that fail > a number out of range = 3.000 (target 0.000..1.000)
[spawnproof] ERROR throwing: java.lang.IllegalStateException: thrown on purpose (at ...ThrowingTest.run(ThrowingTest.java:25))
[spawnproof] Results: .../build/run/clientGameTest/spawnproof/results.json (report.txt, junit.xml, screenshots/ beside it)
```

`spawnproof test` runs the mod's Gradle wrapper with the plugin applied, so it works in any mod's folder. The same from
Gradle itself, in a build with the plugin:

| | |
| --- | --- |
| `-Pspawnproof.games=3` | Run the task's tests across 3 games; `-Pspawnproof.games` alone, as many as the queue has slots |
| `-Pspawnproof.onlyFailures` | Only the tests that failed in this task's last test run in this checkout (and with nothing to go by, all of them) |
| `-Pspawnproof.tests=hat,ride` | Passed to every test game, as are `skip`, `clock` and `graphics` ([Spawnproof for Fabric's settings](fabric/README.md#running)) |

What it needs and how it goes:

- The tests are written with [Spawnproof for Fabric](fabric/README.md), and the game task is a `JavaExec`: Loom's
  `runClientGameTest` and the runs made like it. Linux and macOS for now, not Windows.
- Never more games than tests, and a game that's still waiting for its slot when every test is taken doesn't start.
- The task's Gradle `timeout` applies to each game, counted from when that game starts.
- Past times and failures are kept in Spawnproof's state folder (`tests/`), since Loom clears the run folder before every
  run. A new checkout of a project (a worktree) goes by another checkout's times until it has its own; failures belong to
  their checkout.
- On CI, or with the queue off, the games run without waiting their turn, as games do without Spawnproof.

## The spawnproof command

```text
spawnproof status            what's running, waiting and playing, with time left from past runs
spawnproof pause | resume    hold new games (running ones finish) / let them start again
spawnproof slots 3           how many test games run at once
spawnproof display xvfb      which hidden display to use
spawnproof config            every setting (slots, display, resolution, holdWhilePlaying, idleMinutes, ...)
spawnproof run -- <command>  run any command as a queued game: it waits its turn and gets the hidden display
spawnproof test              in a mod's folder: run its tests across games, with one set of results
spawnproof stop              stop the daemon (the next game starts it again)
```

In a build with the plugin, `./gradlew spawnproofStatus`, `spawnproofPause` and `spawnproofResume` do the same, and
`spawnproofInstall` installs the command.

`status` looks like this:

```text
Spawnproof 0.1.0 · 2 slots · hidden display :2 (weston, NVIDIA GeForce RTX 3060 Ti/PCIe/SSE2)

Running
  #12 horsing-around :26.3:runClientGameTestRideA (100-hats), 4m 12s, about 2m 30s left, hidden on :2
  #13 horsing-around :26.3:runClientGameTestTerrain (main), 1m 03s, hidden on :2

Waiting: all 2 game slots are busy
   1. #14 horsing-around :26.3:runSmoke (108-real-camera), 40s waiting
```

A waiting build says so in its own output too:

```text
Spawnproof: :26.3:runSmoke is next in line: all 2 game slots are busy. Running: horsing-around :26.3:runClientGameTestRideA (100-hats), ...
```

`SPAWNPROOF_LABEL` names a build in `status` instead of its branch, which helps tell several AI sessions apart.

## How it works

The plugin adds one action to every game task, run just before the game starts. It connects to the daemon over a Unix
domain socket (`$XDG_RUNTIME_DIR/spawnproof/daemon.sock`) and waits for a slot. The daemon is first come, first served:
nobody overtakes the head of the line, so a game that needs the machine to itself can't be starved by smaller ones. When the
slot comes, the daemon starts the hidden display if it isn't up, and the action points the game at it (`DISPLAY`). When
the task finishes, or the build ends, or the process dies, the connection closes and the slot frees.

The first build that needs the daemon starts it, from a copy of the plugin's jar, in a session of its own (so Ctrl+C in a
terminal can't take other builds' games with it). It exits after an hour with nothing to do. A daemon of another version
is replaced once it has nothing running.

Settings live in `~/.config/spawnproof/config.properties`; logs and history (one JSON line per game) in
`~/.local/state/spawnproof/`.

A test run (`spawnproof test`) swaps the game task's java for a small script, so it gets the game's command line just as the
build made it, classpath and all. The script starts the run, which waits for a slot for each game and starts the command
once for each. Each game says which tests it found and asks the run for one at a time, over a socket of the run's own.
After each test it writes that test's results as a part (`parts/` in its results folder), and the run merges the parts.

## Building

```bash
./gradlew build
```

That builds the Gradle plugin (`build/libs/`) and Spawnproof for Fabric (`fabric/build/libs/`), which needs JDK 25 for
Minecraft 26.x: an installed one is found, or Gradle downloads one. The plugin's tests start real daemons and real Gradle
builds in temporary folders (`SPAWNPROOF_HOME`), apart from your machine's own queue.

## License

MIT
