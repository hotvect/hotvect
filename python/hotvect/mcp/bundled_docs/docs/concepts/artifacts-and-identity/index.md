---
title: Artifacts and identity
description: How Hotvect identifies algorithm code, configuration, parameters, runtime instances, and workflow outputs
tags: [concepts, artifacts, identity, versions, parameters]
---

# Artifacts and identity

Hotvect separates algorithm code, effective configuration, and trained parameters so each can change under an explicit
identity. Comparisons and integrations should name the exact combination they exercised.

## Runtime artifacts

| Artifact | Contains | Primary identity |
| --- | --- | --- |
| Algorithm package (JAR today) | Algorithm classes, factories, backend modules, embedded definition, packaged assets | Algorithm name and version |
| Algorithm definition | Factories, children, configuration, workflow settings, optional hyperparameter version | Algorithm and hyperparameter identity |
| Parameter package (ZIP today) | Model files, generated state, parameter metadata, child artifacts where packaged | Parameter ID |
| Runtime instance | Resolved definition, parameter metadata, instantiated algorithm | Full algorithm runtime ID |

The definition is normally embedded in the algorithm package and may be patched with an explicit override. An override
changes the effective configuration used by a run; it does not rewrite the source package.

A parameter package does not activate the definition override that produced it. The runtime reads its effective
definition from the algorithm package plus any explicitly supplied override; from `algorithm-parameters.json` it reads
parameter identity and run metadata. A rollout that depends on an override must therefore carry that override
explicitly or release an algorithm package whose embedded definition contains the intended configuration.

Runtime-local filesystem state is not another artifact. A factory may materialize files below a private directory
supplied by the containing runtime, but those files belong to that loaded instance and are removed when construction
fails or the algorithm closes. Reproducible model/state bytes still belong in the parameter package; use local-state
storage only for the runtime materialization a library needs.

## Identity hierarchy

Hotvect distinguishes four values:

| Value | Example form | Meaning |
| --- | --- | --- |
| Algorithm name | `example-ranker` | Stable logical name used by commands and dependency keys |
| Algorithm ID | `example-ranker@1.2.0` | Name plus algorithm version |
| Hyperparameter ID | `example-ranker@1.2.0-candidate-a` | Algorithm ID plus optional hyperparameter version |
| Algorithm runtime ID | `example-ranker@1.2.0-candidate-a@param-001` | Hyperparameter ID plus parameter ID |

Parameterless algorithms use `NA` as the parameter component of `AlgorithmRuntimeIdentity`.

Support for omitting the parameter package depends on the execution surface. Direct `AlgorithmInstanceFactory` use can
pass no parameter file. The current application `AlgorithmRepository` requires a nonempty parameter ID and downloads
a ZIP. The Python lifecycle normally packages a predict-parameters ZIP for every non-state pipeline, including a
parameterless algorithm.

Do not confuse the Hotvect framework version with the algorithm version. The framework version identifies the APIs,
runner, and Python tooling; the algorithm version identifies one algorithm-package line.

## Identity is an immutability contract

Treat published algorithm IDs and parameter IDs as immutable. `AlgorithmRepository` caches the algorithm-package factory by
algorithm ID and instances by algorithm ID plus parameter ID. Reusing an existing ID for different bytes does not
reliably replace the cached runtime and makes logs and comparisons ambiguous.

Publish new bytes under a new version or parameter ID. Do not use an identity as a mutable pointer.

## Composite ownership

Each child in a composite graph retains its own definition and parameter identity. The parent parameter package can
package child artifacts, but the child's ownership does not disappear. Inspect workflow `result.json` and runtime
metadata rather than assuming every file belongs to the outer algorithm.

## Lifecycle outputs

Offline workflows also produce operational artifacts such as encoded data, predictions, evaluation results, logs, and
submission manifests. Those outputs describe an operation over an algorithm runtime identity; they are not themselves
the deployable algorithm.

See [Pipeline stages](../../guides/pipeline-stages/index.md) for the concrete artifact chain and
[Version compatibility](../../reference/version-compatibility/index.md) before mixing framework, algorithm-package, or parameter-package
versions.

## Verification checklist

Before making a parity or regression claim, record:

- the selected algorithm package and effective definition;
- the parameter package or the fact that the algorithm is parameterless;
- outer and child runtime identities;
- the input/source representation;
- the execution context;
- the operation outputs and logs.
