---
title: Runbook - Build and preview docs
description: Build the Hotvect docsite strictly and inspect a local preview
tags: [agents, runbook, docs, mkdocs]
---

# Runbook: Build and preview docs

## Facts (do not guess)

- MkDocs config: `mkdocs.yml` (repo root)
- Docs source: `python/hotvect/mcp/bundled_docs/docs/`
- Output directory: `site/`
- MkDocs is **strict** (`strict: true`): warnings fail the build.

## Build

From repo root:

```bash
bash python/scripts/docs.sh build
```

Equivalent Make target:

```bash
cd python
make docs-build
```

The script selects the repository-supported MkDocs versions and builds with `strict: true`. Treat any warning or
nonzero exit as a documentation failure.

## Preview

From repo root:

```bash
bash python/scripts/docs.sh serve
```

Equivalent Make target:

```bash
cd python
make docs-serve
```

Open `http://127.0.0.1:8001/` and inspect desktop and narrow layouts.

## Editing rules

- Add/move pages under `python/hotvect/mcp/bundled_docs/docs/`.
- Update navigation in `mkdocs.yml` (repo root).
- Prefer descriptive, stable headings because agents retrieve sections by their text.

Related: [Docs MCP](../../../guides/docs-mcp/index.md) for version-matched retrieval and
[documentation sanitization](../../contracts/documentation-sanitization/index.md) before an OSS-facing change.
