# Spawnproof

Test infrastructure for Minecraft mods, growing into a test framework (roadmap: #5). Today it has:

- a machine-wide queue and hidden display for test games: the daemon, the Gradle plugin and the `spawnproof` command, in
  `src/`;
- Spawnproof for Fabric, the test API and runner a mod's client game tests are written with, in `fabric/`.

The README says what each does for its users. `fabric/README.md` covers the test API.

## Firm rules

- Every change, however small, starts from a GitHub issue. Reuse one that fits, or open one assigned to the user
  (`--assignee GabePurc`) with the `claude` label when Claude works on it. Claude can't be assigned on GitHub, so the label
  stands in.
- Branch as `<n>-<slug>` from a freshly fetched `origin/main`, not local main. Bring the branch up to date with `main`
  before asking for a merge.
- The PR says `Closes #<n>`; the `linked-issue` check fails a PR into `main` without it. One issue per PR.
- PRs are squash-merged, so the PR's title and description become the commit on `main`: write them for someone reading
  the history.
- Nothing breaks without Spawnproof: a build whose daemon can't be reached, or one running on CI, runs its games directly
  as it always did.

## Build and test

- `./gradlew build` builds both halves and runs the unit tests and the plugin's functional tests. The functional tests
  start real daemons and Gradle builds in temporary folders (`SPAWNPROOF_HOME`), apart from the machine's own queue.
- `./gradlew :fabric:runClientGameTest` runs Spawnproof for Fabric's self-test in a real game.
  `-Pspawnproof.tests=selftest-failing,plain-fabric` runs the tests that fail, throw and leave a world open on purpose.
  Results land in `fabric/build/run/clientGameTest/spawnproof/`.
- `./gradlew :fabric:publishToMavenLocal` puts Spawnproof for Fabric in Maven Local, for trying it in a mod's build.
- The Fabric half needs JDK 25 (Minecraft 26.x); the plugin is compiled for Java 17, so it loads in any mod's Gradle.

## Style

- Plain English, in code comments and docs alike: comments are written for people reading the code, not as an API
  reference. Say what a thing is for and why, and leave out what the code already says.
- Messages people read (the console, `status`, reports) say what happened and what to do about it, without jargon.
