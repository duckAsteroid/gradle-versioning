---
description: "Computes project.version from git tags plus Conventional Commits messages, with per-module tag scoping for monorepos. Applies axion-release internally and requires no other plugin."
---

# Conventional Commits Versioning Plugin

Plugin ID: `io.github.duckasteroid.version`

```groovy
plugins {
    id 'io.github.duckasteroid.version'
}
```

Computes `project.version` by finding the last *final* release tag (plain `vX.Y.Z`, no suffix)
reachable from `HEAD`, then bumping it according to the Conventional Commits messages since that
tag that touched this module's own directory. Requires no upstream plugin for any language or
toolchain - it applies [axion-release](https://github.com/allegro/axion-release-plugin) itself
internally (for its tag-prefix scheme and the `-Prelease.forceVersion` backstop) and nothing else,
so it works standalone on any Gradle project.

## Bump rules

- `feat` → minor, `fix`/`perf` → patch, `docs`/`style`/`refactor`/`test`/`chore`/`build`/`ci` → no
  bump.
- A `!` marker after the type/scope, or a `BREAKING CHANGE:` footer, always forces a major bump
  regardless of type.
- A commit that doesn't conform to Conventional Commits at all (or uses a type outside every
  configured set) bumps patch, with a warning on stderr - never silently ignored.
- When several qualifying commits disagree, the highest-severity bump wins.
- `HEAD` not sitting exactly on a release tag decorates the version with `-SNAPSHOT` (optionally
  with the sanitized branch name folded in on feature branches).

## Tag scheme

`{gradle project path}/vX.Y.Z`, collapsing to plain `vX.Y.Z` at the root project - so each
subproject in a monorepo can be versioned and released independently. A subproject with no tag of
its own yet falls back to the root's tag line, so a brand-new module inherits the current version
instead of starting over at `0.0.0`.

## Configuring which commit types bump what

```groovy
commitAnalyzer {
    minorTypes.add('perf2')     // append onto the default minor-bump types
    majorTypes.add('security')  // always treat "security: ..." commits as breaking
    noBumpTypes.set(['docs'])   // REPLACE the default no-bump set entirely
}
```

`.add(...)`/`.addAll(...)` appends to the defaults; `.set(...)` replaces them outright.

## Forcing a version

`-Prelease.forceVersion=X.Y.Z` bypasses all of the above and uses the underlying axion-release tag
prefix scheme's own native handling verbatim.

## Pairs with

`io.github.duckasteroid.release-flow` reuses this plugin's tag-prefix computation for RC tagging,
promotion, and changelog generation - apply both together for a full develop/release/main release
flow.
