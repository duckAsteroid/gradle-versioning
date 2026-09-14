# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

This project dogfoods a Gradle MCP server — prefer `mcp__gradle-mcp__gradle` /
`mcp__gradle-mcp__query_build` / `mcp__gradle-mcp__wait_build` over shelling out to `./gradlew`
when that server is available in this session; fall back to Bash only if it isn't.

- `./gradlew build` — compile, run unit + functional tests, assemble the plugin jar
- `./gradlew test` — unit tests only (JUnit 5, plain `ProjectBuilder`/pure-Java tests)
- `./gradlew functionalTest` — `GradleRunner`-based end-to-end tests only (each spins up a real
  Gradle build in a temp dir)
- `./gradlew check` — both test tasks
- Single test class: `./gradlew test --tests "io.github.duckasteroid.gradle.versioning.VersionResolverTest"`
- Single functional test class: `./gradlew functionalTest --tests "io.github.duckasteroid.gradle.versioning.releaseflow.ExplainVersionFunctionalTest"`

There is no separate lint/format task beyond what `duckasteroid-java` (the convention plugin this
project applies to itself) wires into `check`.

## Architecture

Two independent Gradle plugins, both under `io.github.duckasteroid.gradle.versioning`:

- **`io.github.duckasteroid.version`** (`VersionPlugin`) — computes `project.version` from git
  tags + Conventional Commits. No dependency on the `java` plugin.
- **`io.github.duckasteroid.release-flow`** (`releaseflow.ReleaseFlowPlugin`) — RC tagging,
  promotion, changelog generation, GitHub Actions workflow install. Depends on
  `io.github.duckasteroid.version` being applied to the same project (reuses its tag-prefix
  computation) but not on `java`, except for one task (`installReleaseWorkflows`, which needs a
  Java-toolchain plugin present to know what Java version to template into the installed
  workflow's `setup-java` step).

### Core version-resolution pipeline

- **`VersionResolver`** (pure logic, JGit-based, no Gradle types) is where almost everything
  lives: finding the last final release tag reachable from HEAD (with fallback prefixes for a
  monorepo submodule that has no tag of its own yet), walking commits since that tag scoped to a
  module's own directory, bumping the version, minting/promoting RC tags, and gathering changelog
  commit ranges. Read its class-level doc comment before touching it — it documents *why* JGit is
  used instead of shelling out to `git` (Gradle configuration-cache compatibility) and the
  `excludedModulePaths` sibling-exclusion mechanism for monorepos.
- **`CommitAnalyzer`** (pure logic, no Gradle/git types) is a minimal Java port of
  semantic-release's commit-analyzer: parses a commit message's Conventional Commits shape and
  classifies it into `Bump.{NONE,PATCH,MINOR,MAJOR}` against a configurable `Map<Bump,
  Set<String>>` type-rule table (`CommitAnalyzerExtension` is the Gradle-facing DSL for
  customizing it). A non-conforming commit is treated as `PATCH` + a stderr warning, not silently
  ignored — a deliberate departure from upstream semantic-release.
- **`VersionPlugin.apply()`** wires these together: applies axion-release (still used as the raw
  per-module tag-prefix scheme and as the `-Prelease.forceVersion` backstop), computes this
  project's own tag prefix (`{gradle path}/v`, collapsing to `v` at root), and defers
  `project.setVersion(...)` to `afterEvaluate` so a consumer's own `commitAnalyzer { }`
  customization in their `build.gradle` has already run. Exposes `project.ext.tagPrefix` /
  `project.ext.modulePath` via `ExtraPropertiesExtension` for `ReleaseFlowPlugin` to reuse without
  recomputing.

### Release-flow tasks

`ReleaseFlowPlugin.apply()` registers per-project tasks (`tagReleaseCandidate`,
`promoteReleaseCandidate`, `changelogForReleaseCandidate`, `changelogForRelease`,
`explainVersion`) plus root-scoped aggregators (`tagReleaseCandidates`,
`promoteReleaseCandidates`, `explainVersions`, `installReleaseWorkflows`,
`checkReleaseWorkflows`) that loop every project the plugin is applied to. The aggregators are
guarded with a `findByName(...) == null` check so they're registered exactly once on the root
project regardless of how many subprojects apply the plugin — see the class doc comment for why
(otherwise Gradle's unqualified task matching would run one aggregator instance per applying
project, racing on the same `build/release-manifest.json`).

**Configuration-cache constraint that shapes this whole plugin**: task `doLast { }` actions never
touch a live `Project` — everything needed (tag prefix, module path, project dir, extension
values) is captured into plain local variables during *configuration*, and the plural aggregators'
list of applying projects is captured as an immutable `Target` snapshot from a
`rootProject.getGradle().projectsEvaluated(...)` callback, not read from inside `doLast`. Preserve
this pattern in new tasks — reading `project.*` from inside a `doLast` breaks the configuration
cache.

Git operations (`git tag`, `git push`) happen via a real `git` subprocess in `ReleaseGitOps`
(execution-time only, so shelling out is fine there) rather than JGit, unlike the read-only
history walking in `VersionResolver`.

### Self-installing GitHub Actions workflows

`installReleaseWorkflows`/`checkReleaseWorkflows` install/verify `release-candidate.yml` and
`promote-release.yml` (bundled as resources under
`src/main/resources/.../releaseflow/workflows/`) into `.github/workflows/`. Every installed file's
first line is a `ManagedFileMarker` (`# duckasteroid-managed: <componentId> <version>
sha256:<hash-of-body-below>`) — `ManagedFileInstaller` writes it, `ManagedFileChecker` re-parses it
to report missing/foreign/tampered/stale/up-to-date without ever failing the build. The hash covers
only the body below the marker line, so hand-editing the templated body (without touching the
marker) is detectable but not a checksum against deliberate tampering.

`build.gradle` stamps the actual plugin version into the bundled `workflow-version.txt` resource
via a `project.afterEvaluate` + scoped `filesMatching(...)` on `processResources` (deliberately not
a blanket `expand()` on all resources, since the workflow YAML templates contain live `${{
github.token }}`-style GitHub Actions expressions that must pass through byte-for-byte).

### Test layout

Three source sets: `src/main`, `src/test` (unit tests, JUnit 5, pure logic + `ProjectBuilder`),
`src/functionalTest` (`GradleRunner`-based end-to-end tests, each spinning up a real temp Gradle
build — this is where configuration-cache compatibility, multi-module scenarios, and the installed
workflow files actually get exercised).

## Conventions

- This repo follows its own Conventional-Commits-driven versioning — commit messages matter beyond
  style: `feat`/`fix`/`perf`/`!`/`BREAKING CHANGE:` directly determine the next version, per
  `CommitAnalyzer`'s default rules described above.
- Extensive class/method-level doc comments throughout `VersionResolver`, `ReleaseFlowPlugin`, and
  `VersionPlugin` explain non-obvious *why* (config-cache constraints, JGit quirks, tag-fallback
  semantics) — read the relevant doc comment before modifying a method rather than re-deriving the
  reasoning from scratch.
