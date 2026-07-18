---
title: Run and compare feature audits
description: Generate human-readable feature outputs and compare algorithm versions
tags: [audit, debugging, comparison, features, testing]
difficulty: beginner
estimated_time: 10 minutes
prerequisites:
  - Algorithm JAR built and available
  - Predict-parameters ZIP for the algorithm
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

# Run and compare feature audits

Use an audit when you need to prove where two algorithm versions first diverge in feature engineering. It emits
human-readable JSONL for the same transformation path that encoding uses.

## Audit contract

| Inputs | Command | Artifacts | Verify |
| --- | --- | --- | --- |
| One algorithm JAR/name, predict-parameters ZIP, and source rows | `hv audit` | A destination directory containing `part-*.jsonl` and a metadata directory | Compare the matching part files with `hv-ext compare-jsonl` |

Audit output is a **directory**, never a single `.jsonl` file. For a deterministic, row-for-row comparison, pass
`--ordered` and compare `part-00000.jsonl` from each run.

## Output shape

Feature audits output transformed feature values as readable JSONL. Use them to locate the first transformation
difference before comparing model scores or retraining results.

For example, your algorithm might produce the following encoded training file:
```text
0.0\tITEM_FAKE_001\tFAKE_LABEL_A\t32\t452\t0.12
2.0\tITEM_FAKE_002\tFAKE_LABEL_A FAKE_LABEL_B\t3252\t12\t0.75
```
The corresponding audit record is structured by example and action:

```json
{
  "example_id": "00000000-0000-0000-0000-000000000000",
  "actions": [
    {
      "action_id": "ITEM_FAKE_001",
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
      "action_id": "ITEM_FAKE_002",
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

## Run an audit

```bash
hv audit \
  --algorithm-jar /path/to/algorithm-1.2.3.jar \
  --algorithm-name <algorithm-name> \
  --parameter-path /path/to/predict.parameters.zip \
  --metadata-path ./audit.1.2.3.meta \
  --source-path training_data/dt=2000-01-06 \
  --dest-path audit.1.2.3 \
  --ordered \
  --samples 2
```

The command audits `--samples` rows from the source path and writes `audit.1.2.3/part-00000.jsonl`. For
reproducibility, keep `--source-path`, `--samples`, and ordering fixed when comparing runs.

Artifacts are written under `--metadata-path/` (including `metadata.json` and `hotvect-offline-utils.log`). If you run audits via the `hv` CLI wrapper, hotvect also writes `hv.log` (Python logs) and `stdout-stderr.log` (raw subprocess output) there.

`hv audit` always requires a predict-parameters ZIP. Pass it with `--parameter-path`; Hotvect forwards it to Java as
`--parameters`.


Use `--algorithm-override` for a definition patch and place raw JVM arguments after `--`. The [CLI
reference](../../reference/cli/index.md) lists the common options.

## Compare two audits
To compare audit outputs from different algorithm versions, use `hv-ext compare-jsonl`:

```bash
hv-ext compare-jsonl \
  audit.1.2.3/part-00000.jsonl \
  audit.1.2.4/part-00000.jsonl \
  -o ./audit-diff | jq .
```

If there are differences between the two audit files, the command prints a JSON summary and (for the **first** differing line) writes three files under `-o/--output`:

- `<file1_stem>.line=<n>.json`: the JSON from file1 (pretty printed)
- `<file2_stem>.line=<n>.json`: the JSON from file2 (pretty printed)
- `diff.<file1_stem>-<file2_stem>.line=<n>.json`: the diff (pretty printed)

If the files are identical, it prints `{ "message": "The two files are identical" }` and does not write diff files.

The command writes detail files only for the first differing line. Arrays of integers or strings compare as multisets:
order is ignored, but duplicate counts still matter.


Example diff:
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
