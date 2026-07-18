---
title: Tour an existing Hotvect algorithm
description: Learn an unfamiliar algorithm repository, inspect its contract, and choose a safe first execution
tags: [getting-started, algorithm, workflow, beginner]
difficulty: beginner
prerequisites:
  - Hotvect installed and activated
  - Access to an algorithm repository
related_docs:
  - ../quickstart/index.md
  - ../../concepts/how-hotvect-works/index.md
  - ../local-development-env/index.md
---

# Tour an existing Hotvect algorithm

The fastest useful introduction to Hotvect is to trace one existing algorithm from its public contract to its
artifacts. This guide gives you that reading order and helps you choose a bounded first run.

You do not need training data to understand the repository. You do need algorithm data and, for most inference paths,
a compatible parameter ZIP before you can execute the full lifecycle.

## What you need

Start with:

- a local algorithm checkout or repository URL;
- the git reference you intend to inspect;
- an activated Hotvect installation;
- optionally, access to the algorithm's declared data and an existing parameter ZIP.

If `hv --version` does not work yet, complete [Install and verify Hotvect](../quickstart/index.md) first.

## 1. Find the outer algorithm definition

Algorithm definitions are root resources named `<algorithm-name>-algorithm-definition.json`. In the algorithm checkout:

```bash
rg --files | rg -- '-algorithm-definition\.json$'
```

There may be several definitions because one JAR can contain an outer algorithm and its children. Start with the name
used by the containing application or the repository's normal backtest command.

Read these fields first:

| Field | Question it answers |
| --- | --- |
| `algorithm_name`, `algorithm_version` | What artifact am I looking at? |
| `algorithm_factory_classname` | Which class constructs the public algorithm? |
| `dependencies` | Which child algorithms must be prepared and loaded? |
| Decoder, transformer/vectorizer, and encoder factory fields | How do raw examples become model inputs? |
| `training_command` | Does this algorithm train parameters? |
| `train_data_spec`, `test_data_spec`, `source_data` | Which offline data does it require? |
| `hotvect_execution_parameters` | Which stages and runtime controls are configured? |

Do not try to understand every field on the first pass. The goal is to identify the public factory, dependency graph,
and stages that can run.

## 2. Follow the factory into the implementation

Open the class named by `algorithm_factory_classname`. Determine:

1. Which public shape it returns: ranker, bulk scorer, TopK, or ThemedTopK. A `Scorer` is normally a child capability
   or custom-host contract. A definition with `generator_factory_classname` owns state generation rather than a public
   decision shape; the current Python lifecycle also requires it to declare `algorithm_factory_classname`.
2. Whether it is simple or composite.
3. Which parameters and child `AlgorithmInstance` objects it consumes.
4. Where the final ordering, selection, or scoring policy lives.

For a composite, follow each named dependency only far enough to identify its responsibility. A useful first sketch is:

```text
outer algorithm
  ├─ child A: transformation or state
  ├─ child B: model scoring
  └─ outer policy: final decision
```

Then find the decoder and transformer/vectorizer factories named in the definition. This connects the source-data
representation to the typed request and feature path.

## 3. Verify that the project builds

Use the algorithm repository's normal build command. Hotvect git-reference workflows also build the repository, but a
direct build usually gives the clearest compiler and test feedback.

After the build, identify the runtime JAR. If the project publishes a shaded JAR, Hotvect prefers the
`*-shaded.jar` artifact over a thin JAR. The selected JAR must contain the implementation classes and the definition
resource for the algorithm name you will run.

At this point you have validated source, definition, and packaging—even if data access is not yet available.

## 4. Inspect the offline dependency plan

`hv-ext show-data-dependency` builds the selected reference and resolves its declared data requirements without listing
or downloading objects from S3.

```bash
hv-ext show-data-dependency \
  --repo-url /path/to/algorithm-checkout \
  --git-reference <git-reference> \
  --scratch-dir /tmp/hotvect-dependency-plan \
  --last-test-time <test-date> \
  --output /tmp/hotvect-dependencies.json
```

`<test-date>` uses `YYYY-MM-DD` format, for example the deliberately synthetic date `2000-01-08`.

Inspect the output for the outer algorithm name, child algorithms, data prefixes, and calculated dates. Use the same
git reference, override, target, and test date for the later run; changing any of them can change the plan.

When you are ready to list or download actual data, continue with
[Prepare a local development environment](../local-development-env/index.md).

## 5. Choose the smallest honest first run

| What you want to learn | First operation | Required inputs |
| --- | --- | --- |
| Does the source and definition build coherently? | Repository build and `show-data-dependency` | Repository and Hotvect installation |
| What features does one request produce? | Ordered `hv audit` with a small sample | JAR, parameter ZIP, source rows |
| Does one built artifact produce predictions? | Bounded `hv predict` | JAR, parameter ZIP, source rows |
| Does a revision complete its whole offline lifecycle? | One-date local `hv backtest` | Repository, local data, scratch/output directories |
| How do two revisions compare? | Backtest both fixed git references | Same data contract and explicit comparison criteria |

Do not start with a broad backtest merely because it is the highest-level command. If you are changing feature logic,
an ordered audit is usually easier to interpret. If you are changing packaging or dependencies, a one-date backtest is
the more relevant first proof.

## 6. Read the result as an artifact chain

After a pipeline run, start with `result.json`. It records which stages ran, reused outputs, or were skipped. Then inspect
the stage that answers your question:

- `audit` output for transformed values;
- prediction part files for decisions and scores;
- evaluation metadata for quality metrics;
- performance-test metadata for latency and throughput;
- `hv.log` and `hv.all.log` for orchestration and dependency failures.

A successful process exit proves that the requested operation completed. It does not by itself prove model quality,
online/offline parity, or production performance.

## What you should understand after the tour

You should now be able to answer:

- What is the outer public algorithm?
- Which behavior belongs to the outer algorithm and which belongs to children?
- Which JAR and parameter artifact form one runnable instance?
- Which data and lifecycle stages are required for the change you want to make?
- Which smallest operation will give relevant evidence?

Continue with [Develop a Hotvect algorithm](../develop-algorithms/index.md) when you need to change the implementation,
or [Pipeline stages](../pipeline-stages/index.md) when you need to understand a full offline run. When the repository
and data are ready, [train one artifact](../local-train/index.md) or
[backtest fixed revisions](../local-backtest/index.md).
