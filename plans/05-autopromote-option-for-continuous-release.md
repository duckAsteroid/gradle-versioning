---
type: feature
status: draft
tags: [trunk-based]
updated: 2026-11-09
---
# autoPromote option for continuous release

## Goal

`releaseFlow { autoPromote = true }` makes every trunk push with bump-worthy commits mint a final `vX.Y.Z` directly (no RC), semantic-release style, via a `tagRelease`/`tagReleases` task pair: compute candidate, changelog since last final, tag HEAD.

## Dependencies

- [4 Trunk workflow templates and releaseFlow strategy switch](04-trunk-workflow-templates-and-releaseflow-strategy-switch.md) — builds on the TRUNK strategy and its workflow templates

## Open questions

_None._

## Acceptance criteria

_What must be true when this is done._
