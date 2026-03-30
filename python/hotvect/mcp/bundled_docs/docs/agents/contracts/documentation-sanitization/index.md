---
title: Documentation sanitization
description: Rules for keeping docs safe (synthetic examples, no PII, no trademarks, no internal identifiers)
tags: [agents, docs, sanitization, privacy, security]
---

# Documentation sanitization

This docsite must be safe to share. When writing or updating docs, treat all example values as **public**.

## Core rules

### 1) Use synthetic IDs (obviously fake)

- **UUIDs**: use the nil UUID `00000000-0000-0000-0000-000000000000` (or another clearly fake constant).
- **Item/SKU/action IDs**: use obviously fake IDs (for example `ABC123H01-J11`), not values that resemble production identifiers.
- **Job names / prefixes**: use generic names like `exp-...` (avoid team/org names).

### 2) Avoid trademarks and real brand names

- Do not use real brand names in example payloads.
- Use placeholders like `FAKE_BRAND_A`, `FAKE_CATEGORY_A`, `FAKE_VALUE_A`.

### 3) Avoid internal dataset/table names and feature engineering names

- Use generic dataset prefixes like `example_training_data`, `example_test_data`, `example_user_features`.
- Use generic feature keys like `feature_numeric_01`, `feature_categorical_01`, `feature_text_01`.

### 4) Use generic infrastructure placeholders

- **S3**: use `s3://example-bucket/...` (bucket names should be syntactically valid; avoid underscores).
- **IAM ARNs**: use placeholders like `arn:aws:iam::123456789012:role/example-role`.
- **Git URLs**:
  - Hotvect itself: the public repo URL is fine.
  - Example algorithm repos: use `https://github.com/example-org/example-algorithm.git` (avoid org-internal URLs).
- **Emails/domains**: use `example.com` / `user@example.com`.

### 5) Prefer org-neutral CLI setup instructions

- Do not mention company-specific credential helpers or internal tooling.
- Use generic AWS guidance such as `aws sso login` / `aws sts get-caller-identity`.

## Reviewer checklist (fast scans)

From repo root:

```bash
# UUIDs
rg -n -i '\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b' python/hotvect/mcp/bundled_docs/docs

# AWS + S3
rg -n '\\bs3://' python/hotvect/mcp/bundled_docs/docs
rg -n '\\barn:aws' python/hotvect/mcp/bundled_docs/docs

# URLs + emails
rg -n 'https?://' python/hotvect/mcp/bundled_docs/docs
rg -n '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}' python/hotvect/mcp/bundled_docs/docs
```

If you find anything that looks real or internal, replace it with a clearly synthetic placeholder.
