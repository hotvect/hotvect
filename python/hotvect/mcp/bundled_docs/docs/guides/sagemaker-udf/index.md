---
title: Run a FlatMap UDF
description: Execute a custom line-oriented flatMap function locally and package an explicit remote payload when needed
tags: [udf, sagemaker, flatmap, data-processing, advanced]
difficulty: advanced
estimated_time: 30 minutes
prerequisites:
  - UDF JAR implementing FlatMapFunFactory
  - hotvect-offline-util JAR available
  - Test data available locally
  - SageMaker access (for remote execution)
related_docs:
  - ../develop-algorithms/index.md
  - ../../reference/cli/index.md
related_commands:
  - java -cp ... FlatMapFile
next_steps:
  - Implement a tested custom payload when remote processing is required
  - Create reusable UDF library
  - Integrate UDF results into training pipeline
---

# Run a FlatMap UDF

## What can you do with it?

Sometimes it's useful to run an arbitrary function over your data. For example, you might want to:

- compute debugging statistics
- calculate alternative rewards for offline evaluation
- generate derived datasets that reuse the same domain code as your algorithms

You can always do this with Spark, but if your logic is already in JVM code (and depends on the same domain artifacts as your algorithms), running it with Hotvect can be convenient.

## Prerequisite knowledge

Running a function on top of a list of records is called `map`: each input line produces one output line. If you want to skip lines or emit multiple output lines per input line, you need `flatMap`.

Hotvect provides a `flatmap` runner that reads input line-by-line (files in lexicographic order), applies your
function, and writes each returned byte buffer verbatim in order. It does not encode JSON or add line endings for you.

At a high level, your UDF has the shape:

- `Function<String, List<ByteBuffer>>` (Java), i.e. `input_line -> [serialized_output, ...]`

## How to develop your flatmap function

To run a flatMap function using Hotvect, implement:

- `com.hotvect.offlineutils.commandline.util.flatmap.FlatMapFunFactory`

The factory returns a `Function<String, List<ByteBuffer>>`. The factory receives an `Optional<JsonNode>`
hyperparameter, which you can use to configure your UDF at runtime.

If you want JSONL, serialize JSON and include the newline in the buffer yourself:

```java
import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.offlineutils.commandline.util.flatmap.FlatMapFunFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public final class JsonLineFactory implements FlatMapFunFactory {
    @Override
    public Function<String, List<ByteBuffer>> apply(Optional<JsonNode> hyperparameter) {
        return input -> List.of(StandardCharsets.UTF_8.encode("{\"input\":\"" + input + "\"}\\n"));
    }
}
```

Use a real JSON serializer for untrusted or structured values; the example only illustrates the byte-buffer and
delimiter contract.

## How to run your flatmap function

### Running it locally

Example:

```bash
java -cp ~/.m2/repository/com/hotvect/hotvect-offline-util/x.y.z/hotvect-offline-util-x.y.z-jar-with-dependencies.jar \
  com.hotvect.offlineutils.commandline.util.flatmap.FlatMapFile \
  --jars ./target/example-udf-1.0.0.jar \
  --flatmap-class org.example.udf.ExampleFlatMapFactory \
  --source /path/to/input/dt=2000-02-02 \
  --dest test-output.jsonl \
  --metadata-path test-output.metadata
```

Apart from the output, it will also write run metadata to `--metadata-path/metadata.json` (by default, `metadata/metadata.json`).


### Running it on SageMaker

Hotvect does not expose a dedicated `hv` command or managed runner for “UDF on SageMaker.” The available mechanism is
**script mode**: submit your own SageMaker training job whose `s3_uri_custom_jar` points to a ZIP payload with
root-level `custom.py`.

That payload owns the UDF JAR, its dependencies, the `FlatMapFile` invocation, input-channel handling, and output
upload. Hotvect only downloads the payload ZIP and calls `custom.py` with a hyperparameters JSON path. See
[Use a custom SageMaker payload](../sagemaker-upgrade-custom-py/index.md) for the exact contract.

Build and validate the UDF locally first. When you need remote execution, keep the job-definition and `custom.py`
implementation in the project that owns the UDF so its inputs, outputs, and failure behavior stay explicit.
