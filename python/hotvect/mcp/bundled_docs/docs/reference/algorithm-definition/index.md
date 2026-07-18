---
title: Algorithm definitions
description: Define an algorithm's identity, factories, data, composition, and lifecycle configuration
tags: [reference, algorithm-definition, factories, overrides]
related_docs:
  - ../../concepts/artifacts-and-identity/index.md
  - ../../concepts/dependencies-and-bindings/index.md
  - ../../guides/first-algorithm/index.md
  - ../generated-transformer-backends/index.md
---

# Algorithm definitions

An algorithm definition is the JSON contract that tells Hotvect what is in an algorithm JAR and how to construct and
run it. It names the algorithm, points to Java factory classes, declares child algorithms and data inputs, and configures
the offline lifecycle.

The definition is configuration, not executable code and not a trained model. Keep these four things separate:

| Item | Contains | Typical lifetime |
|---|---|---|
| Algorithm definition | Identity, factory class names, data declarations, workflow settings | Versioned with the algorithm source |
| Algorithm JAR | Java classes plus the embedded definition resource | Built once for an algorithm version |
| Parameter ZIP | Trained or generated files plus parameter metadata | Produced or selected for a particular run |
| Override fragment | A partial JSON patch applied to the embedded definition | Supplied for one environment or experiment |

For the complete artifact model, see [Artifacts and identity](../../concepts/artifacts-and-identity/index.md).

## File name and location

Put the definition in the algorithm project at:

```text
src/main/resources/<algorithm_name>-algorithm-definition.json
```

It must be packaged at the **root of the JAR resource namespace** with that exact file name. For example, an algorithm
named `example-document-ranker` is loaded from:

```text
example-document-ranker-algorithm-definition.json
```

Both the Java and Python loaders select the resource by the requested algorithm name. The `algorithm_name` inside the
JSON must match that requested name. A JAR may contain more than one definition, provided each has its own exact name.

The embedded resource is the normal source of truth. SageMaker execution can instead receive an already resolved
definition through `s3_uri_algorithm_definition`; that payload is still the same contract.

## A minimal definition

This is enough wiring for a parameterless `SimpleRankerFactory` called by the local algorithm server:

```json
{
  "algorithm_name": "example-document-ranker",
  "algorithm_version": "1.0.0",
  "decoder_factory_classname": "org.example.ranker.DocumentExampleDecoderFactory",
  "algorithm_factory_classname": "org.example.ranker.DocumentRankerFactory"
}
```

The decoder is needed because that surface receives serialized examples. An application that constructs typed Java
requests itself does not use the decoder, although keeping one is useful for offline parity tests. Other offline tasks
add the stage-specific factories they require; for example, current `predict` also constructs a reward function.

Every definition needs:

- `algorithm_name`
- `algorithm_version`
- at least one construction entry point: `algorithm_factory_classname` or `generator_factory_classname`

That last rule describes the Java definition reader. The current Python `AlgorithmPipeline` additionally requires
`algorithm_factory_classname`, including for a definition with `generator_factory_classname`. In a state-producing
component, the generator creates files offline; when that component is loaded as a runtime child, the algorithm factory
is the construction entry point for its packaged result.

There is no single list of factories required for every algorithm. Hotvect resolves a definition first and validates
stage-specific wiring when that stage runs. For example, `encode` needs decoder, transformer/vectorizer, reward, and
encoder factories; a simple algorithm embedded directly in Java may need only its algorithm factory.

## Factory fields

Factory classes are loaded with a no-argument constructor. Use a factory interface that matches the role and algorithm
shape.

| Field | Factory contract | Used for | Definition/runtime inputs |
|---|---|---|---|
| `algorithm_factory_classname` | An `AlgorithmFactory` family such as `SimpleRankerFactory`, `RankerFactory`, or `CompositeAlgorithmFactory` | Constructs the callable algorithm | `algorithm_parameters`; parameter streams and child instances for factory forms that support them |
| `decoder_factory_classname` | `ExampleDecoderFactory` or a specialized ranking/TopK decoder factory | Decodes offline examples and local-server requests | `train_decoder_parameters` for encode/audit; `test_decoder_parameters` for predict/performance/local serve |
| `transformer_factory_classname` | A transformer factory such as `RankingTransformerFactory` or its composite form | Computes the dependency passed to a non-composite algorithm and to the encoder | `transformer_parameters`, parameter streams, and child instances for a composite factory |
| `vectorizer_factory_classname` | A vectorizer factory such as `RankingVectorizerFactory` or its composite form | Alternative feature-extraction dependency | `vectorizer_parameters`, parameter streams, and child instances for a composite factory |
| `reward_function_factory_classname` | `RewardFunctionFactory` | Converts outcomes for encode and formats batch prediction output | none |
| `encoder_factory_classname` | `ExampleEncoderFactory` or a specialized encoder factory | Serializes transformed training examples for the trainer | receives the feature dependency and reward function |
| `generator_factory_classname` | `StateGeneratorFactory` | Produces files during `generate-state` | receives the complete `AlgorithmDefinition` and algorithm class loader |

When both a vectorizer and transformer are declared, the Java loader selects the vectorizer. New definitions should
choose one so that the construction path is unambiguous.

The fields ending in `_parameters` above are optional JSON values from the definition. Despite the name, they are
construction-time configuration; they are not files from the trained parameter ZIP.

## Lifecycle and data fields

These fields control orchestration rather than Java class construction:

| Field | Meaning |
|---|---|
| `dependencies` | Named child algorithms, optionally with embedded definition overrides |
| `hyperparameter_version` | Optional stable label included in artifact paths and runtime identity for one configuration line |
| `hotvect_version` | Framework version provenance used by compatibility-sensitive lifecycle behavior and reports |
| `train_data_spec` | Training input declaration; `data_prefix` selects the local data directory and optional properties describe remote input |
| `test_data_spec` | Historical test input used by the evaluate target |
| `prediction_spec` | Explicit inference input and output used only by the predict target |
| `number_of_training_days` | Number of dated training partitions before the test anchor |
| `training_lag_days` | Gap between the test anchor and the newest training partition |
| `training_command` | Jinja2 template executed by the train stage; its presence makes the definition trainable |
| `source_data` | Named inputs for a state-producing definition |
| `state_output_filename` | Optional relative file or directory to package as generated state |
| `requires_local_state_storage` | Boolean declaration that the runtime must offer private writable filesystem storage while constructing this algorithm |
| `hotvect_execution_parameters` | Hotvect orchestration controls such as stage enablement, caching, JVM flags, and performance-test settings |
| `sagemaker_training_job_definition` | Algorithm-owned partial SageMaker training-job definition used for remote lifecycle execution |

Prefer `train_data_spec` and `test_data_spec` for current definitions. See
[Data dependencies](../../guides/patterns/data-dependencies/index.md) for date and location semantics,
[Artifacts and identity](../../concepts/artifacts-and-identity/index.md) for hyperparameter identity, and
[SageMaker configuration](../../design/sagemaker-configuration/index.md) for the remote job field.

## Definition parameters versus parameter artifacts

Several similarly named concepts cross the definition and runtime boundary:

| Concept | Where it lives | How code receives it |
|---|---|---|
| `algorithm_parameters` | Definition JSON | `Optional<JsonNode>` passed to the algorithm factory |
| `transformer_parameters` / `vectorizer_parameters` | Definition JSON | `Optional<JsonNode>` passed to the selected feature factory |
| `train_decoder_parameters` / `test_decoder_parameters` | Definition JSON | `Optional<JsonNode>` passed to the decoder factory |
| Trained/generated parameters | Parameter ZIP under an algorithm-name directory | `Map<String, InputStream>` passed to parameterized factories |
| `algorithm-parameters.json` | Parameter ZIP under the algorithm-name directory | Read by Hotvect as artifact identity and provenance metadata |

Changing definition JSON does not mutate an existing parameter ZIP. Supplying a parameter ZIP also does not activate
the override that produced it; the runtime definition comes from the JAR plus the override explicitly applied for that
load or run.

## Runtime-local state storage

Set `requires_local_state_storage` to `true` only when the constructed algorithm must materialize runtime files outside
its parameter ZIP—for example, when a library requires a filesystem path rather than an input stream:

```json
{
  "algorithm_name": "example-file-backed-model",
  "algorithm_version": "1.0.0",
  "requires_local_state_storage": true,
  "algorithm_factory_classname": "org.example.FileBackedModelFactory"
}
```

The value must be a JSON boolean. Missing or `false` means the algorithm does not require this capability. When it is
`true`, a containing runtime must configure a local-state root and the factory must require the supplied
`LocalStateStorage`. Repository loading enforces the declaration before it downloads the parameter package; direct
`AlgorithmInstanceFactory` loading passes `Optional.empty()` when no root was configured, so the algorithm factory must
reject that missing capability itself. Calling `allocateDirectory()` returns an existing, empty, private, absolute
directory. The runtime owns its name and layout, so algorithm code must treat the path as opaque.

The allocated directory is runtime state, not generated offline state and not part of the parameter package. Ownership
transfers to the factory or constructed algorithm: remove it if construction fails, or remove it from
`Algorithm.close()` after successful construction. A runtime may delay cleanup until an old instance is no longer
reachable so in-flight requests can finish.

The current `hv serve` implementations do not configure a local-state root. Use a containing application integration
that supplies one when testing or running a definition with this requirement.

## Dependencies

Use an array when children need no embedded overrides:

```json
{
  "dependencies": [
    "example-scorer",
    "example-rules"
  ]
}
```

Use an object when a child needs a definition patch:

```json
{
  "dependencies": {
    "example-scorer": {
      "algorithm_parameters": {
        "threshold": 0.25
      }
    },
    "example-rules": {}
  }
}
```

Object keys are child algorithm names and every value must be an object. The Python lifecycle recursively prepares
children before the parent. At runtime, Hotvect resolves the same names into `AlgorithmInstance` values passed to a
composite factory. Dependencies must form an acyclic graph; indirect cycles recurse during preparation or loading
rather than representing a valid composition.

## Override semantics

An override file is a fragment, not a second complete definition. Its merge rules are:

- objects merge recursively;
- scalar and array values replace the base value;
- `null` deletes an ordinary field;
- `algorithm_name` and `algorithm_version` cannot be changed or deleted;
- `dependencies` must be an object keyed by a child already declared by the base definition;
- overriding one child preserves its siblings.

For example:

```json
{
  "training_lag_days": 2,
  "dependencies": {
    "example-scorer": {
      "algorithm_parameters": {
        "threshold": 0.4
      }
    }
  }
}
```

See [Override files](../../guides/patterns/override-files/index.md) for command examples.

## `transformer_parameters.features`

When an algorithm uses `@GenerateSimpleRankingTransformer`, the annotation processor reads
`transformer_parameters.features` from the resource named by the annotation's `algorithmDefinitionResource`. This
array is the compile-time output contract for the generated transformer.

Each entry is either:

- a string, which uses that feature name and infers its type from the Java return type; or
- an object with `name` and optional `type`, where the selected backend interprets the explicit type.

```json
{
  "transformer_parameters": {
    "features": [
      "document_kind",
      "numeric_signal",
      { "name": "dense_vector", "type": "float32[8]" }
    ]
  }
}
```

Important boundaries:

- Select the generated backend on `@GenerateSimpleRankingTransformer`, not in this array.
- `features[].type` is backend-specific.
- `algorithm_parameters.backend` selects a Python worker backend; it does not select the generated Java namespace.
- Feature order is preserved. Duplicate names produce compiler warnings and the first occurrence wins.

See [Generated transformer backends](../generated-transformer-backends/index.md) for the exact backend type matrices.

## `prediction_spec`

Use `prediction_spec` when `hv train --target predict` should run inference on a dataset other than the historical test
slice.

```json
{
  "prediction_spec": {
    "data_prefix": "example_prediction_input",
    "number_of_days": 1,
    "lag_days": 0,
    "s3_uri": {
      "production": "s3://example-bucket-input/tables/example_prediction_input/",
      "staging": "s3://example-bucket-staging-input/tables/example_prediction_input/"
    },
    "output_uri": {
      "production": "s3://example-bucket-output/predictions/{{ hyperparameter_slug }}/{{ parameter_version }}/dt={{ last_test_date }}/",
      "staging": "s3://example-bucket-staging-output/predictions/{{ hyperparameter_slug }}/{{ parameter_version }}/dt={{ last_test_date }}/"
    }
  }
}
```

Meaning:

- `data_prefix`, `number_of_days`, and `lag_days` select the prediction input partitions;
- `s3_uri` optionally declares environment-specific remote input;
- `output_uri` is the required final destination for the prediction artifact.

The two inference targets remain distinct:

- `target=evaluate` reads `test_data_spec`, writes the normal run-local `prediction/` artifact, and can then evaluate
  and performance-test it;
- `target=predict` requires `prediction_spec`, publishes to `prediction_spec.output_uri`, and skips evaluation and
  performance testing.

Additional constraints:

- `output_uri` can be a string or an environment map.
- Environment maps require an exact key match with `data_environment`; there is no alias or fallback key.
- SageMaker predict execution requires an `s3://` output; local execution may use a filesystem path.
- An S3 output prefix must be empty before publication.
- Prediction rows may omit `reward` for unlabeled inference.
- Logs, metadata, and `result.json` remain under the normal Hotvect metadata root.

### `prediction_spec.output_uri` template variables

- `algorithm_name`
- `algorithm_version`
- `hyperparameter_slug`
- `parameter_version`
- `last_test_date`
- `last_test_time`
- `data_environment`
- `ran_at`

## `training_command`

Hotvect renders `training_command` as a Jinja2 template immediately before the train stage. Available variables are:

- `algorithm_definition_path`: effective definition written to the metadata directory;
- `algorithm_jar_path`: algorithm JAR being executed;
- `encoded_data_file_path`: encoded data directory;
- `encoded_schema_description_file_path`: schema description written by encode;
- `parameter_output_path`: location where the trainer must write its model files;
- `scratch_dir`: scratch directory for the command;
- `python_executable`: Python executable used by Hotvect;
- `encode_parameter_path`: encode-parameters ZIP, including packaged child artifacts.

```json
{
  "training_command": "{{ python_executable }} -m example_trainer --input {{ encoded_data_file_path }} --output {{ parameter_output_path }}"
}
```

## `hotvect_execution_parameters`

This object controls the pipeline around the algorithm. Common fields include:

- `predict.enabled` and `evaluate.enabled`;
- `performance-test.enabled`;
- `performance-test.workload_mode` (`realtime` or `batch`);
- `cache_base_dir`;
- root and per-stage `cache` settings;
- `cache_scope` (`major`, `minor`, `patch`, or `hyperparam`);
- `cache_refresh`;
- `jvm_args`.

See [Caching](../../guides/caching/index.md) for the complete cache modes and reuse boundaries.

### Evaluation function

Select a registered evaluation function by name:

```json
{
  "hotvect_execution_parameters": {
    "evaluation_function": "standard_evaluation"
  }
}
```

Or pass keyword arguments:

```json
{
  "hotvect_execution_parameters": {
    "evaluation_function": {
      "name": "standard_evaluation",
      "arguments": {
        "missing_reward_policy": "zero"
      }
    }
  }
}
```

`name` must exist in Hotvect's evaluation-function registry, and `arguments` must be an object.

### JVM arguments

Declare flags globally or for a task:

```json
{
  "hotvect_execution_parameters": {
    "jvm_args": ["-Xmx2g", "-XX:+ExitOnOutOfMemoryError"],
    "predict": {
      "jvm_args": ["-Xmx4g"]
    }
  }
}
```

Resolution order is:

1. `hotvect_execution_parameters.<task>.jvm_args`
2. `hotvect_execution_parameters.jvm_args`
3. CLI or pipeline-context JVM arguments

Classpath flags (`-cp` and `-classpath`) are rejected. Configure exactly one heap cap style: `-Xmx...` or
`-XX:MaxRAMPercentage=...`. If neither is present, Hotvect uses `-XX:MaxRAMPercentage=80`.

### State output and packaging

A definition with `generator_factory_classname` follows the state-producing lifecycle. Generated files are packaged
into the predict-parameters ZIP by default.

```json
{
  "state_output_filename": "state/id-mapping.json",
  "hotvect_execution_parameters": {
    "package_state_as_predict_parameters": true
  }
}
```

- If `state_output_filename` is omitted, the algorithm output directory is the state root.
- A configured path must be relative, remain below the output root, and exist when packaging begins.
- A directory is packaged recursively.
- A configured primary file is packaged with same-stem sidecars when present.
- `package_state_as_predict_parameters` defaults to `true`. Setting it to `false` suppresses the standalone ZIP but
  still writes parameter metadata for parent packaging.

### Performance-test controls

The performance-test block can pin the request count, decoded sample pool, and workload mode:

```json
{
  "hotvect_execution_parameters": {
    "performance-test": {
      "enabled": true,
      "samples": 1000,
      "sample_pool_size": 64,
      "workload_mode": "realtime"
    }
  }
}
```

- `samples` is the number of requests in each measured repeat.
- `sample_pool_size` is the number of decoded requests retained for reuse.
- `workload_mode` defaults to `realtime` for `hv performance-test`; `batch` explicitly benchmarks the batch path.
- A CLI `--workload-mode` value takes precedence.

## `algorithm_parameters` for Python workers

Algorithms backed by a Python worker keep backend selection at the top level and runtime transport under the relevant
workload scope:

```json
{
  "algorithm_parameters": {
    "backend": "tensorflow",
    "realtime": {
      "litserve": {
        "python_executable": "/opt/example-venv/bin/python",
        "accelerator": "cuda",
        "devices": "auto",
        "workers_per_device": 1,
        "request_timeout_ms": 30000
      }
    },
    "batch": {
      "direct_workers": {
        "python_executable": "/opt/example-venv/bin/python",
        "accelerator": "cpu",
        "devices": "auto",
        "workers_per_device": 1,
        "startup_timeout_ms": 60000
      }
    }
  }
}
```

When `hv worker serve` launches LitServe, Python interpreter precedence is:

1. `HOTVECT_PYTHON_EXECUTABLE`
2. the selected `litserve.python_executable`
3. `sys.executable`

For JVM-managed direct workers, `DirectWorkersConfig` does not resolve an interpreter. The algorithm integration must
construct `PythonWorkerCommand` with an explicit executable and worker module; a `python_executable` value inside
`direct_workers` affects that path only when the algorithm code reads it.

See [Direct Python workers](../../design/direct-python-workers/index.md) for the runtime contract.
