---
brindley: 1
title: Plans
summary: Planned work for the gradle-versioning plugins.
status: active
tags:
  trunk-based: Support a single-branch, trunk-based release flow alongside develop → release → main
---
# Plans

Planned work for the gradle-versioning plugins.

<!-- brindley:generated:begin — do not edit by hand; regenerate instead -->

### Active

| # | Initiative | Type | Status | Ready / blocked by | Open Qs | Owner |
|---|------------|------|--------|--------------------|---------|-------|
| 1 | [Promote a specific RC's commit, not HEAD](01-promote-a-specific-rc-s-commit-not-head.md) | feature | draft | — | 0 | — |
| 2 | [Make RC minting idempotent per commit](02-make-rc-minting-idempotent-per-commit.md) | bug | draft | — | 0 | — |
| 3 | [Detect version collisions against unreachable tags](03-detect-version-collisions-against-unreachable-tags.md) | bug | draft | — | 0 | — |
| 4 | [Trunk workflow templates and releaseFlow strategy switch](04-trunk-workflow-templates-and-releaseflow-strategy-switch.md) | feature | draft | ⛔ 1, 2, 3 | 0 | — |
| 5 | [autoPromote option for continuous release](05-autopromote-option-for-continuous-release.md) | feature | draft | ⛔ 4 | 0 | — |

### Dependencies

```mermaid
flowchart RL
  n1["✏️ 1 Promote a specific RC's commit, not HEAD"]
  n2["✏️ 2 Make RC minting idempotent per commit"]
  n3["✏️ 3 Detect version collisions against unreachable tags"]
  n4["✏️ 4 Trunk workflow templates and releaseFlow strategy switch"]
  n5["✏️ 5 autoPromote option for continuous release"]
  n4 --> n1
  n4 --> n2
  n4 --> n3
  n5 --> n4
  click n1 href "01-promote-a-specific-rc-s-commit-not-head.md"
  click n2 href "02-make-rc-minting-idempotent-per-commit.md"
  click n3 href "03-detect-version-collisions-against-unreachable-tags.md"
  click n4 href "04-trunk-workflow-templates-and-releaseflow-strategy-switch.md"
  click n5 href "05-autopromote-option-for-continuous-release.md"
```

### Themes

- **trunk-based** — Support a single-branch, trunk-based release flow alongside develop → release → main · 5 open, 0 closed or deferred

### Completed

_None._

<!-- brindley:generated:end -->
