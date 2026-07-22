---
title: Predict with Feature Logging
description: Inspect dependency features beside the prediction action that used them
tags: [prediction, debugging, features, composite-algorithms]
---

# Predict with feature logging

Use `hv predict --log-features` to inspect computed features alongside the scored actions of a prediction. It is most
useful when an outer algorithm decodes the input and delegates feature work to child algorithms: one bounded predict
run then shows the real end-to-end path.

## Prediction contract

| Inputs | Command | Artifacts | Verify |
| --- | --- | --- | --- |
| Algorithm JAR/name, a small source slice, and parameters when the target needs them | `hv predict --log-features --ordered` | `part-00000.jsonl`, metadata, logs | Each inspected `result[]` action contains the expected `feature_audit` entry |

Use a small, non-production sample. Feature values are serialized into prediction output and can make files large.

## Run a bounded prediction

```bash
OUT=./prediction-with-features

hv predict \
  --log-features \
  --algorithm-jar /path/to/parent-algorithm.jar \
  --algorithm-name <parent-algorithm-name> \
  --parameter-path /path/to/predict.parameters.zip \
  --source-path /path/to/test-data.jsonl.gz \
  --dest-path "$OUT" \
  --ordered \
  --samples 100
```

`--ordered` makes the output deterministic for inspection: `$OUT/part-00000.jsonl`. Keep the same JAR, parameters,
source slice, and sample count when comparing two builds.

The example supplies a parameter ZIP because most scoring algorithms need one. Omit `--parameter-path` only when the
target algorithm and its dependencies are parameterless.

## Inspect one prediction action

Show the first scored action and its feature logging payload:

```bash
head -n 1 "$OUT/part-00000.jsonl" | jq '.result[0] | {action_id, rank, score, feature_audit}'
```

To inspect one named child algorithm directly:

```bash
head -n 1 "$OUT/part-00000.jsonl" \
  | jq '.result[0].feature_audit["<child-algorithm-name>"].features'
```

## Output contract

`feature_audit` is attached to each scored item in `result[]`, **not** to the root prediction object. It is keyed by
the algorithm that calculated the feature values.

```json
{
  "example_id": "example-001",
  "result": [
    {
      "action_id": "ITEM_FAKE_001",
      "rank": 0,
      "score": 0.0972,
      "feature_audit": {
        "child-algorithm": {
          "features": {
            "feature_numeric_01": 14,
            "numeric_signal_01": 1.25
          }
        }
      }
    }
  ]
}
```

The payload is action-specific: inspect the `result[]` item for the candidate you are investigating.

## When logging is available

Hotvect enables feature auditing on loaded transformers that implement `AuditableTransformer`.
`StandardRankingTransformer` supports it. A transformer that does not implement that contract does not make the
prediction fail; Hotvect writes a warning and omits that transformer's feature audit data.

If an expected algorithm key is missing, read the prediction metadata/logs first, then confirm which transformer the
algorithm definition loads.

## Choose the right diagnostic

| Question | Use | Target | Parameter ZIP |
| --- | --- | --- | --- |
| Did a feature-transforming algorithm produce the expected values? | `hv audit` | The algorithm with the transformer; vectorizer-only definitions are not supported by the current audit task | Required |
| Did an outer algorithm decode the input and score candidates through its dependencies correctly? | `hv predict --log-features` | The outer algorithm | Provide one when the target/dependencies require it |
| Did two builds preserve the full predict score/rank contract? | [Score equivalence testing](../score-equivalence/index.md) | Each build | One shared ZIP |

Audit output uses root `example_id` plus `actions[]`; feature logging uses root `example_id` plus `result[]` and puts
the feature payload on each result action. They are related debugging views, not interchangeable JSON schemas.

## Compare two builds

Run the command above once per JAR with the same parameters and input. Then use the normal predict-equivalence
workflow for the score/rank contract, and compare individual feature payloads only after locating the affected
`example_id` and `action_id`.

For a direct feature-transform comparison, run `hv audit` for both builds and use `hv-ext compare-jsonl` on their
ordered `part-00000.jsonl` outputs.

## See also

- [Feature audits](../feature-audits/index.md)
- [Score equivalence testing](../score-equivalence/index.md)
- [Debug feature engineering](../debug-feature-engineering/index.md)
