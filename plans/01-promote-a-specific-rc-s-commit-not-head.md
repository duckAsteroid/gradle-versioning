---
type: feature
status: draft
tags: [trunk-based]
updated: 2026-11-09
---
# Promote a specific RC's commit, not HEAD

## Goal

`promoteReleaseCandidate` accepts `-Prelease.candidate=<rc tag>` (defaulting to the nearest reachable RC) and places the final `X.Y.Z` tag on that RC's commit rather than on HEAD; `changelogForRelease` ends its range at the same commit. Today the final tag goes on HEAD (ReleaseFlowPlugin.java:149 via ReleaseGitOps.createAndPushTag), which is only correct when HEAD *is* the accepted RC, as after a release→main merge. On a single trunk, HEAD has usually moved past the tested RC.

## Dependencies

_None._

## Open questions

_None._

## Acceptance criteria

_What must be true when this is done._
