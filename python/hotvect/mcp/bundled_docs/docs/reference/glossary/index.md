---
title: Glossary
description: Concise definitions of the terms used throughout the Hotvect documentation
tags: [reference, glossary, concepts]
---

# Hotvect glossary

Use these definitions when a guide or log assumes Hotvect vocabulary.

| Term | Meaning |
| --- | --- |
| **Action** | One candidate payload an algorithm can score, rank, or select. |
| **Action ID** | Stable, nonblank identity of an action within one request. It is distinct from the action's list position. |
| **Algorithm** | The complete decision behavior exposed through a Hotvect public shape—not only a trained model. |
| **Algorithm definition** | JSON contract embedded in the algorithm package that declares identity, factories, dependencies, data, and workflow/runtime configuration. |
| **Algorithm ID** | Algorithm name plus algorithm version, formatted as `name@version`. |
| **Algorithm instance** | Resolved definition, optional parameter metadata, and instantiated `Algorithm` object held together at runtime. |
| **Algorithm package** | Versioned executable implementation, embedded definition, and packaged assets. Its current outer distribution format is a JVM JAR. |
| **Algorithm JAR** | The current physical algorithm-package format: a loadable JVM artifact containing implementation classes, assets, and one or more algorithm-definition resources. |
| **Algorithm runtime ID** | Full hyperparameter and parameter identity of one runnable algorithm instance. |
| **Assignment key** | Stable input used with slot and experiment configuration to deterministically select a variant. The containing application chooses which domain identifier to supply. |
| **Backtest** | Repetition of the offline lifecycle for fixed historical dates and/or git references so results can be compared. |
| **Binding** | A concrete object supplied for a named algorithm dependency, either loaded by Hotvect or provided by the host application. |
| **Bulk scorer** | Public shape that scores a batch of candidates while another component owns final ordering or policy. |
| **Child algorithm** | A named algorithm dependency used by another algorithm. Children retain their own definitions and parameter identities. |
| **Decision** | One algorithm output associated with an action, such as a ranked or selected item with an optional score. |
| **Decoder** | Boundary that converts a raw offline source representation into typed Hotvect examples. |
| **Definition override** | Partial JSON patch applied explicitly to an embedded algorithm definition for one load or workflow. |
| **Effective definition** | Embedded definition after the selected explicit override has been applied. |
| **EMS** | Experiment Management Service. The current Hotvect clients consume an external EMS control plane for slots, variants, experiments, and algorithm metadata. |
| **Encoder** | Boundary that serializes transformed examples into the format consumed by a training library. |
| **Example** | Offline package containing a request and its observed outcomes. |
| **Experiment** | Configuration that assigns part of a slot's shards and ramp-up to one or more runtime variants. |
| **Execution context** | Pair of workload mode (`REALTIME` or `BATCH`) and input semantic (`ONLINE` or `OFFLINE`) supplied to factories. |
| **Feature** | Named value produced by transformation code for model input or downstream computation. |
| **Hyperparameters** | Pre-training choices that affect how parameters are produced. An optional hyperparameter version participates in artifact identity. |
| **Local-state storage** | Runtime capability that allocates an opaque private filesystem directory for an algorithm instance. It is separate from offline generated state and from a parameter package; the algorithm owns cleanup. |
| **Outer algorithm** | Public entrypoint targeted by a caller or top-level workflow. It can compose child algorithms. |
| **Outcome** | Observation associated with a past decision, used for training or evaluation. |
| **Parameter ID** | Identity recorded inside a parameter artifact for one training or state-generation result. |
| **Parameter package** | Versioned trained or generated inference state kept separate from the algorithm package. Its current distribution format is a ZIP. |
| **Parameter version** | Offline workflow name for one parameter-producing run, commonly derived from its test-date anchor; it becomes the packaged parameter identity. |
| **Parameter ZIP** | The current physical parameter-package format: model files, generated state, metadata, and any included child artifacts needed for inference. |
| **Parameters** | Learned or generated inference-time values such as model weights, trees, lookup data, or thresholds. |
| **Public shape** | Caller-facing decision interface: ranker, scorer, bulk scorer, TopK, or ThemedTopK. |
| **Ranker** | Public shape that receives candidate actions and returns them as ordered decisions. |
| **Reward** | Numeric training/evaluation signal derived from an observed outcome. |
| **Reward function** | Algorithm-owned mapping from an offline outcome to its numeric reward. |
| **Shared value** | Request-level payload or derived value reused across candidate actions. |
| **Slot** | Named runtime-selection domain with a default variant, active experiments, shard configuration, and forced assignments. |
| **State generation** | Offline workflow role that materializes and optionally packages lookup data, aggregates, or other state. |
| **TopK** | Algorithm shape that owns selecting up to `k` actions; unlike ranking, its request does not carry a candidate list. |
| **ThemedTopK** | TopK shape whose response also carries an action-list ID and string metadata for the selected list. |
| **Transformer** | Algorithm-side component that produces a namespaced record of typed feature values for each action. Generated ranking transformers are the recommended path for feature-rich algorithms. |
| **Variant** | Experiment or default choice that references a released algorithm and parameter identity plus assignment metadata. |
| **Vectorizer** | Lower-level algorithm-side component that produces a fixed-order numerical `SparseVector` for each action. |
| **Workload mode** | Runtime behavior expected for latency-sensitive (`REALTIME`) or throughput-oriented (`BATCH`) execution. |

!!! note "Parameters versus `algorithm_parameters`"
    The JSON field `algorithm_parameters` contains configuration passed to an algorithm factory, including runtime or
    backend settings. Despite its name, it is not the trained parameter artifact. Trained or generated inference values
    live in the parameter package.

For the end-to-end relationship between these terms, read [How Hotvect works](../../concepts/how-hotvect-works/index.md).
