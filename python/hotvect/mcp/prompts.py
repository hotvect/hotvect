from __future__ import annotations

from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class PromptDef:
    name: str
    description: str
    text: str


_SETUP_CONFIG = PromptDef(
    name="setup_config",
    description="Set up local Hotvect environment and ~/.hotvect/config.json (CLI-only workflow).",
    text=(
        "## Goal\n"
        "Ensure `hv` / `hv-ext` work locally and base directories come from `~/.hotvect/config.json`.\n"
        "\n"
        "## Steps\n"
        "1) Activate the venv (source checkout):\n"
        "   - `cd python`\n"
        "   - `source .venv/bin/activate`\n"
        "\n"
        "2) Verify CLIs:\n"
        "   - `command -v hv`\n"
        "   - `command -v hv-ext`\n"
        "   - `hv --help`\n"
        "   - `hv-ext --help`\n"
        "\n"
        "3) Initialize config (recommended):\n"
        "   - `hv-ext config show` (should succeed)\n"
        "   - If missing, run: `hv-ext config init` (interactive)\n"
        "\n"
        "4) Quick sanity check that defaults resolve:\n"
        "   - Run a command that supports config defaults and ensure it does not complain about missing base dirs.\n"
        "\n"
        "## How to quickly find relevant docs/code\n"
        "- Search docs:\n"
        '  - `rg -n "config\\.json|hv-ext config" python/hotvect/mcp/bundled_docs/docs`\n'
        "- Search Python source:\n"
        '  - `rg -n "load_config\\(|resolve_meta_dir\\(" python/hotvect`\n'
    ),
)

_QUALITY_REGRESSION_BACKTEST = PromptDef(
    name="quality_regression_backtest",
    description="Run a backtest regression (quality metrics) and compare baseline vs treatment across days.",
    text=(
        "## Goal\n"
        "Compare two algorithm versions on offline quality metrics over a date range.\n"
        "\n"
        "## Inputs (fill in)\n"
        "- Baseline algorithm id: `<algo_name>@<baseline_version>`\n"
        "- Treatment algorithm id: `<algo_name>@<treatment_version>`\n"
        "- Last test time (anchor): `<YYYY-MM-DD>`\n"
        "- Number of runs (days): `<N>`\n"
        "\n"
        "## Steps\n"
        "1) Run backtest (local or SageMaker depending on your setup):\n"
        "   - `hv backtest --git-reference <baseline_git_ref> --git-reference <treatment_git_ref> \\\n"
        "        --algo-repo-url <repo_url> \\\n"
        "        --data-base-dir <data_dir> --output-base-dir <output_dir> --scratch-dir <scratch_dir> \\\n"
        "        --last-test-time <YYYY-MM-DD> \\\n"
        "        --number-of-runs <N>`\n"
        "\n"
        "   Notes:\n"
        "   - If you have `~/.hotvect/config.json` configured, the base dirs default from there.\n"
        "   - The backtest writes a conventional `meta/` directory under the output base dir.\n"
        "\n"
        "2) Inspect what results exist locally:\n"
        "   - `hv-ext list-available-results --from-date <YYYY-MM-DD> --to-date <YYYY-MM-DD>`\n"
        "\n"
        "3) Compare offline quality metrics across the same date range (paired by day):\n"
        "   - `hv-ext metrics compare-quality \\\n"
        "        --output-base-dir <path/to/results/meta> \\\n"
        "        --control <algo_name>@<baseline_version> \\\n"
        "        --treatment <algo_name>@<treatment_version> \\\n"
        "        --from-test-date <YYYY-MM-DD> \\\n"
        "        --to-test-date <YYYY-MM-DD> \\\n"
        "        > compare.json`\n"
        "\n"
        "   Note: `--meta-dir` defaults from `~/.hotvect/config.json` if configured.\n"
        "\n"
        "## Tips\n"
        "- Prefer comparing multiple days; confidence intervals require 2+ paired days.\n"
        "- If a day is missing for either side, it is reported as missing/invalid and excluded from pairing.\n"
        "\n"
        "## Find relevant docs/code\n"
        '- `rg -n "metrics compare-quality|list-available-results" python/hotvect/mcp/bundled_docs/docs python/hotvect`\n'
    ),
)

_MAJOR_BACKWARD_COMPAT_PREDICT_REGRESSION = PromptDef(
    name="major_backward_compat_predict_regression",
    description="Verify hv(X+1) predict compatibility for all algorithms currently in use on hv(X).",
    text=(
        "## Goal\n"
        "Prove that Hotvect `hv(X+1)` is backward compatible with `hv(X)` for **predict** execution:\n"
        "- every algorithm currently in use can run with `hv(X+1)` using existing artifacts,\n"
        "- `hv(X)` vs `hv(X+1)` predictions match (score + rank) with the same parameter ZIP and same input rows.\n"
        "\n"
        "This runbook is predict-only on purpose. Do **not** retrain for compatibility proof.\n"
        "\n"
        "## Step 1 — List all in-use algorithms from EMS (`hv-exp`)\n"
        "Run:\n"
        "```bash\n"
        "hv-exp algorithm list-in-use > in_use_algorithms.json\n"
        "```\n"
        "\n"
        "Optional (single slot):\n"
        "```bash\n"
        "hv-exp algorithm list-in-use --slot-name <slot-name>\n"
        "```\n"
        "\n"
        "Extract unique `(algorithm_name, algorithm_version)` pairs from `algorithms[]`.\n"
        "\n"
        "## Step 2 — Resolve production artifacts for each pair\n"
        "For each `<name>@<version>` resolve:\n"
        "- JAR: `s3://example-bucket/algorithms/jars/<name>/<name>-<version>.jar`\n"
        "- Parameters ZIP: latest `*.parameters.zip` under\n"
        "  `s3://example-bucket/algorithms/training_results/<name>/<version>/`\n"
        "- Algorithm definition JSON from the same training run (to derive test dataset prefix).\n"
        "\n"
        "Use the same parameters ZIP for both runtimes (`hv(X)` and `hv(X+1)`).\n"
        "\n"
        "## Step 3 — Run predict on both runtimes\n"
        "For each algorithm/version run:\n"
        "```bash\n"
        "# hv(X)\n"
        "hv predict \\\n"
        "  --algorithm-jar <jar> \\\n"
        "  --algorithm-name <name> \\\n"
        "  --source-path <source_path> \\\n"
        "  --parameter-path <params_zip> \\\n"
        "  --dest-path hvX.pred.jsonl \\\n"
        "  --samples <N>\n"
        "\n"
        "# hv(X+1)\n"
        "hv predict \\\n"
        "  --algorithm-jar <jar> \\\n"
        "  --algorithm-name <name> \\\n"
        "  --source-path <source_path> \\\n"
        "  --parameter-path <params_zip> \\\n"
        "  --dest-path hvXplus1.pred.jsonl \\\n"
        "  --samples <N>\n"
        "```\n"
        "\n"
        "Important:\n"
        "- keep source rows identical,\n"
        "- keep parameters ZIP identical,\n"
        "- only runtime version changes.\n"
        "\n"
        "## Step 4 — Compare equivalence (score + rank)\n"
        "Run strict comparison:\n"
        "```bash\n"
        "hv-ext compare-equivalence hvX.pred.jsonl hvXplus1.pred.jsonl --score-eps 1e-6 > compare.strict.json\n"
        "```\n"
        "\n"
        "If deterministic tie order is not guaranteed in your stack, also run:\n"
        "```bash\n"
        "hv-ext compare-equivalence hvX.pred.jsonl hvXplus1.pred.jsonl \\\n"
        "  --score-eps 1e-6 \\\n"
        "  --allow-non-deterministic-tie-breaking > compare.tie_allowed.json\n"
        "```\n"
        "\n"
        "## Pass criteria per algorithm version\n"
        "- `hv(X+1)` predict exits successfully and output is non-empty.\n"
        "- `compare-equivalence` status is `passed`.\n"
        "- No `example_id` mismatch.\n"
        "\n"
        "## Final output\n"
        "Produce a table with columns:\n"
        "- algorithm, version,\n"
        "- hv(X) predict status,\n"
        "- hv(X+1) predict status,\n"
        "- score result,\n"
        "- rank result,\n"
        "- processed lines,\n"
        "- failure reason (if any).\n"
        "\n"
        "## Find relevant docs/code\n"
        '- `rg -n "hv-exp algorithm list-in-use|compare-equivalence|with_parameter|predict parity" python/hotvect/mcp/bundled_docs/docs python/hotvect`\n'
    ),
)

_SAGEMAKER_BACKTEST_RUNBOOK = PromptDef(
    name="sagemaker_backtest_runbook",
    description="Run backtests on SageMaker and compare results (CLI-only workflow).",
    text=(
        "## Goal\n"
        "Submit backtests to SageMaker, download results, and compare baseline vs treatment over multiple days.\n"
        "\n"
        "## Inputs (fill in)\n"
        "- Algorithm repo URL: `<repo_url>`\n"
        "- Baseline/treatment git references: `<baseline_git_ref>`, `<treatment_git_ref>`\n"
        "- SageMaker config JSON: `<path/to/sagemaker_config.json>`\n"
        "- Last test time: `<YYYY-MM-DD>`\n"
        "- Number of runs (days): `<N>`\n"
        "- Results S3 prefix (where SageMaker writes outputs): `<s3://.../>`\n"
        "\n"
        "## Steps\n"
        "1) Submit backtest jobs:\n"
        "   - `hv backtest \\\n"
        "        --git-reference <baseline_git_ref> \\\n"
        "        --git-reference <treatment_git_ref> \\\n"
        "        --algo-repo-url <repo_url> \\\n"
        "        --sagemaker-config <path/to/sagemaker_config.json> \\\n"
        "        --output-base-dir <output_dir> --scratch-dir <scratch_dir> \\\n"
        "        --last-test-time <YYYY-MM-DD> \\\n"
        "        --number-of-runs <N>`\n"
        "\n"
        "   Note: for SageMaker runs, prefer `s3://...` output/scratch dirs.\n"
        "\n"
        "2) Download results locally:\n"
        "   - `hv-ext download-results \\\n"
        "        --s3-base-prefix <s3://.../> \\\n"
        "        --dest-base-dir <local_results_dir> \\\n"
        "        --from-date <YYYY-MM-DD> \\\n"
        "        --to-date <YYYY-MM-DD>`\n"
        "\n"
        "3) List what was downloaded and what’s usable:\n"
        "   - `hv-ext list-available-results --meta-dir <local_results_dir>/meta --from-date <YYYY-MM-DD> --to-date <YYYY-MM-DD>`\n"
        "\n"
        "4) Compare offline quality metrics:\n"
        "   - `hv-ext metrics compare-quality \\\n"
        "        --output-base-dir <local_results_dir>/meta \\\n"
        "        --control <algo_name>@<baseline_version> \\\n"
        "        --treatment <algo_name>@<treatment_version> \\\n"
        "        --from-test-date <YYYY-MM-DD> \\\n"
        "        --to-test-date <YYYY-MM-DD> \\\n"
        "        > compare.json`\n"
        "\n"
        "## Find relevant docs/code\n"
        '- `rg -n "download-results|sagemaker|backtest" python/hotvect/mcp/bundled_docs/docs python/hotvect`\n'
    ),
)

_RUN_AND_COMPARE_FEATURE_AUDITS = PromptDef(
    name="run_and_compare_feature_audits",
    description="Run `hv audit` for baseline and treatment, then compare the JSONL output to spot feature diffs.",
    text=(
        "## Goal\n"
        "Generate feature audits for two versions and diff them to find the first feature-level change.\n"
        "\n"
        "## Inputs (fill in)\n"
        "- Algorithm name: `<your-algorithm-name>`\n"
        "- Baseline JAR: `<path/to/baseline.jar>`\n"
        "- Treatment JAR: `<path/to/treatment.jar>`\n"
        "- Source path: `<path/to/offline-data/dt=YYYY-MM-DD>` (or file)\n"
        "- Samples: `<N>` (start with 20–200)\n"
        "\n"
        "## Steps\n"
        "1) Run baseline audit:\n"
        "   - `hv audit --algorithm-jar <baseline.jar> --algorithm-name <your-algorithm-name> \\\n"
        "        --source-path <source> \\\n"
        "        --dest-path baseline.audit --metadata-path baseline.audit.metadata \\\n"
        "        --samples <N>`\n"
        "\n"
        "2) Run treatment audit:\n"
        "   - `hv audit --algorithm-jar <treatment.jar> --algorithm-name <your-algorithm-name> \\\n"
        "        --source-path <source> \\\n"
        "        --dest-path treatment.audit --metadata-path treatment.audit.metadata \\\n"
        "        --samples <N>`\n"
        "\n"
        "3) Diff the audit JSONL (prints JSON on stdout; empty means equal):\n"
        "   - `hv-ext compare-jsonl baseline.audit/part-00000.jsonl treatment.audit/part-00000.jsonl`\n"
        "\n"
        "## Notes\n"
        "- Use the same `--source-path` and `--samples` for reproducibility.\n"
        "- If the only diffs are key renames, pass a renaming config:\n"
        "  - `hv-ext compare-jsonl baseline.audit/part-00000.jsonl treatment.audit/part-00000.jsonl -c field_mappings.json`\n"
        "\n"
        "## Find relevant docs/code\n"
        '- `rg -n "hv audit|compare-jsonl|audit" python/hotvect/mcp/bundled_docs/docs python/hotvect`\n'
    ),
)

_PREDICT_WITH_FEATURE_LOGGING = PromptDef(
    name="predict_with_feature_logging",
    description="Debug composite algorithms by running `hv predict --log-features` and inspecting `feature_audit`.",
    text=(
        "## Goal\n"
        "Run a full `hv predict` while also logging dependency-level features (useful for composite algorithms).\n"
        "\n"
        "## Inputs (fill in)\n"
        "- Algorithm name (outer): `<outer-algorithm-name>`\n"
        "- Algorithm JAR: `<path/to/outer.jar>`\n"
        "- Predict parameters ZIP: `<path/to/predict.parameters.zip>`\n"
        "- Source path: `<path/to/offline-data/dt=YYYY-MM-DD>` (or file)\n"
        "\n"
        "## Steps\n"
        "1) Run prediction with feature logging:\n"
        "   - `hv predict --log-features \\\n"
        "        --algorithm-jar <outer.jar> --algorithm-name <outer-algorithm-name> \\\n"
        "        --parameter-path <predict.parameters.zip> \\\n"
        "        --source-path <source> \\\n"
        "        --dest-path predict.with-features --metadata-path predict.with-features.metadata \\\n"
        "        --samples 200`\n"
        "\n"
        "2) Inspect one example's feature audit:\n"
        "   - `head -n 1 predict.with-features/part-00000.jsonl | jq '.feature_audit'`\n"
        "\n"
        "## Notes\n"
        "- `feature_audit` groups features by dependency algorithm name.\n"
        "- If you only need features (no scores), use `hv audit` instead.\n"
        "\n"
        "## Find relevant docs/code\n"
        '- `rg -n "--log-features|feature_audit" python/hotvect/mcp/bundled_docs/docs python/hotvect`\n'
    ),
)

_USE_HOTVECT_CACHING = PromptDef(
    name="use_hotvect_caching",
    description="Speed up repeated runs by enabling Hotvect caching (best-effort reuse of state/encode/train artifacts).",
    text=(
        "## Goal\n"
        "Speed up repeated backtests by reusing expensive pipeline artifacts (state, encoded data, trained params).\n"
        "\n"
        "## Best default: enable caching via `hv backtest --cache`\n"
        "- `--cache <local_path_or_s3_uri>` enables caching\n"
        "- `--cache-scope major|minor|patch|hyperparam` controls cache sharing across algorithm versions\n"
        "- `--cache-refresh` forces recompute only with an effective `cache_base_dir` and cache mode `run`\n"
        "\n"
        "Example (SageMaker; recommended):\n"
        "```bash\n"
        "hv backtest \\\n"
        "  --git-reference <baseline_git_ref> \\\n"
        "  --git-reference <treatment_git_ref> \\\n"
        "  --algo-repo-url <repo_url> \\\n"
        "  --sagemaker-config <path/to/sagemaker_config.json> \\\n"
        "  --last-test-time <YYYY-MM-DD> \\\n"
        "  --number-of-runs <N> \\\n"
        "  --cache s3://bucket/hotvect-cache/ \\\n"
        "  --cache-scope hyperparam\n"
        "```\n"
        "\n"
        "## Notes\n"
        "- Caching is best-effort. If an artifact is missing, Hotvect recomputes it.\n"
        "- For SageMaker, use `s3://...` cache prefixes; local paths do not persist.\n"
        "\n"
        "## Find relevant docs/code\n"
        '- `rg -n "cache_base_dir|--cache|cache_scope|cache-refresh" python/hotvect/mcp/bundled_docs/docs python/hotvect`\n'
    ),
)

_REUSE_EXISTING_OUTPUTS = PromptDef(
    name="reuse_existing_outputs",
    description="Pin an exact parameters ZIP (strict) or use caching (best-effort) to speed iteration and isolate changes.",
    text=(
        "## Goal\n"
        "Reuse previously generated outputs so your run is faster and/or isolates specific changes.\n"
        "\n"
        "Hotvect supports two related mechanisms:\n"
        "1) **Strict**: pin an exact parameters ZIP via `hotvect_execution_parameters.with_parameter` (must exist)\n"
        "2) **Best-effort**: enable caching via `cache_base_dir` / `hv backtest --cache` (reuse if present)\n"
        "\n"
        "## Option A (strict): pin `predict-parameters.zip` via `with_parameter`\n"
        "Create an override JSON (usually on a dependency):\n"
        "```json\n"
        "{\n"
        '  "dependencies": {\n'
        '    "my-model": {\n'
        '      "hotvect_execution_parameters": {\n'
        '        "with_parameter": "s3://bucket/hotvect-cache/my-model@1.2.3/last_test_date_2024-06-01/train/predict-parameters.zip"\n'
        "      }\n"
        "    }\n"
        "  }\n"
        "}\n"
        "```\n"
        "\n"
        "Rules:\n"
        "- `dependencies` keys must match declared child algorithm names.\n"
        "- overriding one child preserves unspecified siblings.\n"
        "- `null` deletes a leaf field from the effective definition.\n"
        "\n"
        "Then run with the override (example: predict):\n"
        "```bash\n"
        "hv predict \\\n"
        "  --algorithm-jar <jar> --algorithm-name <algo> \\\n"
        "  --algorithm-override overrides.json \\\n"
        "  --parameter-path <predict.parameters.zip> \\\n"
        "  --source-path <source> \\\n"
        "  --dest-path out.predict --metadata-path out.predict.metadata\n"
        "```\n"
        "\n"
        "## Option B (best-effort): caching\n"
        "- Use `hv backtest --cache ...` for iterative backtests, or\n"
        "- Set `hotvect_execution_parameters.cache_base_dir` in the algorithm definition.\n"
        "\n"
        "## Find relevant docs/code\n"
        '- `rg -n "with_parameter|cache_base_dir|--cache" python/hotvect/mcp/bundled_docs/docs python/hotvect`\n'
    ),
)

_PERFORMANCE_REGRESSION_CHECK = PromptDef(
    name="performance_regression_check",
    description="Run replicated performance tests, compare latency/throughput/memory, and confirm regressions with statistical tests.",
    text=(
        "## Goal\n"
        "Detect system-level performance regressions (latency/throughput/memory) between two versions.\n"
        "\n"
        "## Benchmark contract\n"
        "Keep these fixed between baseline and treatment:\n"
        "- source data\n"
        "- logical workload definition such as training horizon, partitions, input channels, shard count, and cache policy\n"
        "- any model-quality-affecting settings such as hyperparameters, sampling ratio, feature toggles, or label construction\n"
        "- parameter bundle\n"
        "- runtime / training image\n"
        "- instance type / instance count / volume\n"
        "- spot vs on-demand mode\n"
        "- `--max-threads`\n"
        "- JVM args and algorithm overrides\n"
        "\n"
        "## Steps\n"
        "1) Decide whether this is a production-like benchmark or a diagnostic stage-isolation benchmark:\n"
        "   - for a production-like claim, do not change training horizon, input channels, shard count, or cache reuse policy\n"
        "   - do not change hyperparameters, sampling policy, feature switches, label logic, or any other setting that can change model quality\n"
        "   - if you intentionally isolate one stage, label the result as diagnostic and do not reuse it as a production wall-clock claim\n"
        "\n"
        "2) If you are comparing latency at the same offered load, set a fixed target load:\n"
        "   - add `--target-rps <N>` to both baseline and treatment\n"
        "   - do not rely on warmup-derived pacing for A/B tail-latency claims\n"
        "\n"
        "3) Pin sample size:\n"
        "   - use the same `--samples <N>` for both candidates\n"
        "   - if you care about `p999`, prefer a large run such as `1000000` samples rather than a short smoke test\n"
        "\n"
        "4) If you are comparing train time and the encoded artifacts should be equivalent:\n"
        "   - keep encoded shard count and layout fixed\n"
        "   - persist encoded outputs when possible and compare them directly\n"
        "   - fingerprint encoded content in an order-insensitive way if row order may differ\n"
        "   - if the train-time delta looks suspicious, cross-train each runtime on the other runtime's encoded output before blaming the trainer\n"
        "\n"
        "5) Run replicated independent jobs for baseline and treatment (prefer at least 3 per side):\n"
        "   - `hv performance-test --algorithm-jar <baseline.jar> --algorithm-name <algo> \\\n"
        "        --parameter-path <predict.parameters.zip> --source-path <source> \\\n"
        "        --metadata-path baseline.r1.perf.metadata --samples <N> --target-rps <RPS>`\n"
        "   - `hv performance-test --algorithm-jar <baseline.jar> --algorithm-name <algo> \\\n"
        "        --parameter-path <predict.parameters.zip> --source-path <source> \\\n"
        "        --metadata-path baseline.r2.perf.metadata --samples <N> --target-rps <RPS>`\n"
        "   - repeat for baseline `r3...` and treatment `r1...r3`\n"
        "   - interleave or randomize the submission order rather than running all baseline jobs first and all treatment jobs second\n"
        "\n"
        "6) Compare summary metrics for a quick read:\n"
        "   - `hv-ext metrics compare-system baseline.r1.perf.metadata/metadata.json treatment.r1.perf.metadata/metadata.json`\n"
        "\n"
        "7) Then do statistical testing across all replicated runs:\n"
        "   - compare job-level metrics such as mean latency, `p95`, `p99`, `p999`, throughput, and memory\n"
        "   - treat separate job submissions as the primary independent observations\n"
        "   - Hotvect's internal repeated perf runs from one job are useful as a secondary check, but they are not a replacement for replicated jobs\n"
        "   - use Welch t-test plus a non-parametric check such as a permutation test\n"
        "   - report a bootstrap confidence interval for the effect size when possible\n"
        "   - do not call a tail regression confirmed if the interval still spans zero\n"
        "\n"
        "## Notes\n"
        "- A single short run is fine for smoke testing, but not for a reliable `p99`/`p999` conclusion.\n"
        "- If you inject a custom runtime, verify both the imported Python package version and the shell entrypoints used by the pipeline (for example `hv`, `hv-ext`, and `catboost_train`).\n"
        "- If a difference still looks real after replicated testing, profile it with JFR or request-path instrumentation.\n"
        "\n"
        "## Find relevant docs/code\n"
        '- `rg -n "performance-test|metrics compare-system|response_time_metrics|performance-benchmarking" python/hotvect/mcp/bundled_docs/docs python/hotvect`\n'
    ),
)

_PERFORMANCE_INVESTIGATION_RUNBOOK = PromptDef(
    name="performance_investigation_runbook",
    description="Investigate a performance change without mixing software deltas with workload, config, or artifact mismatches.",
    text=(
        "## Goal\n"
        "Explain a performance difference in a way that is reproducible and defensible.\n"
        "\n"
        "## Freeze the comparison contract first\n"
        "Record and hold constant:\n"
        "- algorithm refs / versions\n"
        "- Hotvect version\n"
        "- last test time / date range\n"
        "- number of training days\n"
        "- train/test prefixes\n"
        "- instance type / machine shape\n"
        "- spot vs on-demand\n"
        "- container image\n"
        "- override files\n"
        "- `writer_num_shards`\n"
        "- sample size / target RPS / warmup settings\n"
        "\n"
        "Do not compare runs until these match or you have explicitly documented why they differ.\n"
        "\n"
        "## Do not change model-quality-affecting knobs by accident\n"
        "If you are evaluating a software/runtime perf change, keep these identical unless they are the explicit subject of the experiment:\n"
        "- training data window / number of training days\n"
        "- train/test date boundaries\n"
        "- sampling ratio / number of examples\n"
        "- feature set\n"
        "- feature-store inputs\n"
        "- model hyperparameters\n"
        "- training command\n"
        "- parameter reuse / `with_parameter`\n"
        "- ordered vs unordered mode when it changes model input order\n"
        "\n"
        "If those differ, you are benchmarking a different model-training problem, not a pure software delta.\n"
        "\n"
        "## Measure the right metric\n"
        "Keep these separate:\n"
        "- SageMaker wall clock: `TrainingStartTime -> TrainingEndTime`\n"
        "- pipeline timings: `result.json` values like `prepare_dependencies`, `encode`, `train`, `predict`\n"
        "- perf-test metrics: throughput, latency, memory\n"
        "\n"
        "Do not mix provisioning time with algorithm runtime.\n"
        "\n"
        "## Verify the runtime that actually executed\n"
        "Inspect the completed job, not just the launch script:\n"
        "- effective algorithm definition\n"
        "- `result.json`\n"
        "- logs / stderr\n"
        "- custom payload or wheelhouse activation\n"
        "- executable resolution (`PATH`), not only Python imports\n"
        "\n"
        "## Localize the regression by layer\n"
        "1) Feature parity:\n"
        "   - `hv audit ...`\n"
        "   - `hv-ext compare-jsonl baseline.audit/part-00000.jsonl treatment.audit/part-00000.jsonl`\n"
        "\n"
        "2) Encoded data parity:\n"
        "   - compare row counts\n"
        "   - compare encoded row multiset\n"
        "   - compare row order separately\n"
        "\n"
        "3) Trainer input parity:\n"
        "   - confirm all shards/files were consumed\n"
        "   - confirm the trainer did not silently pick only one file\n"
        "\n"
        "## Watch the consumer contract\n"
        "If you change `writer_num_shards` or ordered/unordered mode, verify the downstream stage can consume that layout.\n"
        "Do not assume multi-shard output is faster overall; benchmark the full pipeline.\n"
        "\n"
        "## Use local replay before burning cloud time\n"
        "Useful tools:\n"
        "- `hv-ext data-dependency` to download exact datasets\n"
        "- `hv audit` for feature diffs\n"
        "- `hv encode` for encoded-data diffs\n"
        "- `with_parameter` / cached outputs to isolate predict from train\n"
        "\n"
        "## Repeat runs when the effect is small\n"
        "If the observed difference is small, run repeated paired measurements and summarize mean + spread before drawing conclusions.\n"
        "\n"
        "## Final output\n"
        "Produce a short table with:\n"
        "- baseline vs treatment contract\n"
        "- metric being compared\n"
        "- whether data parity was proven\n"
        "- whether the change actually took effect at runtime\n"
        "- final conclusion and confidence level\n"
        "\n"
        "## Find relevant docs/code\n"
        '- `rg -n "performance investigations|writer_num_shards|with_parameter|compare-jsonl|result.json" python/hotvect/mcp/bundled_docs/docs python/hotvect`\n'
    ),
)

_PREDICT_SCORE_EQUIVALENCE_TESTING = PromptDef(
    name="predict_score_equivalence_testing",
    description="Prove two algorithm builds produce identical `hv predict` output (same params ZIP + same input).",
    text=(
        "## Goal\n"
        "Prove two algorithm JARs produce **exactly the same** `hv predict` outputs when using the same\n"
        "parameters ZIP and the same source dataset (strict equality; no tolerances).\n"
        "\n"
        "This is the right workflow for:\n"
        "- Hotvect version upgrades (e.g. v8 → v9)\n"
        "- transformer refactors (legacy → standard, generated → hand-written)\n"
        "- dependency wiring changes where you expect no behavior change\n"
        "\n"
        "## Inputs (fill in)\n"
        "- Algorithm name: `<your-algorithm-name>`\n"
        "- Control JAR: `<path/to/control.jar>`\n"
        "- Treatment JAR: `<path/to/treatment.jar>`\n"
        "- Predict parameters ZIP: `<path/to/predict.parameters.zip>`\n"
        "- Predict source: `<path/to/offline-data/dt=YYYY-MM-DD>` (or a single file)\n"
        "- Samples: `<N>` (start with 200)\n"
        "\n"
        "## Steps\n"
        "0) Sanity-check which Hotvect you are running:\n"
        "   - `hv --version`\n"
        "\n"
        "1) (Optional, recommended) Compare feature audits first to ensure feature values are identical:\n"
        "   - `hv audit --algorithm-jar <control.jar> --algorithm-name <your-algorithm-name> \\\n"
        "        --source-path <source> --dest-path control.audit --metadata-path control.audit.metadata \\\n"
        "        --samples <N>`\n"
        "   - `hv audit --algorithm-jar <treatment.jar> --algorithm-name <your-algorithm-name> \\\n"
        "        --source-path <source> --dest-path treatment.audit --metadata-path treatment.audit.metadata \\\n"
        "        --samples <N>`\n"
        "   - `hv-ext compare-jsonl control.audit/part-00000.jsonl treatment.audit/part-00000.jsonl`\n"
        "\n"
        "2) Run `hv predict` for control and treatment using the same parameters ZIP:\n"
        "   - `hv predict --algorithm-jar <control.jar> --algorithm-name <your-algorithm-name> \\\n"
        "        --parameter-path <predict.parameters.zip> --source-path <source> \\\n"
        "        --dest-path control.predict --metadata-path control.predict.metadata \\\n"
        "        --samples <N>`\n"
        "   - `hv predict --algorithm-jar <treatment.jar> --algorithm-name <your-algorithm-name> \\\n"
        "        --parameter-path <predict.parameters.zip> --source-path <source> \\\n"
        "        --dest-path treatment.predict --metadata-path treatment.predict.metadata \\\n"
        "        --samples <N>`\n"
        "\n"
        "3) Strictly compare prediction JSONL:\n"
        "   - `hv-ext compare-jsonl control.predict/part-00000.jsonl treatment.predict/part-00000.jsonl`\n"
        "\n"
        "## Notes / common footguns\n"
        "- `hv predict` writes a destination directory. Ordered predict writes `part-00000.jsonl`; unordered predict writes multiple `part-*.jsonl` files.\n"
        "- Parallel SageMaker one-shot publish uses zero-padded `part-<worker>-<localshard>.jsonl[.gz]` files such as `part-00000-00000.jsonl`.\n"
        "- `hv predict` accepts `--ordered` / `--unordered`; unordered is the default, and you should use ordered mode when you need strict row-for-row diffing.\n"
        "- Use the *predict* parameters ZIP (not the encode parameters ZIP).\n"
        "- For Hotvect v9 users: `hv compare-jsonl ...` moved to `hv-ext compare-jsonl ...` in v10.\n"
        "\n"
        "## Find relevant docs/code\n"
        '- `rg -n "predict score equivalence|hv predict|compare-jsonl" python/hotvect/mcp/bundled_docs/docs python/hotvect`\n'
    ),
)

_ORDERED_BACKTEST_WITH_PINNED_PARAMETERS = PromptDef(
    name="ordered_backtest_with_pinned_parameters",
    description="Run a multi-day backtest that isolates inference changes by pinning a parameters ZIP.",
    text=(
        "## Goal\n"
        "Compare two versions over multiple days in a way that is:\n"
        "- **inference-only** (reuse the same parameters ZIP so encode/train variance is removed).\n"
        "\n"
        "This is the right workflow when you already proved `hv predict` parity with a fixed params ZIP,\n"
        "and you want a backtest-style evaluation that focuses on *runtime* changes only.\n"
        "\n"
        "## Inputs (fill in)\n"
        "- Algorithm repo URL: `<repo_url>`\n"
        "- Control/treatment git refs: `<control_git_ref>`, `<treatment_git_ref>`\n"
        "- Last test time (anchor): `<YYYY-MM-DD>`\n"
        "- Number of runs (days): `<N>`\n"
        "- Parameters ZIP to pin: `<path/to/predict.parameters.zip>`\n"
        "- Override file path: `<overrides.json>`\n"
        "\n"
        "## Steps\n"
        "1) Create an override JSON that pins parameters (usually on the model dependency):\n"
        "   - Use `hotvect_execution_parameters.with_parameter` and point it at the ZIP.\n"
        "   - Optionally disable `train` / `encode` / `generate-state` if you want to be explicit.\n"
        "\n"
        "2) Run backtest (local or SageMaker) using the override:\n"
        "   - `hv backtest \\\n"
        "        --git-reference <control_git_ref> \\\n"
        "        --git-reference <treatment_git_ref> \\\n"
        "        --algo-repo-url <repo_url> \\\n"
        "        --last-test-time <YYYY-MM-DD> \\\n"
        "        --number-of-runs <N> \\\n"
        "        --algorithm-override <overrides.json>`\n"
        "\n"
        "   Override rules: `dependencies.<child>` must target declared children, overriding one child preserves siblings, and `null` deletes a field.\n"
        "\n"
        "3) Compare multi-day results (paired by date):\n"
        "   - `hv-ext metrics compare-quality --control <algo>@<control> --treatment <algo>@<treatment> \\\n"
        "        --from-test-date <YYYY-MM-DD> --to-test-date <YYYY-MM-DD> > compare.json`\n"
        "\n"
        "## Find relevant docs/code\n"
        '- `rg -n "with_parameter|algorithm-override|metrics compare-quality" python/hotvect/mcp/bundled_docs/docs python/hotvect`\n'
    ),
)

_ONLINE_OFFLINE_PARITY_INVESTIGATION = PromptDef(
    name="online_offline_parity_investigation",
    description="Debug cases where live-serving scores diverge from offline replay for the same algorithm/parameters.",
    text=(
        "## Goal\n"
        "Explain an online/offline score gap without mixing it up with a genuine model-quality change.\n"
        "\n"
        "## Inputs (fill in)\n"
        "- Algorithm name: `<your-algorithm-name>`\n"
        "- Live algorithm version: `<version>`\n"
        "- Live parameter id / parameters ZIP: `<path-or-id>`\n"
        "- Live request sample: `<path/to/request-sample.jsonl.gz>`\n"
        "- Logged online score field: `<field_name>`\n"
        "\n"
        "## Steps\n"
        "1) Align the comparison contract:\n"
        "   - same live dates\n"
        "   - same traffic slice / rollout cell\n"
        "   - same algorithm version\n"
        "   - same parameters ZIP\n"
        "\n"
        "2) Measure replay-vs-online parity **within one version**:\n"
        "   - `hv predict --algorithm-jar <jar> --algorithm-name <algo> \\\n"
        "        --parameter-path <predict.parameters.zip> --source-path <live-sample> \\\n"
        "        --dest-path replay.predict --samples <N>`\n"
        "   - compare replay scores to the logged online score field and compute MAE / top-1 agreement.\n"
        "\n"
        "3) Localize offline first:\n"
        "   - `hv audit --algorithm-jar <jar> --algorithm-name <algo> \\\n"
        "        --source-path <live-sample> --dest-path replay.audit --samples <N>`\n"
        "   - `hv predict --log-features ...` if you need dependency-level feature output.\n"
        "\n"
        "4) If offline looks healthy but live does not, instrument the live request path:\n"
        "   - log request/entity counts before any merge or fallback\n"
        "   - log failure strings and explicit fallback flags via `BulkScoreResponse.additionalProperties()`\n"
        "   - sample model-input rows in a compact TSV form for exact online/offline comparison\n"
        "\n"
        "5) Watch for common failure classes:\n"
        "   - wrong field keys / empty requested-field list\n"
        "   - normalized ids colliding after request construction\n"
        "   - fallback hiding a partial dependency failure\n"
        "   - offline replay using richer preattached payloads than live serving\n"
        "\n"
        "6) Roll out a diagnostic release first, verify the issue in shadow/canary, then cut a clean release.\n"
        "\n"
        "## Find relevant docs/code\n"
        '- `rg -n "online/offline parity|additionalProperties|feature_audit|compare-jsonl" python/hotvect/mcp/bundled_docs/docs python/hotvect`\n'
    ),
)


PROMPTS: dict[str, PromptDef] = {
    p.name: p
    for p in [
        _SETUP_CONFIG,
        _QUALITY_REGRESSION_BACKTEST,
        _MAJOR_BACKWARD_COMPAT_PREDICT_REGRESSION,
        _SAGEMAKER_BACKTEST_RUNBOOK,
        _RUN_AND_COMPARE_FEATURE_AUDITS,
        _PREDICT_WITH_FEATURE_LOGGING,
        _USE_HOTVECT_CACHING,
        _REUSE_EXISTING_OUTPUTS,
        _PERFORMANCE_REGRESSION_CHECK,
        _PERFORMANCE_INVESTIGATION_RUNBOOK,
        _PREDICT_SCORE_EQUIVALENCE_TESTING,
        _ORDERED_BACKTEST_WITH_PINNED_PARAMETERS,
        _ONLINE_OFFLINE_PARITY_INVESTIGATION,
    ]
}


def list_prompts() -> list[dict[str, Any]]:
    return [{"name": p.name, "description": p.description} for p in PROMPTS.values()]


def get_prompt(name: str) -> PromptDef:
    if name not in PROMPTS:
        raise ValueError(f"Unknown prompt: {name}")
    return PROMPTS[name]
