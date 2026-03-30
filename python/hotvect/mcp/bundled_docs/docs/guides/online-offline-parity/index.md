---
title: Investigate Online/Offline Parity Gaps
description: Debug cases where live-serving scores diverge from offline replay even when the trained model is supposed to be the same.
tags: [debugging, parity, serving, feature-logging, regression]
difficulty: advanced
estimated_time: 30 minutes
prerequisites:
  - Logged online scores or request-level serving logs
  - A predict parameters ZIP for the live model
  - A replayable sample of live requests
related_docs:
  - ../feature-audits/index.md
  - ../feature-logging/index.md
  - ../score-equivalence/index.md
  - ../../agents/runbooks/debugging/index.md
related_commands:
  - hv predict
  - hv audit
  - hv-ext compare-jsonl
next_steps:
  - Add a diagnostic release with request-level debug properties
  - Verify the fix in a shadow environment before cutting a clean release
---

# Investigate online/offline parity gaps

This guide is for a specific failure mode:

- the model or algorithm **should** be the same online and offline,
- but logged online scores do **not** match offline replay closely enough to explain a rollout.

Typical causes are not model-quality issues. They are usually:

- comparing the wrong dates, parameters, or traffic slice,
- request-contract bugs against external dependencies,
- fallback behavior hiding a failed fetch,
- offline replay using richer preattached data than the live request path.

## 1. Align the comparison contract first

Before debugging features, make sure you are comparing the same thing.

Keep these fixed:

- same live time window,
- same rollout cell / traffic slice,
- same algorithm version,
- same parameter bundle,
- same request rows.

If your serving platform rotates parameter files, do not compare one side with “latest” and the other with a previous parameter id. Treat the exact live parameter bundle as part of the contract.

## 2. Measure replay-vs-online parity within one version

Do this **before** comparing treatment vs control.

For a single live version:

1. take request rows from the live period,
2. replay them offline with the exact live parameters ZIP,
3. compare replayed scores to the logged online scores.

Track at least:

- score MAE,
- p95 / p99 absolute score delta,
- top-1 agreement,
- rank delta distribution.

This tells you whether the problem is:

- a real online/offline parity gap inside one version, or
- a genuine model difference between two versions.

## 3. Use `hv audit` and `hv predict` for offline localization

When replay parity is bad, localize the first mismatch offline:

1. run `hv audit` for the same rows,
2. compare feature JSONL across versions or against a known-good build,
3. run `hv predict --log-features` if you need dependency-level features in the prediction output.

Recommended pattern:

```bash
hv audit \
  --algorithm-jar /path/to/algo.jar \
  --algorithm-name <algorithm-name> \
  --source-path /path/to/live-sampled-rows.jsonl.gz \
  --dest-path replay.audit.jsonl \
  --samples 100

hv predict --log-features \
  --algorithm-jar /path/to/algo.jar \
  --algorithm-name <algorithm-name> \
  --parameter-path /path/to/live.parameters.zip \
  --source-path /path/to/live-sampled-rows.jsonl.gz \
  --dest-path replay.predict.jsonl \
  --samples 100
```

If offline audit and predict look healthy but the live system does not, the problem is usually in the online request path, not the model itself.

## 4. Remember that offline replay can hide live request bugs

Some offline pipelines attach external features directly to the replay row. When that happens, offline replay can look healthy even if the live request path is wrong.

This is the most common trap in serving regressions:

- offline already has the dependency payload,
- online must fetch it with the correct ids and requested fields.

So treat offline replay as necessary, but not sufficient, evidence.

## 5. Instrument live scoring with request-level diagnostics

When the bug only appears in serving, add a **diagnostic release** that logs request-level information through `BulkScoreResponse.additionalProperties()`.

Good request-level fields are:

- request count sent to an external dependency,
- entity count returned,
- failure string,
- explicit fallback flag,
- counts before and after any merge step.

If candidate-level detail matters, add sampled values through `ScoringDecision.additionalProperties()` or sampled response-level payloads.

### What to log

Keep the keys:

- flat,
- scalar where possible,
- pre-merge and pre-fallback.

Good examples:

```json
{
  "additional_properties": {
    "ext_diag_candidate_request_count": 24,
    "ext_diag_history_request_count": 17,
    "ext_diag_history_entity_count": 0,
    "ext_diag_history_failure": "Duplicate key {entity_id=...}",
    "ext_diag_candidate_only_fallback": true,
    "model_input_sample_meta_json": "[{\"candidate_id\":\"A1\",\"candidate_index\":0,\"score\":0.0386}]",
    "model_input_sample_tsv": "NaN\t0.123\tCateg_A\t..."
  }
}
```

Why log before merge/fallback:

- a final merged response can hide which branch actually failed,
- a fallback can make the request look superficially healthy,
- the root cause is often only visible in the pre-merge counts or failure string.

## 6. Sample model input in a compact, reproducible form

When you need to compare exact live vs offline model input, log a low-rate sample of the transformed feature vector.

For CatBoost-style scorers, a compact pattern is:

- a small metadata JSON for row identity,
- a TSV row for the actual ordered model input.

This is better than large nested JSON dumps because:

- it is deterministic,
- it is smaller,
- it can be compared offline with the same column ordering.

## 7. Common serving-side failure classes

The same patterns show up repeatedly:

### Wrong request fields

The algorithm definition asks for the wrong field key, or emits an empty field list for a dependency.

Symptom:

- request counts look fine,
- entity counts may look non-zero,
- downstream feature maps are empty or nearly empty.

### Normalized ids collide

The request builder normalizes entity ids (for example, stripping to one key) and creates duplicate ids.

Symptom:

- live failures such as `Duplicate key ...`,
- request-level fallback flags,
- offline replay often misses it.

Fix:

- deduplicate normalized ids in a deterministic order before the fetch.

### Fallback hides the failure

One branch of a multi-step fetch fails, but the algorithm falls back to a partial response and still scores.

Symptom:

- the request does not crash,
- scores drift,
- logs only show the final merged response unless you explicitly log pre-fallback state.

## 8. Recommended rollout pattern

For parity fixes, use two releases:

1. **diagnostic release**
   - contains the fix,
   - keeps extra diagnostics on,
   - verify in a shadow or canary environment.
2. **clean release**
   - same fix,
   - diagnostics removed or minimized,
   - use for the real experiment or full rollout.

This lets you prove the issue is gone without mixing proof code into the long-lived release line.

## 9. A compact decision tree

- If replay-vs-online parity is already good:
  - investigate model or data differences between versions.
- If replay-vs-online parity is bad, and offline feature audits already differ:
  - fix feature engineering or parameter selection first.
- If replay-vs-online parity is bad, but offline looks healthy:
  - instrument the live request path,
  - log request counts, entity counts, failures, and sampled model input,
  - test in a shadow environment before restarting the main rollout.
