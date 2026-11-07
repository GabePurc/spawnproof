# Spawnproof

Spawnproofing in Minecraft stops mobs from spawning where you don't want them. Spawnproof does the same for test games: it
queues Minecraft test games across every Gradle build on your machine, so two builds never run games over each other, and
it runs them on a hidden display, so they don't pop up on your desktop.

It's made for mod developers who run several builds at once: a few worktrees, a few AI coding sessions, or just you and a
test run. Each build used to start its games whenever it liked. With Spawnproof, they take turns.

Version 0.1. Works with Fabric Loom and NeoForge ModDevGradle builds. The queue works anywhere Java 17 runs; the hidden
display is Linux only for now.

## What it does

- **One queue for the whole machine.** A small background service (the daemon) hands out game slots, two by default. A
  game waits for a slot just before it starts, and gives it back when it closes. Compiling and building jars don't wait,
  only the games do.
- **Hidden games.** Test games draw on a hidden display: [weston](https://wayland.pages.freedesktop.org/weston/) running
  headless, which can use your GPU, or Xvfb, which draws in software. Production runs (Loom's `ClientProductionRunTask`)
  are hidden too.
- **Playing comes first.** While you play (`runClient`, or any `play*` task), new test games wait until you close the game.
  Your game never waits.
- **Games that need the machine to themselves** (performance runs, recordings) can ask to run alone.
- **Nothing breaks without it.** On CI, or if the daemon can't be reached, games run directly as they always did.
- **Crash-proof slots.** A game holds its slot by holding a connection to the daemon. If its build dies, the slot frees
  itself.

## Setup

Spawnproof isn't on the Gradle Plugin Portal yet. Until it is, build it and install it for every build on your machine:

```bash
git clone https://github.com/GabePurc/spawnproof.git
cd spawnproof
./gradlew build
java -jar build/libs/spawnproof-0.1.0.jar install --init-script
```

That puts a `spawnproof` command in `~/.local/bin` and an init script in `~/.gradle/init.d/` that applies the plugin to every
Gradle build you run. Delete `~/.gradle/init.d/spawnproof.gradle` to stop.

Once it's published, a build can apply it itself, in `settings.gradle` (covers every project):

```groovy
plugins {
	id 'io.github.gabepurc.spawnproof' version '0.1.0'
}
```

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

## The spawnproof command

```text
spawnproof status            what's running, waiting and playing, with time left from past runs
spawnproof pause | resume    hold new games (running ones finish) / let them start again
spawnproof slots 3           how many test games run at once
spawnproof display xvfb      which hidden display to use
spawnproof config            every setting (slots, display, resolution, holdWhilePlaying, idleMinutes, ...)
spawnproof run -- <command>  run any command as a queued game: it waits its turn and gets the hidden display
spawnproof stop              stop the daemon (the next game starts it again)
```

In a build with the plugin, `./gradlew spawnproofStatus`, `spawnproofPause` and `spawnproofResume` do the same, and
`spawnproofInstall` installs the command.

`status` looks like this:

```text
Spawnproof 0.1.0 · 2 slots · hidden display :1 (weston, AMD Radeon Graphics (radeonsi, ...))

Running
  #12 Horsing Around :26.3:runClientGameTestRideA (100-hats), 4m 12s, about 2m 30s left, hidden on :1
  #13 Horsing Around :26.3:runClientGameTestTerrain (main), 1m 03s, hidden on :1

Waiting: all 2 game slots are busy
   1. #14 Horsing Around :26.3:runSmoke (108-real-camera), 40s waiting
```

A waiting build says so in its own output too:

```text
Spawnproof: :26.3:runSmoke is next in line: all 2 game slots are busy. Running: Horsing Around :26.3:runClientGameTestRideA (100-hats), ...
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

## Building

```bash
./gradlew build
```

The tests start real daemons and real Gradle builds in temporary folders (`SPAWNPROOF_HOME`), apart from your machine's own
queue.

## License

MIT
