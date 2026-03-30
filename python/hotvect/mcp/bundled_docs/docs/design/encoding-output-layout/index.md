# Encoding Output Layout (Sharded Directories)

Hotvect encoding outputs are **always directory-based** and consist of one or more **shard files** inside that directory.

## Decision / Contract

- `--dest` / `--dest-path` is always a **directory**, not a file path.
- Output files are written as `shard_<index><ext>` inside the destination directory.
  - Single-shard output still uses a directory and produces exactly `shard_0<ext>`.
- The file extension `<ext>` is provided by the encoder (e.g. `.jsonl`, `.tsv`, `.tfrecord`).

## Rationale

- Consistent output structure across unordered/ordered and single-/multi-shard modes.
- Eliminates user-controlled filename patterns (and associated edge cases).
- Makes downstream tooling simpler: “read the directory, then resolve shards”.

## Downstream Consumers

If downstream code expects a single file:

- Prefer globbing `shard_*<ext>` in the destination directory.
- If the consumer cannot handle multiple files (e.g. certain trainers), enforce `writer_num_shards=1` and read `shard_0<ext>`.

### CatBoost Training Note

We explicitly tried the obvious optimization of:

- encode to multiple shards with `writer_num_shards > 1`
- merge those shards deterministically back into one TSV inside `catboost_train`
- then train CatBoost on the merged file

On **2026-03-24**, that path was benchmarked on the prod-style `v81` training contract (`7` training days, `ml.m5.12xlarge`, train-only):

- single-shard control (`writer_num_shards=1`): `8484s` SageMaker wall clock
- merge-shards treatment (`writer_num_shards=16` + deterministic merge): `8504s` SageMaker wall clock

That is effectively neutral to slightly worse, so we are **not** treating multi-shard CatBoost training as a recommended optimization path right now. For CatBoost training jobs, keep `writer_num_shards=1` unless you have a new benchmark that proves otherwise.

## Related Docs

- CLI usage: `reference/cli/index.md`
- Migration guide: `archive/migrations/v9-to-v10/index.md`
