# Encoding Output Layout (Directory-Based Part Files)

Hotvect encoding outputs are **always directory-based** and consist of one or more **part files** inside that directory.

## Decision / Contract

- `--dest` / `--dest-path` is always a **directory**, not a file path.
- Output files are written as zero-padded `part-<index><ext>` files inside the destination directory.
  - Single-shard output still uses a directory and produces exactly `part-00000<ext>`.
- The file extension `<ext>` is provided by the encoder (e.g. `.jsonl`, `.tsv`, `.tfrecord`).

## Rationale

- Consistent output structure across unordered/ordered and single-/multi-shard modes.
- Eliminates user-controlled filename patterns (and associated edge cases).
- Makes downstream tooling simpler: “read the directory, then resolve shards”.

## Downstream Consumers

If downstream code expects a single file:

- Prefer globbing `part-*<ext>` in the destination directory.
- If the consumer cannot handle multiple files (e.g. certain trainers), enforce `writer_num_shards=1` and read `part-00000<ext>`.

### CatBoost Training Note

`catboost_train` can merge compatible plain TSV shards before fitting. Treat that as layout compatibility, not a
performance optimization; keep one shard unless an end-to-end benchmark proves a different setting for the intended
workload.

## Related Docs

- [CLI reference](../../reference/cli/index.md)
- [v9 to v10 migration](../../migrations/v9-to-v10/index.md)
