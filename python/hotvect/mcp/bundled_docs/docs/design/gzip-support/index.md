# GZIP support

## Inputs

Hotvect reads supported gzip-compressed data inputs, including `.json.gz` and `.jsonl.gz`, where the command accepts
those source formats.

## Output

Local commands and single-job SageMaker one-shot runs write uncompressed output. Gzip output is available only for
parallel SageMaker one-shot `predict` and `audit` runs:

```bash
hv predict ... \
  --sagemaker \
  --job-parallelism 8 \
  --compression gzip
```

That mode publishes zero-padded `.jsonl.gz` part files directly under `--dest-path`, such as
`part-00003-00012.jsonl.gz`. `none` is the default compression value.

`encode` rejects `--compression`; so do all non-parallel and non-`audit`/`predict` one-shot modes. See
[Parallel SageMaker one-shot runs](../../guides/parallel-sagemaker-one-shot/index.md) for the complete submission and
verification contract.

## Rationale

Compression is deliberately scoped to the fan-out S3 output path, where it reduces transferred and stored JSONL data
without changing a local command's directory/part-file contract.
