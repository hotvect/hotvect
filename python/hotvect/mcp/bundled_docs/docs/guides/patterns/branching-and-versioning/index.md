---
title: Branching and Versioning Strategy
description: Recommended branching and versioning strategy for Hotvect algorithm repositories that release many concurrent algorithm lines
tags: [patterns, branching, versioning, releases, algorithms]
difficulty: intermediate
estimated_time: 15 minutes
prerequisites:
  - Familiarity with Git branches and tags
  - Basic understanding of Hotvect algorithm repositories
related_docs:
  - ../parent-child/index.md
  - ../override-files/index.md
  - ../../develop-algorithms/index.md
  - ../../../reference/faq/index.md
related_commands:
  - git worktree add
  - hv backtest
  - hv-exp
---

# Branching and Versioning Strategy

## Summary

If you only remember six things, remember these:

1. Hotvect algorithm repositories are designed for **concurrent development** and **reproducible releases**, not for a single linear deployment flow from `main`.
2. A protected branch like `v9`, `v10`, or `v83` is a **release line**. Different release lines can be active at the same time in different applications or environments.
3. In this model, a new **major version usually means a new workstream or release line**, not necessarily a breaking API change.
4. The usual flow is: find the right baseline, claim the next free major, create the protected `vN` branch, do work on feature branches, then merge back into `vN`.
5. Use **Git tags** for reproducibility and **`hv-exp`** to see which versions are actually active online.
6. If a change should naturally carry across many future release lines, it usually belongs in a **shared repository**, not in a single algorithm repository.

## Why This Model Exists

Many teams use a development model where one main branch is the single source of truth for releases. That model works well when releases are mostly linear.

Hotvect algorithm development often has a different shape:

- many algorithm ideas are developed in parallel
- several teams or agents may work on different algorithm threads at the same time
- backtests must be reproducible
- online deployments must be easy to trace back to an exact released version
- different applications, slots, or environments may use different algorithm versions at the same time
- older release lines sometimes need bug fixes even after newer lines exist

Because of that, a single release branch is often not enough. The recommended approach is to maintain **multiple protected version branches**, each representing its own release line.

## What Versions Mean in This Model

In many software projects, a major version primarily communicates API compatibility.

In Hotvect algorithm repositories, the meaning is usually more operational:

- a **major version** often identifies a new release line or development thread
- a **minor version** usually means feature work within the same release line
- a **patch version** usually means a fix within the same release line

This means that a jump from `55.x` to `56.x` or from `83.x` to `84.x` does **not** necessarily imply a breaking external API change. Often it simply means "this is a different algorithm line" or "this work belongs to a different ticket or stream."

## Why Tags Matter

Git tags are important for two reasons:

1. They make offline experiments and backtests reproducible.
2. They make it easy to understand exactly which released algorithm version was deployed.

This is one reason semantic versioning is still useful here, even though its meaning is more operational than API-centric. A version like `83.2.1` is much easier to reason about than a raw commit SHA when discussing deployments, experiments, and backtests.

## Why Release Branches Matter

The platform is designed so that deployment can happen from **multiple protected version branches**, not only from `main`.

Typical protected branches follow a pattern like:

- `v9`
- `v10`
- `v83`

Each such branch is a release line. In practice:

- one workstream may own one version branch
- another workstream may own another version branch
- both can progress independently
- both can have active deployments at the same time

This is why you may see very large major version numbers. That is expected in this model.

## Algorithms, Artifacts, and Repositories

Algorithm repositories often contain one or more algorithm artifacts.

In many cases:

- the **artifact name** communicates the algorithm type
- child algorithms and parent algorithms may each have their own artifact
- those artifacts can be composed through Hotvect dependencies

However, Git tags belong to the **repository**, not to the artifact name.

!!! warning
    If one repository contains multiple algorithm families, they still share one Git tag namespace. If `8.0.0` already exists anywhere in that repository, you cannot create another `8.0.0` for a different artifact in the same repository.

That is why version selection must be done at the repository level, not only at the artifact level.

## Recommended Procedure

### 1. Find the right baseline

Start by finding the best baseline for the next piece of work.

The most common baseline is:

- the latest default version currently active for the relevant application or slot

To find that, use **`hv-exp`**. This tells you what is actually live or default online, which is usually more important than simply looking at the numerically largest Git tag.

This is the normal question to ask first:

> Which released algorithm version is currently the default for the target application or slot?

Often, that is the best starting point for the next workstream.

That said, you should not blindly start from the highest version. Start from the release line that is behaviorally closest to what you want.

Good reasons to choose a particular baseline:

- it is the currently active default
- it already has the right request or shared type
- it already has the right serving contract
- it belongs to the correct algorithm family
- it already contains an earlier change you want to keep

### 2. Claim the next free major version

Once you know the baseline, choose the next free major version in the repository.

Think of this step as claiming a new release line for your workstream.

Before creating it, check:

- existing tags
- existing protected version branches
- whether someone else has already taken that major

If the major is free, create that release line. If it is taken, pick another free major.

### 3. Create the protected release branch

Create a protected branch like `vN` from the chosen baseline.

For example:

```bash
git worktree add ../../worktrees/my-algo-v84 -b v84 83.2.1
```

This means:

- baseline release: `83.2.1`
- new release line: `v84`

You can think of `v84` as the branch that owns the future `84.x.y` family.

### 4. Bump the version on that release line

After creating the release branch, bump the algorithm version to the new major.

Typical choices:

- `84.0.0`
- `84.0.0-SNAPSHOT`

Using `-SNAPSHOT` is optional. Some teams use it, some do not. The important part is that the new release line clearly owns the new major.

### 5. Do day-to-day work on feature branches

Do not treat the protected release branch as the place where everyday implementation happens.

Instead, branch from it:

```bash
git worktree add ../../worktrees/my-algo-feature-x -b codex/my-algo-feature-x v84
```

This keeps the protected branch stable while still allowing multiple developers or agents to work concurrently.

### 6. Merge back into the protected release branch

When the work is ready, merge it into the protected `vN` branch.

In repositories that release from protected version branches, that merge is the important operational event:

- it updates the release line
- it enables release or deployment for that major

## Important Consequence: There Is No Single Forward Path

When you use multiple active release lines, there is no single branch into which every change automatically flows.

That has one important consequence:

- if you need a fix or feature in another release line, you must merge or cherry-pick it explicitly

You cannot assume that because something landed in `v84`, it will automatically appear in `v85` or any other future line.

This can feel less convenient than a single-main-branch model, but it avoids a different problem: mixing multiple independently active algorithm lines into one evolving branch and then trying to reconstruct which change belongs to which release stream.

## How To Carry Changes Across Release Lines

The branching model above is good for independent algorithm workstreams, but it makes one thing more expensive:

- changes that are broadly useful across many algorithms or many future release lines

If you leave those changes inside one algorithm repository, you may end up repeatedly cherry-picking or reimplementing them in each new line.

The recommended solution is to move such concerns into a **shared repository** with a single main line.

### What usually belongs in a shared repository

Typical examples are:

- API contracts
- DTOs and shared request or response models
- schema-generated models
- common feature extraction utilities
- common lookup or enrichment utilities around stable domain concepts such as articles, customers, or orders
- reusable logic that multiple algorithms are expected to depend on

The key idea is simple:

- if a change should be carried forward almost everywhere, it should usually live in shared code with a single lineage
- if a change is specific to one algorithm experiment or one application, it should usually stay in the algorithm repository

### Why this helps

A shared repository gives you one place where cross-cutting changes evolve.

That means:

- shared changes naturally carry into future algorithm work through normal dependency updates
- algorithm release branches can stay focused on algorithm-specific behavior
- you reduce the need to manually propagate the same change across many algorithm release lines

In other words, the multi-release-branch model works best when algorithm repositories contain mostly algorithm-specific logic, while broadly reusable logic lives elsewhere.

### A common promotion workflow

In practice, reusable logic often starts life inside one algorithm repository.

That is normal. A common workflow is:

1. prototype the logic inside the algorithm repository
2. validate that it is useful and stable
3. move or reimplement it in the appropriate shared repository
4. switch the algorithm repository back to depending on the shared artifact

This promotion step is common and expected.

### Temporary local copies are sometimes fine

Sometimes you want to experiment on a shared-looking class without first making a release in the shared repository.

In that case, it can be reasonable to:

- copy the class locally into the algorithm repository
- modify it there while the design is still changing
- promote it into the shared repository later, once the shape is stable

The important thing is to treat that as a temporary step, not as the final home for broadly reusable logic.

### DTOs and schemas may be split or combined

Different teams organize shared artifacts differently.

For example:

- some teams keep DTOs together with other shared logic
- some teams generate DTOs from schemas and keep the generated models separate
- some teams split schema artifacts from utility artifacts

There is no single mandatory layout. The important design principle is simply to separate **cross-cutting shared concerns** from **algorithm-specific release lines**.

## How To Know What Is Active

Because there may be multiple valid release lines at the same time, Git alone is not enough to answer operational questions.

Use **`hv-exp`** when you need to know:

- which algorithm version is the current default for a slot or application
- which experiments are active
- which released version should be used as the starting point for new work

This is especially useful when there is no obvious "current branch" from a product point of view.

## Hotvect Versioning Is Separate

Do not confuse these three things:

1. **Algorithm version**
2. **Hotvect dependency version**
3. **Local Hotvect CLI version**

They are related, but they are not the same.

For example, all of the following can be true at once:

- your algorithm baseline was `7.3.11`
- your new release line is `v9`
- your artifact version is `9.0.0`
- your algorithm depends on Hotvect `9.30.0`
- your local `hv` or `hv-exp` tooling is running from Hotvect `10.x`

That is a normal situation.

A new algorithm major should usually be created because you want a new release line, not because you upgraded the Hotvect CLI on your machine.

## Recommended Decision Rules

Use these defaults unless your repository has a stronger local convention:

- choose the baseline by behavior, not by the largest number
- use `hv-exp` to find the operationally relevant current version
- treat a new major as a new release line
- pick the next free repository-level major
- create a protected `vN` branch for that line
- use feature branches or worktrees for day-to-day development
- merge into the protected `vN` branch to update that release line
- manually propagate fixes across release lines when needed
- move broadly reusable logic into shared repositories instead of repeatedly carrying it across algorithm release lines

## Example

Suppose:

 - the active default online version is `83.2.1`
 - you want to start a new workstream
 - no one has taken major `84` yet

The recommended flow is:

1. use `hv-exp` to confirm that `83.2.1` is the right operational baseline
2. create protected release branch `v84` from `83.2.1`
3. bump the algorithm version to `84.0.0`
4. do implementation on short-lived branches based on `v84`
5. merge into `v84` when you want the `84.x` line to move forward

If later another release line also needs the same fix, propagate it explicitly by merge or cherry-pick.

## Checklist

Before starting a new algorithm thread, check:

- What is currently active online for the target slot or application?
- Which released version is the best behavioral baseline?
- Which major versions are already taken in this repository?
- Which protected `vN` branch should own this workstream?
- Does the new major represent a new release line?
- Do any other active release lines need the same change later?
- Is this change actually cross-cutting enough that it belongs in a shared repository instead?
