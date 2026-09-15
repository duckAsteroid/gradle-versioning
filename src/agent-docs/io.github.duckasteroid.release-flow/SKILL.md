---
description: "Release-engineering Gradle tasks for a develop/release/main git flow: RC tagging, promotion, changelog generation, and self-installing GitHub Actions workflows."
---

# Release Flow Plugin

Plugin ID: `io.github.duckasteroid.release-flow`

```groovy
plugins {
    id 'io.github.duckasteroid.version'
    id 'io.github.duckasteroid.release-flow'
}
```

Requires `io.github.duckasteroid.version` applied to the same project - it reuses that plugin's
tag-prefix computation for RC tagging. Neither plugin needs `java` or any other language
toolchain, except `installReleaseWorkflows`, which needs a java-toolchain-configuring plugin
present so it knows what Java version to put in the installed workflow's `setup-java` step.

## Tasks

Per-project tasks, plus root-scoped aggregators (named with an `s`) that loop every project the
plugin is applied to - so applying it to a single subproject in a multi-module build still gets a
working aggregator on the root:

| Task | Purpose |
|---|---|
| `tagReleaseCandidate` / `tagReleaseCandidates` | Tags/pushes the next `X.Y.Z-RCn` (auto-incrementing `n`). Run on every push to `release`. |
| `promoteReleaseCandidate` / `promoteReleaseCandidates` | Strips the `-RCn` suffix off the nearest reachable RC tag and tags/pushes the final `X.Y.Z`. Run on every push to `main`. |
| `changelogForReleaseCandidate` | Generates RC release notes (`build/changelog.md`). Run *before* `tagReleaseCandidate` in the same job - it looks for the previous RC tag, which the about-to-be-created tag would otherwise shadow. |
| `changelogForRelease` | Generates final release notes, always covering everything since the last final release. Run *before* `promoteReleaseCandidate` for the same reason. |
| `explainVersion` / `explainVersions` | Read-only: prints a breakdown of how the next version/RC tag would be computed, without tagging anything. |
| `installReleaseWorkflows` / `checkReleaseWorkflows` | Installs (or checks the staleness of) the GitHub Actions workflows below. Root-scoped only. |

## Release-candidate pruning

```groovy
releaseCandidates {
    pruneSuperseded = false   // default: true - keep every RC's GitHub Release forever instead
    retain = 2                // default: 0 - also keep the 2 most recent RCs besides the new one
}
```

Minting a new RC marks every earlier RC in the same cycle as superseded (recorded in
`build/release-manifest.json` by default), leaving cleanup of their GitHub Releases to a CI step.
The underlying git tags and published packages are never deleted.

## Changelog scope

```groovy
changelog {
    rcScope = ChangelogScope.SINCE_PREVIOUS_RC   // default: SINCE_LAST_RELEASE
}
```

`SINCE_LAST_RELEASE` (default) covers the whole cycle so far; `SINCE_PREVIOUS_RC` covers just the
delta since the last RC, falling back to `SINCE_LAST_RELEASE` automatically when there is no
previous RC yet. `changelogForRelease` always uses `SINCE_LAST_RELEASE`, regardless of this
setting.

## Version report

```groovy
versionReport {
    enabled = false                                                 // default: true
    outputFile = layout.buildDirectory.file('reports/version.json') // default: build/version-report.json
}
```

Controls the structured JSON file `explainVersion`/`explainVersions` write alongside their
console output.

## Installing the release workflows

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
