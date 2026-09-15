# gradle-versioning

Gradle plugins for Conventional-Commits-driven versioning and release engineering, built to be
adopted independently of one another and of any particular language toolchain.

## Features

- 🔢 **`project.version` from git + Conventional Commits** - no manual version bumps, no
  hardcoded numbers in `build.gradle`
- 🧩 **No `java` plugin required** - both plugins work in a plugins-only, source-free project
- 📁 **Per-module versioning in a monorepo** - each subproject that applies `io.github.duckasteroid.version`
  gets its own git-tag-based version line, scoped to commits that actually touched its own directory
- 🚦 **`develop` → `release` → `main` release flow** - release-candidate tagging, promotion, and
  changelog generation as plain Gradle tasks, runnable locally or from CI
- 📄 **Self-installing GitHub Actions workflows** - `installReleaseWorkflows` drops in the CI
  wiring for the flow above, with staleness detection so you know when your copy has drifted
- 🧪 **Fully tested** - unit tests for the pure git/commit-parsing logic, functional (`GradleRunner`)
  tests for every task, including configuration-cache and multi-module scenarios

## Two independent plugins

| Plugin ID | What it does |
|---|---|
| `io.github.duckasteroid.version` | Computes `project.version` from git tags + Conventional Commits |
| `io.github.duckasteroid.release-flow` | RC tagging, promotion, changelog generation, workflow install |

`io.github.duckasteroid.release-flow` depends on `io.github.duckasteroid.version` being applied to
the same project (it reuses its version computation for RC candidates) - but neither plugin
depends on `java`, `java-gradle-plugin`, or any other language toolchain. The one exception is
`installReleaseWorkflows`, which needs a java-toolchain-configuring plugin present so it knows what
Java version to put in the installed workflow's `setup-java` step; every other task works in a
project with no language plugin applied at all.

## `io.github.duckasteroid.version`

```groovy
plugins {
    id 'io.github.duckasteroid.version'
}
```

`project.version` is computed by finding the last *final* release tag (plain `vX.Y.Z`, no suffix)
reachable from `HEAD`, then bumping it according to the Conventional Commits messages since that
tag *that touched this module's own directory*:

- `feat` → minor, `fix`/`perf` → patch, `docs`/`style`/`refactor`/`test`/`chore`/`build`/`ci` → no
  bump. A `!` marker or `BREAKING CHANGE:` footer always forces a major bump regardless of type.
- A commit that doesn't conform to Conventional Commits at all (or uses a type outside every
  configured set) bumps patch, with a warning on stderr - not silently ignored.
- The highest-severity qualifying commit wins.
- `HEAD` not sitting exactly on a release tag decorates the version with `-SNAPSHOT` (optionally
  with the sanitized branch name folded in on feature branches).

Tag scheme is `{gradle project path}/vX.Y.Z` (root project collapses to just `vX.Y.Z`), so each
subproject in a monorepo can be versioned and released independently. A subproject with no tag of
its own yet falls back to the root's tag line, so a brand-new module inherits the current version
instead of starting over at `0.0.0`.

### Publishing project dependencies between independently versioned modules

If `maven-publish` is also applied, every `MavenPublication`'s generated POM has its `<dependency>`
entries fixed up automatically: a project dependency (`implementation project(':other-module')`) on
another module that also applies `io.github.duckasteroid.version` is published using that module's
last final release version, not its own possibly `-SNAPSHOT`-decorated `project.version` at the
current `HEAD`. Nothing to configure - this only rewrites dependencies whose `groupId:artifactId`
matches another project in the same build.

### Configuring which commit types bump what

```groovy
commitAnalyzer {
    minorTypes.add('perf2')     // append onto the default minor-bump types
    majorTypes.add('security')  // always treat "security: ..." commits as breaking
    noBumpTypes.set(['docs'])   // REPLACE the default no-bump set entirely
}
```

`.add(...)`/`.addAll(...)` appends to the defaults; `.set(...)` replaces them outright.

### Forcing a version

`-Prelease.forceVersion=X.Y.Z` bypasses all of the above and uses the tag prefix scheme's own
native handling verbatim (see axion-release's
[`force_version`](https://axion-release-plugin.readthedocs.io/en/latest/configuration/force_version/) docs).

## `io.github.duckasteroid.release-flow`

```groovy
plugins {
    id 'io.github.duckasteroid.version'
    id 'io.github.duckasteroid.release-flow'
}
```

Six per-project tasks, plus root-scoped aggregators that loop every project the plugin is applied
to (so applying it to a single subproject in a multi-module build still gets a working
`tagReleaseCandidates` on the root):

| Task | Purpose |
|---|---|
| `tagReleaseCandidate` / `tagReleaseCandidates` | Tags/pushes the next `X.Y.Z-RCn` (auto-incrementing `n`). Intended to run on every push to `release`. |
| `promoteReleaseCandidate` / `promoteReleaseCandidates` | Strips the `-RCn` suffix off the nearest reachable RC tag and tags/pushes the final `X.Y.Z`. Intended to run on every push to `main`. |
| `changelogForReleaseCandidate` | Generates RC release notes (Markdown, `build/changelog.md`). Run *before* `tagReleaseCandidate` in the same job - it looks for the previous RC tag, which the about-to-be-created tag would otherwise shadow. |
| `changelogForRelease` | Generates final release notes, always covering everything since the last final release. Run *before* `promoteReleaseCandidate` for the same reason. |
| `explainVersion` / `explainVersions` | Read-only: prints a breakdown of how the next version/RC tag would be computed, without tagging anything. |
| `installReleaseWorkflows` / `checkReleaseWorkflows` | Installs (or checks the staleness of) the GitHub Actions workflows below. Root-scoped only. |

### Release-candidate pruning

```groovy
releaseCandidates {
    pruneSuperseded = false   // default: true - keep every RC's GitHub Release forever instead
    retain = 2                // default: 0 - also keep the 2 most recent RCs besides the new one
}
```

Release candidates aren't permanent artifacts - by default, minting a new RC marks every earlier
RC in the same cycle as superseded (recorded in `build/release-manifest.json`), leaving cleanup of
their GitHub Releases to a CI step. The underlying git tags and published packages are never
deleted.

### Changelog scope

```groovy
changelog {
    rcScope = ChangelogScope.SINCE_PREVIOUS_RC   // default: SINCE_LAST_RELEASE
}
```

`SINCE_LAST_RELEASE` (default) covers the whole cycle so far; `SINCE_PREVIOUS_RC` covers just the
delta since the last RC, falling back to `SINCE_LAST_RELEASE` automatically when there is no
previous RC yet. `changelogForRelease` always uses `SINCE_LAST_RELEASE`, regardless of this
setting.

### Version report

```groovy
versionReport {
    enabled = false                                                 // default: true
    outputFile = layout.buildDirectory.file('reports/version.json') // default: build/version-report.json
}
```

Controls the structured JSON file `explainVersion`/`explainVersions` write alongside their console
output.

### Installing the release workflows

```bash
./gradlew installReleaseWorkflows
```

Drops `release-candidate.yml` and `promote-release.yml` into `.github/workflows/`, wiring
`tagReleaseCandidate`/`changelogForReleaseCandidate` to `release` pushes and
`promoteReleaseCandidate`/`changelogForRelease` to `main` pushes. Each installed file's first line
is a marker comment recording the plugin version and a hash of the templated body, so a later
`checkReleaseWorkflows` (warns only, never fails the build) can tell you whether your copy is
missing, foreign (no marker), edited since install, stale (an older plugin version's marker), or
up to date. Re-running `installReleaseWorkflows` after editing an installed file is a no-op unless
you pass `-Pduckasteroid.workflows.force=true`.

## Building

```bash
./gradlew build          # compile, run unit + functional tests, assemble the plugin jar
./gradlew test           # unit tests only
./gradlew functionalTest # GradleRunner-based end-to-end tests only
./gradlew check          # both test tasks
```

This project dogfoods the published `duckasteroid-java` plugin from
[gradle-convention-plugin](https://github.com/duckAsteroid/gradle-convention-plugin) for its own
toolchain, resolved via `io.github.duckasteroid.github-packages-settings` in `settings.gradle` -
same mechanism any other consumer of that plugin uses.
