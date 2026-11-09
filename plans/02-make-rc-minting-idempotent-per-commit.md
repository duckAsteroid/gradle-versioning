---
type: bug
status: draft
tags: [trunk-based]
updated: 2026-11-09
---
# Make RC minting idempotent per commit

## Goal

`tagReleaseCandidate` skips (with a log line) when HEAD already carries an RC tag for this module, instead of minting RCn+1 on the same commit. `VersionResolver.nextReleaseCandidateTag` deliberately advances the counter even against the same commit (VersionResolver.java:218); a CI re-run therefore produces a duplicate RC. Rare on a merge-only release branch, routine on trunk.

## Dependencies

_None._

## Open questions

_None._

## Acceptance criteria

_What must be true when this is done._
