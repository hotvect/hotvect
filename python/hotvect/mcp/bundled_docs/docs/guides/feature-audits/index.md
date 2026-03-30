---
title: How to Run and Compare Feature Audits
description: Generate human-readable feature outputs and compare algorithm versions
tags: [audit, debugging, comparison, features, testing]
difficulty: beginner
estimated_time: 10 minutes
prerequisites:
  - Algorithm JAR built and available
  - Test data available in expected directory structure
  - hotvect CLI installed
related_docs:
  - ../debug-feature-engineering/index.md
  - ../develop-algorithms/index.md
  - ../../reference/cli/index.md
related_commands:
  - hv audit
  - hv-ext compare-jsonl
next_steps:
  - Debug specific feature differences
  - Run full backtest comparison
  - Update algorithm based on findings
---

# How to: Run and compare feature audits

## What are feature audits?
Feature audits are a way to output feature values as readable json files. It outputs the same feature values as encoding, but in a human readable format. It is useful for debugging and comparing feature values between different algorithm versions.

For example, your algorithm might produce the following encoded training file:
```text
0.0\tITEM_FAKE_001\tFAKE_LABEL_A\t32\t452\t0.12
2.0\tITEM_FAKE_002\tFAKE_LABEL_A FAKE_LABEL_B\t3252\t12\t0.75
```
This file is not very readable. With the audit function, you can produce the same value in json format as follows:

```json
{
  "example_id": "00000000-0000-0000-0000-000000000000",
  "actions": [
    {
      "reward": 0.0,
      "features": {
        "feature_categorical_01": "ITEM_FAKE_001",
        "feature_categorical_02": [
          "FAKE_LABEL_A"
        ],
        "feature_numeric_01": 32,
        "feature_numeric_02": 452,
        "feature_numeric_03": 0.12
      }
    },
    {
      "reward": 2.0,
      "features": {
        "feature_categorical_01": "ITEM_FAKE_002",
        "feature_categorical_02": [
          "FAKE_LABEL_A",
          "FAKE_LABEL_B"
        ],
        "feature_numeric_01": 3252,
        "feature_numeric_02": 12,
        "feature_numeric_03": 0.75
      }
    }
  ]
}
```

## How to run feature audits
The easiest way to run feature audits is to use the `hv audit` CLI command:

```bash
hv audit \
  --algorithm-jar ~/.m2/repository/com/yourcompany/your-algorithm/1.2.3/your-algorithm-1.2.3.jar \
  --algorithm-name your-algorithm-click-model \
  --metadata-path ./audit.1.2.3.meta \
  --source-path training_data/dt=2024-01-06 \
  --dest-path audit.1.2.3.jsonl \
  --samples 2
```

The command will audit `--samples` from the source path. For reproducibility, keep `--source-path` and `--samples` fixed when comparing runs.

Artifacts are written under `--metadata-path/` (including `metadata.json` and `hotvect-offline-utils.log`). If you run audits via the `hv` CLI wrapper, hotvect also writes `hv.log` (Python logs) and `stdout-stderr.log` (raw subprocess output) there.

If your audit requires a parameters ZIP, pass it via `--parameter-path` (hv forwards it to Java as `--parameters`).


If you need to override the algorithm definition, or specify different JVM options, you can run the java command directly as follows:

```bash
java -cp ~/.m2/repository/com/hotvect/hotvect-offline-util/x.y.z/hotvect-offline-util-x.y.z-jar-with-dependencies.jar -XX:MaxRAMPercentage=80 -XX:+ExitOnOutOfMemoryError \
  com.hotvect.offlineutils.commandline.Main audit \
  --algorithm-jar ~/.m2/repository/com/yourcompany/your-algorithm/1.2.3/your-algorithm-1.2.3.jar \
  --algorithm-definition custom.algorithm.definition.json \
  --metadata-path audit_metadata.1.2.3.metadata \
  --source training_data \
  --dest audit.1.2.3.jsonl \
  --parameters encode.parameters.file.zip
```

## How to compare feature audits
To compare audit outputs from different algorithm versions, use `hv-ext compare-jsonl`:

```bash
hv-ext compare-jsonl audit.1.2.3.jsonl audit.1.2.4.jsonl -o ./audit-diff | jq .
```

If there are differences between the two audit files, the command prints a JSON summary and (for the **first** differing line) writes three files under `-o/--output`:

- `<file1_stem>.line=<n>.json`: the JSON from file1 (pretty printed)
- `<file2_stem>.line=<n>.json`: the JSON from file2 (pretty printed)
- `diff.<file1_stem>-<file2_stem>.line=<n>.json`: the diff (pretty printed)

If the files are identical, it prints `{ "message": "The two files are identical" }` and does not write diff files.

Note that the output is only generated for the first line that is different. Also, when the feature value is an array of integers or strings, the order of the elements in the array is not considered. They are considered equal if they have the same elements same number of times.


Example output:
```json
{
  "actions": [
    {
      "features": {
        "feature_numeric_01": {
          "audit.1.2.3": 5.0,
          "audit.1.2.4": 3.0
        },
        "feature_categorical_02": {
          "audit.1.2.3": [
            "FAKE_LABEL_A"
          ],
          "audit.1.2.4": [
            "FAKE_LABEL_B"
          ]
        }
      }
    }
  ]
}
```
