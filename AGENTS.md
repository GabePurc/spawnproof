# Spawnproof

Test infrastructure for Minecraft mods, growing into a test framework (roadmap: #5). Today it has:

- a machine-wide queue and hidden display for test games: the daemon, the Gradle plugin and the `spawnproof` command, in
  `src/`, with the daemon's testing of watched repos' pull requests in its `watch` package;
- Spawnproof for Fabric, the test API and runner a mod's client game tests are written with, in `fabric/`.
- the dashboard app, in `dashboard/`: a Tauri app (Rust, with a plain HTML and JavaScript window in `ui/`) that's a client
  of the daemon's socket, like the command.

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
- `java -jar ../build/libs/spawnproof-0.4.0-SNAPSHOT.jar test --games 2`, in `fabric/` after `./gradlew build`, runs that
  self-test as a test run across two games with this checkout's plugin. It queues on the machine's daemon, which then
  runs this build until another build replaces it.
- `cargo build` in `dashboard/` builds the dashboard app, apart from Gradle; `cargo test` and `cargo clippy` check it, as CI
  does. On Linux it needs `libwebkit2gtk-4.1-dev`, `libgtk-3-dev`, `librsvg2-dev` and `libxdo-dev`. Its window can be looked
  at in a browser by standing in for `window.__TAURI__` with sample data.
- `./gradlew :fabric:publishToMavenLocal` puts Spawnproof for Fabric in Maven Local, for trying it in a mod's build.
- The Fabric half needs JDK 25 (Minecraft 26.x); the plugin is compiled for Java 17, so it loads in any mod's Gradle.

## Releasing

- `version` in `gradle.properties` is the next release, as a snapshot (`0.4.0-SNAPSHOT`).
- A release is a GitHub release on `main` tagged `v0.4.0`, made only when the user asks: Maven Central keeps every version
  for good. `.github/workflows/release.yml` checks the tag against `gradle.properties`, then puts each loader's library on
  Maven Central (`spawnproof-fabric` so far), the plugin on the Gradle Plugin Portal and the jar on the release. If one of those jobs fails, rerun only
  that job.
- After a release, the next change's PR moves `version` on to the next snapshot.
- The workflow's secrets:
  - `CENTRAL_USERNAME` and `CENTRAL_PASSWORD`, a Central Portal user token;
  - `SIGNING_KEY`, an armored private key whose public half is on keyserver.ubuntu.com, and `SIGNING_PASSWORD`;
  - `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET`, from the Plugin Portal.
- To see what would go to Central without sending it: `SIGNING_KEY=... ./gradlew -Pversion=0.4.0 nmcpZipAggregation`, then
  look in `build/nmcp/zip/`.

## Style

- Plain English, in code comments and docs alike: comments are written for people reading the code, not as an API
  reference. Say what a thing is for and why, and leave out what the code already says.
- Messages people read (the console, `status`, reports) say what happened and what to do about it, without jargon.
