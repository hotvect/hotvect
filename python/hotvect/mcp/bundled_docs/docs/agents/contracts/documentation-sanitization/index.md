---
title: Documentation sanitization
description: Rules for keeping docs and examples safe through synthetic values, no PII, and no internal identifiers
tags: [agents, docs, sanitization, privacy, security]
---

# Documentation sanitization

This docsite must be safe to share. When writing or updating docs, treat all example values as **public**.

## Core rules

### 1) Use synthetic IDs (obviously fake)

- **UUIDs**: use the nil UUID `00000000-0000-0000-0000-000000000000` (or another clearly fake constant).
- **Item/SKU/action IDs**: use obviously fake IDs (for example `ABC123H01-J11`), not values that resemble production identifiers.
- **Job names / prefixes**: use generic names like `exp-...` (avoid team/org names).

### 2) Keep example domains synthetic

- Do not use real product, retailer, or application names in example payloads.
- Use placeholders like `FAKE_BRAND_A`, `FAKE_CATEGORY_A`, `FAKE_VALUE_A`.
- Accurate library names and license attribution are allowed when necessary to document compatibility or a bundled
  asset's source.

### 3) Avoid internal dataset/table names and feature engineering names

- Use generic dataset prefixes like `example_training_data`, `example_test_data`, `example_context_records`.
- Use generic feature keys like `feature_numeric_01`, `feature_categorical_01`, `feature_text_01`.

### 4) Use generic infrastructure placeholders

- **S3**: use `s3://example-bucket/...` (bucket names should be syntactically valid; avoid underscores).
- **IAM ARNs**: use placeholders like `arn:aws:iam::123456789012:role/example-role`.
- **Git URLs**:
  - Hotvect itself: the public repo URL is fine.
  - Example algorithm repos: use `https://github.com/example-org/example-algorithm.git` (avoid org-internal URLs).
- **Emails/domains**: use `example.com` / `user@example.com`.

### 5) Use obviously synthetic time values

- Use a deliberately old date such as `2000-01-08` in examples, partitions, IDs, and timestamps.
- Do not copy real execution timestamps, commit-like date suffixes, or current production analysis dates.

### 6) Prefer org-neutral CLI setup instructions

- Do not mention company-specific credential helpers or internal tooling.
- Use generic AWS guidance such as `aws sso login` / `aws sts get-caller-identity`.

## Reviewer checklist (fast scans)

From repo root:

```bash
DOC_SURFACES=(
  README.md
  CHANGELOG.md
  hotvect-algorithm-demo/README.md
  hotvect-algorithm-serve/README.md
  examples
  python/hotvect/mcp/bundled_docs/docs
)

# UUIDs
rg -n -i '\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b' "${DOC_SURFACES[@]}"

# AWS + S3
rg -n '\\bs3://' "${DOC_SURFACES[@]}"
rg -n '\\barn:aws' "${DOC_SURFACES[@]}"

# URLs + emails
rg -n 'https?://' "${DOC_SURFACES[@]}"
rg -n '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}' "${DOC_SURFACES[@]}"

# Dates and timestamps: inspect every match and keep examples deliberately synthetic
rg -n '\\b20[1-9][0-9]-[0-9]{2}-[0-9]{2}([T ][0-9]{2}:[0-9]{2})?' "${DOC_SURFACES[@]}"
```

If you find anything that looks real or internal, replace it with a clearly synthetic placeholder.

Next, return to [Agent contracts](../index.md) or use the [docs build runbook](../../runbooks/docs-build/index.md) to
render and inspect the sanitized result.
