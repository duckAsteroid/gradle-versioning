---
type: feature
status: draft
tags: [trunk-based]
updated: 2026-11-09
---
# Trunk workflow templates and releaseFlow strategy switch

## Goal

Add `releaseFlow { strategy = THREE_BRANCH | TRUNK; trunkBranch = 'main' }`. Under TRUNK, `installReleaseWorkflows` installs `trunk-release-candidate.yml` (push to trunk, with a `concurrency` group so tag-minting runs serialise) and `trunk-promote.yml` (`workflow_dispatch` with an `rc_tag` input, optionally gated by a GitHub Environment, checking out the RC tag before assemble/publish). Each uses its own ManagedFileMarker componentId so `checkReleaseWorkflows` flags a leftover three-branch file. `explainVersion` reports which RC would be promoted and how far trunk has moved past it. Consider also installing a PR-title Conventional Commits lint workflow, since squash-merge titles drive the bump.

## Dependencies

- [1 Promote a specific RC's commit, not HEAD](01-promote-a-specific-rc-s-commit-not-head.md) — trunk mode relies on this behaviour being in place
- [2 Make RC minting idempotent per commit](02-make-rc-minting-idempotent-per-commit.md) — trunk mode relies on this behaviour being in place
- [3 Detect version collisions against unreachable tags](03-detect-version-collisions-against-unreachable-tags.md) — trunk mode relies on this behaviour being in place

## Open questions

_None._

## Acceptance criteria

_What must be true when this is done._
