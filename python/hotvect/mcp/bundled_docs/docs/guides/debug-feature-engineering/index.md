---
title: Debug feature engineering in an IDE
description: Step-by-step guide to debugging algorithm JARs in your IDE using hotvect-offline-util
tags: [debugging, feature-engineering, algorithms, ide, development]
difficulty: intermediate
estimated_time: 20 minutes
prerequisites:
  - Algorithm JAR built and available
  - IDE installed (IntelliJ IDEA recommended)
  - hotvect Python package installed
  - Test data available
related_docs:
  - ../develop-algorithms/index.md
  - ../feature-audits/index.md
  - ../../reference/cli/index.md
related_commands:
  - hv audit
  - hv encode
  - hv predict
next_steps:
  - Run feature audits to verify fixes
  - Compare algorithm versions
  - Add unit tests for edge cases
---

# Debug feature engineering in an IDE

Hotvect loads algorithm feature code dynamically from the JAR supplied through `--algorithm-jar`. To stop at a
breakpoint in that code, launch the Hotvect offline utility as the IDE main class and pass the algorithm JAR as a
program argument.

Use this only for failures that need an interactive debugger. For value comparison across builds, an ordered
[feature audit](../feature-audits/index.md) is faster and easier to preserve as evidence.

## Locate the offline utility JAR

The Python package bundles the offline utility JAR:

```python
>>> import hotvect.hotvectjar
>>> hotvect.hotvectjar.HOTVECT_JAR_PATH
PosixPath('/path/to/hotvect/hotvectjar/hotvect-offline-util-x.y.z-jar-with-dependencies.jar')
```

## Reproduce the task from Java

Use the same algorithm, source, parameters, and task that failed through `hv`:
```bash
java -XX:MaxRAMPercentage=80 -XX:+ExitOnOutOfMemoryError \
  -cp <path-to-hotvect-offline-util-jar> \
  com.hotvect.offlineutils.commandline.Main encode \
  --algorithm-jar <path-to-algorithm-jar>/my-algorithm-a.b.c.jar \
  --algorithm-definition <my-algorithm-name> \
  --metadata-path <metadata-dir> \
  --source <path-to-dataset> \
  --dest <output-directory> \
  --parameters <path-to-parameter-zip>
```

The parameters are as follows:
 - `--algorithm-jar`: The path to the algorithm jar
 - `--algorithm-definition`: The name of the algorithm. Hotvect uses this to locate the correct algorithm-definition.json to run.
 - `--metadata-path`: The directory where `metadata.json` and `hotvect-offline-utils.log` will be written.
- `encode`: Replace this with the failing task, such as `predict` or `audit`.
 - `--source`: The path to the data set on which the algorithm should be run. This can be a file or a directory.
 - `--dest`: The path to write the encoded (or predicted, or audited) data to.
 - `--parameters`: The path to the parameter zip package. Only necessary if the operation requires a parameter (like prediction with a ML model).

**Note**: If you run the same operation via the `hv` CLI wrapper (instead of invoking Java directly), hotvect also writes `hv.log` (Python CLI logs) and `stdout-stderr.log` (raw subprocess output) into `--metadata-path/`.

## Create the IDE run configuration

Open the algorithm project, use `com.hotvect.offlineutils.commandline.Main` as the main class, put the offline utility
JAR on the run configuration classpath, and use the arguments above as program arguments.

In Intellij, the steps are as follows:

 - First, open the algorithm project in Intellij. Then, create a new run configuration as follows:

 - Set the main class to `com.hotvect.offlineutils.commandline.Main` and edit the JVM arguments (like Xmx) as needed.

 - Set the program arguments to the arguments above.

 - Finally, set the classpath to include the hotvect-offline-util jar.

Now you can run and debug the algorithm jar as you would any other Java application.

The steps are intentionally shown as text so the documentation remains independent of a developer's local IDE paths and environment.
