---
title: Generate runtime state
description: Turn source files into a versioned state artifact that Hotvect can package and load with an algorithm
tags: [state, parameters, workflow, java]
difficulty: intermediate
prerequisites:
  - Completed Build your first algorithm
  - Hotvect installed from source
  - JDK 21 and Maven available
related_docs:
  - ../first-algorithm/index.md
  - ../pipeline-stages/index.md
  - ../../reference/algorithm-definition/index.md
  - ../../concepts/dependencies-and-bindings/index.md
---

# Generate runtime state

State generation turns raw files into a compact, versioned resource that an algorithm can load at runtime. Typical
outputs are a lookup table, an ID mapping, aggregate counts, or a directory of fixed assets.

Use it when the offline work is a deterministic data build rather than model fitting. Use the normal
encode–train lifecycle when a trainer learns parameters from labelled examples. State generation is also useful as a
child component: a parent algorithm can depend on the generated object without knowing how its files were built.

```text
named source paths
    → StateGenerator
    → generated file or directory
    → Hotvect parameter package
    → NonCompositeStateFactory
    → typed runtime object
```

The generator and runtime factory are two halves of one contract. The generator writes a format; the runtime factory
must read that exact format from the packaged parameters. The current parameter-package format is a ZIP.

## The definition fields

| Field | Role |
| --- | --- |
| `generator_factory_classname` | No-argument `StateGeneratorFactory` that creates the offline generator. |
| `algorithm_factory_classname` | Runtime factory for the packaged state. The managed Python pipeline currently requires this field; state components normally use `NonCompositeStateFactory`. |
| `source_data` | Named input channels. Each name becomes a key in the generator's `Map<String, List<File>>`. |
| `state_output_filename` | Relative file or directory below the algorithm output root that Hotvect passes as the generator destination and later packages. |
| `hotvect_execution_parameters.package_state_as_predict_parameters` | Whether to create a standalone parameter ZIP; defaults to `true`. |

For each `source_data` entry, `data_prefix` resolves below `--data-base-dir`. With no date fields, Hotvect passes that
directory to the generator. For date-partitioned data, set both `number_of_days` and `lag_days`; Hotvect resolves the
required partitions relative to `--last-test-time` and passes those paths instead.

Hotvect constructs the generator factory reflectively, then calls:

```java
StateGenerator getGenerator(AlgorithmDefinition definition, ClassLoader classLoader)
```

The resulting generator receives the named sources and exact destination. It owns input traversal and output format.
Return a non-null, mutable metadata map: the current task adds its standard metadata to that map before writing
`metadata.json`.

## Build a minimal state component

This synthetic component counts non-blank records. It is intentionally simple, but it exercises source resolution,
generation, packaging, and the runtime loading contract. The complete example was compiled and run through
`hv train --target parameters` with this Hotvect version.

Reuse the `pom.xml` from [Build your first algorithm](../first-algorithm/index.md) and create the source directories:

```bash
mkdir -p /tmp/hv-state-generation/src/main/java/org/example/state
mkdir -p /tmp/hv-state-generation/src/main/resources
cp /tmp/hv-first-algorithm/pom.xml /tmp/hv-state-generation/pom.xml
cd /tmp/hv-state-generation
```

Change its artifact ID; the existing `hotvect-api` and Jackson dependencies are sufficient:

```xml
<artifactId>record-count-state</artifactId>
```

Create `src/main/java/org/example/state/RecordCountState.java`:

```java
package org.example.state;

import com.hotvect.api.algorithms.Algorithm;

public record RecordCountState(long recordCount) implements Algorithm {}
```

Create `src/main/java/org/example/state/RecordCountStateGeneratorFactory.java`:

```java
package org.example.state;

import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.state.StateGenerator;
import com.hotvect.api.algodefinition.state.StateGeneratorFactory;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

public final class RecordCountStateGeneratorFactory implements StateGeneratorFactory {
    @Override
    public StateGenerator getGenerator(
            AlgorithmDefinition definition,
            ClassLoader classLoader) {
        return (sourceFiles, destination) -> {
            List<File> records = Objects.requireNonNull(
                    sourceFiles.get("records"),
                    "Missing records source channel");
            long recordCount = 0;
            try {
                for (File source : records) {
                    if (source.isDirectory()) {
                        try (Stream<Path> paths = Files.walk(source.toPath())) {
                            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                                recordCount += countNonBlankLines(path);
                            }
                        }
                    } else {
                        recordCount += countNonBlankLines(source.toPath());
                    }
                }
                Files.createDirectories(destination.toPath().getParent());
                Files.writeString(
                        destination.toPath(),
                        recordCount + System.lineSeparator(),
                        StandardCharsets.UTF_8);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("record_count", recordCount);
            return metadata;
        };
    }

    private static long countNonBlankLines(Path path) throws IOException {
        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            return lines.filter(line -> !line.isBlank()).count();
        }
    }
}
```

The generator handles directories explicitly because a non-partitioned `source_data` entry resolves to its root
directory. Sorting the paths makes the read order stable.

Create `src/main/java/org/example/state/RecordCountStateFactory.java`:

```java
package org.example.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.state.NonCompositeStateFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class RecordCountStateFactory
        implements NonCompositeStateFactory<RecordCountState> {
    @Override
    @SuppressWarnings("removal")
    public RecordCountState apply(
            Map<String, InputStream> parameters,
            Optional<JsonNode> configuration) {
        InputStream input = Objects.requireNonNull(
                parameters.get("state/record-count.txt"),
                "Missing state/record-count.txt");
        try (input) {
            String value = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
            return new RecordCountState(Long.parseLong(value));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
```

The parameter-map key is the generated path relative to this algorithm's directory inside the ZIP. The API's
preferred entry point is `create(...)`, but this release still requires implementations to provide the deprecated
`apply(...)` method.

Create `src/main/resources/example-record-count-state-algorithm-definition.json`:

```json
{
  "algorithm_name": "example-record-count-state",
  "algorithm_version": "1.0.0",
  "algorithm_factory_classname": "org.example.state.RecordCountStateFactory",
  "generator_factory_classname": "org.example.state.RecordCountStateGeneratorFactory",
  "state_output_filename": "state/record-count.txt",
  "source_data": {
    "records": {
      "data_prefix": "record-source"
    }
  }
}
```

The resource name must be `<algorithm_name>-algorithm-definition.json`.

## Generate and package it

Create neutral input data below the declared prefix:

```bash
mkdir -p data/record-source
printf 'alpha\nbeta\n\n' > data/record-source/records-a.txt
printf 'gamma\n' > data/record-source/records-b.txt
```

Build the algorithm package and request only the parameter package:

```bash
mvn package

hv train \
  --algorithm-name example-record-count-state \
  --algorithm-jar target/record-count-state-1.0.0.jar \
  --data-base-dir data \
  --output-base-dir output \
  --last-test-time 2000-01-02 \
  --target parameters
```

Inspect the package:

```bash
PARAMETERS="output/example-record-count-state@1.0.0/last_test_date_2000-01-02/example-record-count-state@1.0.0@last_test_date_2000-01-02.parameters.zip"
test -f "$PARAMETERS"
unzip -l "$PARAMETERS"
unzip -p "$PARAMETERS" \
  example-record-count-state/state/record-count.txt
```

The final command prints:

```text
3
```

Hotvect resolved `data/record-source`, passed it under the `records` channel, generated
`state/record-count.txt`, wrote generator metadata, and packaged both the state and `algorithm-parameters.json` under
the algorithm's directory in the ZIP. A state component skips encode, train, predict, evaluation, and performance
testing; its lifecycle result is the packaged state.

If `state_output_filename` names a directory, Hotvect packages its files recursively. If the field is omitted, the
algorithm output directory is the state root. Keep it explicit when possible so the generator and runtime factory
share an obvious path contract. Setting `package_state_as_predict_parameters` to `false` suppresses the standalone
ZIP; parent preparation can still include the child's generated files in the parent's artifact.

## Run only the generator

Use `hv generate-state` to test generation without source resolution or packaging:

```bash
hv generate-state \
  --algorithm-name example-record-count-state \
  --algorithm-jar target/record-count-state-1.0.0.jar \
  --source-path '{"records":["data/record-source/records-a.txt","data/record-source/records-b.txt"]}' \
  --dest-path scratch/state/record-count.txt \
  --metadata-path scratch/metadata
```

Here `--source-path` supplies the channel map directly and `--dest-path` replaces the managed destination. This
command writes the generator output and metadata only; it does not create a parameter ZIP.

## Next

- [Pipeline stages](../pipeline-stages/index.md) shows where state generation and parameter packaging sit in a full run.
- [Dependencies and bindings](../../concepts/dependencies-and-bindings/index.md) explains how a parent consumes a state component.
- [Algorithm definitions](../../reference/algorithm-definition/index.md) covers date-partitioned sources, caching, and packaging controls.
