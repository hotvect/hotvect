---
title: Predict Score Equivalence Testing
description: Prove two hotvect algorithm builds produce identical predict output (scores + ordering) using a repeatable JSONL diff
tags: [predict, regression, equivalence, testing, comparison]
difficulty: intermediate
estimated_time: 15 minutes
prerequisites:
  - hotvect CLI installed and working
  - Algorithm JARs built for both versions (control + treatment)
  - A predict parameters ZIP (trained model) for the algorithm
  - A stable offline source dataset (files or dt= directory)
related_docs:
  - ../quickstart/index.md
  - ../feature-logging/index.md
  - ../feature-audits/index.md
  - ../sagemaker-backtests/index.md
  - ../patterns/override-files/index.md
related_commands:
  - hv predict
  - hv backtest
  - hv audit
next_steps:
  - Run a multi-day SageMaker backtest comparison
  - Add equivalence checks to your algorithm release checklist
---

# Predict score equivalence testing

This guide shows a robust way to prove that two algorithm builds produce **exactly the same `hv predict` output**:

- same `example_id`
- same per-example item order (`result[*].additional_properties.action_id` / `result[*].action_id`)
- same `result[*].score` values (exact float equality, not tolerance-based)

This is the right tool when you:

- refactor a transformer/decoder/encoder and want to ensure **no behavior change**
- migrate between Hotvect versions or transformer implementations
- switch to generated/streaming transformer code and need parity

## Quick parity playbook (`audit` → `compare-jsonl` → `predict`)

Use this sequence when you need a strict “no behavior change” check.

### Step A: Generate feature audits for both builds

```bash
hv audit \
  --algorithm-jar /path/to/control.jar \
  --algorithm-name <algorithm-name> \
  --source-path /path/to/input \
  --dest-path control.audit.jsonl

hv audit \
  --algorithm-jar /path/to/treatment.jar \
  --algorithm-name <algorithm-name> \
  --source-path /path/to/input \
  --dest-path treatment.audit.jsonl
```

### Step B: Compare audits

```bash
hv-ext compare-jsonl control.audit.jsonl treatment.audit.jsonl -o ./audit-diff
```

**Pass**: output is `{"message":"The two files are identical"}`.
**Fail**: any diff in audit output; fix features before continuing.

### Step C: Compare predict output with the same parameters ZIP

```bash
hv predict --algorithm-jar /path/to/control.jar --algorithm-name <algorithm-name> \
  --parameter-path /path/to/predict.parameters.zip --source-path /path/to/input --dest-path control.predict.jsonl

hv predict --algorithm-jar /path/to/treatment.jar --algorithm-name <algorithm-name> \
  --parameter-path /path/to/predict.parameters.zip --source-path /path/to/input --dest-path treatment.predict.jsonl
```

Then run the strict JSONL check in this guide (example id, action order, score equality).

**Pass**: zero mismatches (exact equality).
**Fail**: if audits matched but predict differs, inspect encoder/scorer/runtime wiring first.

## 0) Sanity checks (avoid easy footguns)

Confirm you are running the Hotvect you think you are:

```bash
hv --version
```

Confirm the Hotvect offline util JAR is available (see the quickstart if not):

```bash
python3 -c "import hotvect.hotvectjar; print(hotvect.hotvectjar.HOTVECT_JAR_PATH)"
```

Pick the **predict** parameters ZIP (not the encode parameters ZIP):

- ✅ `...@last_test_date_YYYY-MM-DD.parameters.zip`
- ❌ `...@last_test_date_YYYY-MM-DD.encode.parameters.zip`

Note on ordering:

- `hv predict` is already ordered (Hotvect runs prediction via `OrderedFileMapper`).
- There is **no `--ordered` flag for `predict`**; passing it will fail.

## 1) Run `hv predict` for control and treatment

Use the same `--parameter-path` and same `--source-path` for both.

```bash
RUN_DIR=./equivalence-$(date +%Y%m%d-%H%M%S)
mkdir -p "$RUN_DIR"

# Control
hv predict \
  --algorithm-jar /path/to/control.jar \
  --algorithm-name <your-algorithm-name> \
  --parameter-path /path/to/predict.parameters.zip \
  --source-path /path/to/offline-data/dt=YYYY-MM-DD \
  --dest-path "$RUN_DIR/control.jsonl" \
  --metadata-path "$RUN_DIR/control.metadata" \
  --samples 3000

# Treatment
hv predict \
  --algorithm-jar /path/to/treatment.jar \
  --algorithm-name <your-algorithm-name> \
  --parameter-path /path/to/predict.parameters.zip \
  --source-path /path/to/offline-data/dt=YYYY-MM-DD \
  --dest-path "$RUN_DIR/treatment.jsonl" \
  --metadata-path "$RUN_DIR/treatment.metadata" \
  --samples 3000
```

Tips:

- Start with `--samples 200` to sanity-check, then scale to `--samples 3000` or more.
- Keep `--source-path` the same for both runs (file list, directory, and date partitions).

## 2) Diff the JSONL output (strict)

Use this strict comparator: it requires identical example ids, identical candidate ordering, and identical scores.

```bash
python3 - "$RUN_DIR/control.jsonl" "$RUN_DIR/treatment.jsonl" <<'PY'
import json
import sys
from pathlib import Path

control = Path(sys.argv[1])
treat = Path(sys.argv[2])

def load(p: Path) -> list[dict]:
    out = []
    with p.open() as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            out.append(json.loads(line))
    return out

def action_id(item: dict) -> str | None:
    # Different pipelines can serialize action id under different keys.
    ap = item.get("additional_properties") or {}
    return ap.get("action_id") or item.get("action_id")

c = load(control)
t = load(treat)

if len(c) != len(t):
    raise SystemExit(f"line_count_mismatch: control={len(c)} treatment={len(t)}")

for i, (cc, tt) in enumerate(zip(c, t)):
    if cc.get("example_id") != tt.get("example_id"):
        raise SystemExit(f"example_id_mismatch at line {i}: {cc.get('example_id')} vs {tt.get('example_id')}")

    cres = cc.get("result") or []
    tres = tt.get("result") or []

    if len(cres) != len(tres):
        raise SystemExit(f"result_len_mismatch at line {i}: {len(cres)} vs {len(tres)}")

    for j, (ci, ti) in enumerate(zip(cres, tres)):
        if action_id(ci) != action_id(ti):
            raise SystemExit(f"action_id_mismatch at line {i} item {j}: {action_id(ci)} vs {action_id(ti)}")
        if ci.get("score") != ti.get("score"):
            raise SystemExit(f"score_mismatch at line {i} item {j}: {ci.get('score')} vs {ti.get('score')}")

print(f"OK: {len(c)} examples identical")
PY
```

If you need a tolerance-based check instead, change the score comparison to `abs(a-b) <= eps` and print the largest diffs.

## 3) Debugging when there is a mismatch

If you see a mismatch:

- verify you used the same `--parameter-path` and `--source-path`
- re-run with smaller `--samples` and keep the run directory
- consider:
  - `hv audit` on the same inputs to compare feature values
  - `hv predict --log-features` (for composite algorithms) to inspect dependency features

Relevant docs:

- [Run and compare feature audits](../feature-audits/index.md)
- [Predict with feature logging](../feature-logging/index.md)

## 4) Small-sample pipeline smoke runs (fast end-to-end)

When a full encode+train+predict run is too slow, start with a reduced dataset and/or fewer samples:

- point `--source-path` at a single `dt=...` partition or a single file
- use `--samples N` on `hv predict` for fast iteration

This is often enough to validate a migration/refactor before running the full backtest suite.

## 5) SageMaker validation (script-mode + result.json)

For cloud runs, treat `result.json` as the source of truth for metrics and automation:

- fetch `HyperParameters.s3_uri_result_file` (contains `result.json`)
- compare offline metrics under `evaluate.*` between jobs/versions

See: [Run backtests on AWS SageMaker](../sagemaker-backtests/index.md).

## 5.1) Predict equivalence vs backtests (why results can differ)

`hv predict` equivalence testing proves that **inference** is identical *given the same parameters zip and the same input*.

`hv backtest` typically **retrains** parameters per day. That means backtest metrics can differ even if:
- audit feature values are identical, and
- predict scores are identical when you pin the same parameters zip.

When you want a backtest-like run that isolates inference changes, reuse an existing parameters zip via
`hotvect_execution_parameters.with_parameter` in an override file (usually on the model dependency). That makes the pipeline
skip encode/train and run only predict/evaluate using the pinned parameters.

To compare multiple backtest days, prefer `hv-ext metrics compare-quality --output-base-dir <meta_dir>` over single-day spot checks.
emits deltas (and a CI for the mean delta when you have 2+ paired days).

## 6) Deterministic/ordered training input (reduce variance)

If your decoder supports it, you can force deterministic decoding order for training data to reduce variance when comparing two versions on the same date.

Example override:

```json
{
  "hyperparameter_version": "ordered",
  "dependencies": {
    "my-model": {
      "train_decoder_parameters": {
        "ordering": "ordered"
      }
    }
  }
}
```

Attach it to both control and treatment in a backtest:

```bash
hv backtest \
  --git-reference <control-ref> \
  --git-reference <treatment-ref> \
  --algorithm-override override-ordered.json \
  --last-test-time YYYY-MM-DD \
  --number-of-runs 1 \
  --algo-repo-url <repo> \
  --data-base-dir <...> \
  --output-base-dir <...> \
  --scratch-dir <...>
```

See: [Override files](../patterns/override-files/index.md).
