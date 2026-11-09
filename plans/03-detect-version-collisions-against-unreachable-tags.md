---
type: bug
status: draft
tags: [trunk-based]
updated: 2026-11-09
---
# Detect version collisions against unreachable tags

## Goal

Candidate-version computation checks every existing tag (not only those reachable from HEAD) and either bumps past a taken version or fails with a clear message. Scenario: `v1.2.1` is shipped from a `release/1.2.x` hotfix branch; trunk can't see that tag, its next bump is a patch, so it computes 1.2.1 again and the tag push fails.

## Dependencies

_None._

## Open questions

_None._

## Acceptance criteria

_What must be true when this is done._
