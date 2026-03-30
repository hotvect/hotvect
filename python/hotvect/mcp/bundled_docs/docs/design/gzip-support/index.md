# GZIP Support

## Current Behavior

- **GZIP input** is supported where applicable (e.g. reading `.json.gz` / `.jsonl.gz` inputs).
- **GZIP output** is not supported. Hotvect writes outputs uncompressed (and, for encoders, directory-based sharded outputs).

## Rationale

GZIP output added complexity (naming/path handling, compatibility with sharded directories) without being a relied-upon production feature. Keeping GZIP input support preserves common workflows for training/evaluation data stored as gzip-compressed files.
