# Spawnproof

Spawnproofing in Minecraft stops mobs from spawning where you don't want them. Spawnproof does the same for test games: it
queues Minecraft test games across every Gradle build on your machine, so two builds never run games over each other, and
it runs them on a hidden display, so they don't pop up on your desktop.

It's made for mod developers who run several builds at once: a few worktrees, a few AI coding sessions, or just you and a
test run. Each build used to start its games whenever it liked. With Spawnproof, they take turns.

Version 0.4. Works with Fabric Loom and NeoForge ModDevGradle builds. The queue works anywhere Java 17 runs;
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
- **Pull requests tested on your machine.** `spawnproof watch` tests each new commit on a mod's GitHub pull requests here,
  not on GitHub's machines, and puts the result on the pull request as a check you can require before merging.
- **A dashboard.** `spawnproof dashboard` opens a desktop app that shows what's running and waiting, and each mod's past
  test runs with their checks, errors and screenshots.

## Setup

Download `spawnproof-0.4.0.jar` from the [latest release](https://github.com/GabePurc/spawnproof/releases/latest) and install
it for every build on your machine:

```bash
java -jar spawnproof-0.4.0.jar install --init-script
```

That puts a `spawnproof` command in `~/.local/bin` and an init script in `~/.gradle/init.d/` that applies the plugin to every
Gradle build you run. Delete `~/.gradle/init.d/spawnproof.gradle` to stop. To update, run the same `install` with the new
jar: it's copied under a name made from its contents, so running Gradle daemons load the new one, and the background
service replaces itself once its games are done.

Or a build can apply it itself, from the Gradle Plugin Portal, in `settings.gradle` (covers every project):

```groovy
plugins {
	id 'io.github.gabepurc.spawnproof' version '0.4.0'
}
```

To run changes that aren't released yet, build a clone (`./gradlew build`) and install
`build/libs/spawnproof-0.5.0-SNAPSHOT.jar` the same way.

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

## Testing pull requests on your machine: spawnproof watch

In a mod's folder:

```bash
spawnproof watch                  # test each new commit on this repo's pull requests, as spawnproof test would
spawnproof watch --games 3 hat    # with these spawnproof test options
spawnproof pr 42                  # test pull request #42 now: one from a fork, or one to run again
spawnproof unwatch                # stop
```

Spawnproof then looks at the repo's open pull requests on GitHub every minute, and tests each new commit on this machine.
GitHub hears how it went:

- The commit gets a status named `spawnproof`: waiting, testing, then passed or failed, with how many tests failed. Add
  `spawnproof` to a branch's required status checks on GitHub, and a pull request can't be merged until its tests pass.
- When a run doesn't pass, a comment on the pull request says what failed, or what Gradle said if the build failed before
  the tests. Spawnproof edits that one comment after each run, so it also says when they pass again. The full results
  stay on your machine, and the comment says where.

How it goes:

- The tests run in a clone of the repo of their own, in Spawnproof's state folder (`watch/<owner>/<repo>/`), so your
  checkouts are left alone. The clone is kept between runs, build folders and all, so Gradle and Loom don't start over
  each time.
- Their games wait their turn in the queue like any other build's, and your playing comes first as always. Each repo has
  one pull request tested at a time, and a newer commit stops the test of an older one. A pull request that's merged
  while it's tested is still tested, so its commit gets its check; one closed without merging is let go.
- Pull requests from forks are left alone until you ask (`spawnproof pr <number>`): testing one runs a stranger's code on
  your machine, so read it first. Those from the repo's own branches are tested on their own, so anyone who can push to
  the repo can run code on your machine; for most mods that's only you.
- Your machine asks GitHub; nothing needs to reach your machine from the internet. While it's off, a pull request's check
  waits. Spawnproof catches up when it starts again, and tests again whatever it was in the middle of.
- It signs in to GitHub as you, with the token `gh auth token` gives ([GitHub CLI](https://cli.github.com/)), or a token
  in `~/.config/spawnproof/github-token`. It needs write access to the repo, to set statuses and comment; `spawnproof
  watch` checks for that first.
- The daemon stays running while it watches anything, instead of stopping after an idle hour, and a build with an older
  Spawnproof uses it as it is rather than swapping in its own version. `spawnproof stop` needs `--force` while anything is
  watched. On Linux, `spawnproof install --service` adds a systemd user service that starts it when you log in, too.
- `spawnproof status` lists what's watched, what's being tested, and what each pull request came to.
- GitHub's own CI is left as it is: it goes on running what it runs, and a build on CI still runs its games directly.

## The dashboard

A desktop app for looking at what Spawnproof is doing and has done:

```bash
spawnproof dashboard
```

- **Live**: the queue as `spawnproof status` shows it, kept up to date: the games running and how long they have left, the
  ones waiting and why, anyone playing, and what the watched repos' pull requests are being tested on.
- **Projects**: every mod Spawnproof has run games for, with how its last test run went.
- **Results**: a project's past test runs, from each of its checkouts and its pull requests. Each shows its tests, their
  checks (what failed and why), errors with their stack traces, which game ran what, and the screenshots, which open full
  size.

Past runs are kept in Spawnproof's state folder (`runs/`), since Loom clears the run folder before every run: a test run
(`spawnproof test`, `-Pspawnproof.games`) and a game task run on its own both keep their results when they end, if their
tests are written with [Spawnproof for Fabric](fabric/README.md). Only the last 20 runs of each task in each checkout are
kept (`spawnproof config keepRuns 50` keeps more). Nothing is kept on CI.

The app talks to the daemon over its socket, like the `spawnproof` command, and starts it if it isn't running. While the
app is open, the daemon stays up. It's made with [Tauri](https://tauri.app/), works on Linux and macOS, and until releases
carry it, it's built from a clone of Spawnproof, with [Rust](https://www.rust-lang.org/tools/install) and, on Linux, the web
view's libraries:

```bash
sudo apt install libwebkit2gtk-4.1-dev libgtk-3-dev librsvg2-dev libxdo-dev   # Linux only
cd dashboard
cargo build --release
cp target/release/spawnproof-dashboard ~/.local/bin/
```

`spawnproof dashboard` looks for it on the `PATH` and in `~/.local/bin`, or wherever `SPAWNPROOF_DASHBOARD` points.

## The spawnproof command

```text
spawnproof status            what's running, waiting and playing, with time left from past runs
spawnproof pause | resume    hold new games (running ones finish) / let them start again
spawnproof slots 3           how many test games run at once
spawnproof display xvfb      which hidden display to use
spawnproof config            every setting (slots, display, resolution, holdWhilePlaying, idleMinutes, ...)
spawnproof run -- <command>  run any command as a queued game: it waits its turn and gets the hidden display
spawnproof test              in a mod's folder: run its tests across games, with one set of results
spawnproof watch | unwatch   in a mod's folder: test its pull requests' new commits here, and tell GitHub / stop
spawnproof pr <number>       in a mod's folder: test a pull request now
spawnproof install --service start the daemon when you log in (Linux, systemd), for watched repos
spawnproof dashboard         open the dashboard app: projects, live games, past results with screenshots
spawnproof start             start the daemon if it isn't running (the first game starts it anyway)
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
terminal can't take other builds' games with it). It exits after an hour with nothing to do, unless it's watching a repo.
A daemon of another version is replaced once it has nothing running.

Settings live in `~/.config/spawnproof/config.properties`; logs and history (one JSON line per game) in
`~/.local/state/spawnproof/`.

A watched repo (`spawnproof watch`, kept in `~/.config/spawnproof/watching.json`) is looked at every `watchSeconds` (60).
The daemon asks GitHub for its open pull requests with the last answer's ETag, so an answer that hasn't changed costs
nothing against GitHub's rate limit. A new commit is fetched into the repo's clone (`refs/pull/<n>/head`), checked out, and
tested by running the mod's Gradle wrapper as `spawnproof test` does, with `SPAWNPROOF_RESULTS_LOG` naming a file each test
run adds the place of its results to. What each pull request came to is kept in `prs.json` beside the clone.

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
