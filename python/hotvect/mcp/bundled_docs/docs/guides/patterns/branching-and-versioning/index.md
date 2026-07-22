---
title: Branching and versioning algorithm repositories
description: A release-line convention for algorithm repositories that maintain several active variants
tags: [patterns, branching, versioning, releases, algorithms]
difficulty: intermediate
related_docs:
  - ../../develop-algorithms/index.md
  - ../../../reference/version-compatibility/index.md
related_commands:
  - git worktree add
  - hv backtest
---

# Branching and versioning algorithm repositories

Hotvect does not enforce a Git branching model. This page describes a useful convention for algorithm repositories
that maintain several independently active release lines. Follow a repository's own contribution and release rules
when they differ.

## Keep three versions distinct

1. **Algorithm version** — identity embedded in the algorithm definition and artifacts.
2. **Repository release line** — branch convention used by that algorithm repository.
3. **Hotvect version** — framework/runtime dependency used to build or run the algorithm.

Changing the local Hotvect CLI does not require a new algorithm major. A repository may use algorithm majors as
operational release lines even though Hotvect itself uses majors for framework compatibility.

## When multiple release lines help

Use independent release branches when:

- several algorithm variants remain active at once;
- experiments need reproducible fixes without absorbing unrelated work;
- a new workstream must start from a behaviorally appropriate release rather than the latest branch;
- a repository's release automation explicitly supports those branches.

If the repository has one linear release path, a conventional mainline workflow is simpler.

## Tags and artifact identity

Git tags identify repository commits. Algorithm versions identify built artifacts. Keep them aligned according to the
repository's release automation, but do not treat them as the same namespace.

One repository has one Git tag namespace even when it builds several algorithm artifacts. Before claiming a version,
inspect repository-wide tags and release branches.

## Start a release line

### 1. Identify the behavioral baseline

Use source history, released artifacts, and the containing application's selected algorithm metadata to identify the
behavior currently in use.

The numerically largest version is not automatically the right baseline. Choose the release that already has the
request contract, dependency shape, and behavior the new work should preserve.

### 2. Check repository availability

Inspect remote branches and tags before choosing a new release-line name or algorithm major. Coordinate the choice
through the repository's normal ownership process; a local Git branch does not make a name reserved or protected.

### 3. Create a worktree and branch

For a repository that uses named release branches:

```bash
git worktree add ../../worktrees/example-algorithm-release-2 \
  -b release/2.x v1.9.0
```

Then configure remote branch protection and release automation through the repository's supported mechanism. Hotvect
does not do that step.

### 4. Bump the algorithm version

Update every location the algorithm repository requires and run its version/build checks. Whether the branch starts at
`2.0.0` or a snapshot form is a repository policy, not a Hotvect requirement.

### 5. Develop on short-lived branches

```bash
git worktree add ../../worktrees/example-algorithm-feature-x \
  -b feature/example-algorithm-x release/2.x
```

Merge through the repository's normal review process. Do not assume that merging a branch deploys it; deployment is a
separate CI/CD contract.

## Propagate changes explicitly

Independent release lines do not inherit from each other. When a fix belongs in several active lines, merge or
cherry-pick it deliberately and validate each target line.

Before propagating, ask whether the code is truly algorithm-specific. Stable DTOs, schema models, and reusable domain
or feature utilities often belong in a shared library with one lineage. Avoid leaving temporary copies in several
algorithm branches once the shared contract is established.

## Reproducibility checklist

- Record the baseline tag or commit.
- Record the algorithm and Hotvect versions separately.
- Use an unambiguous repository-level tag for a released commit.
- Backtest the exact ref that will be released.
- Confirm the active online version from the authoritative deployment/experiment system.
- Propagate cross-line fixes explicitly.
- Follow repository-specific branch protection and release rules.

Next, use [Overrides](../override-files/index.md) for temporary definition changes or
[Develop an algorithm](../../develop-algorithms/index.md) for the full artifact loop.
