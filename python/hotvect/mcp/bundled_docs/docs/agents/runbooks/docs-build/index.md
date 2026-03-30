---
title: Runbook: Build & Preview Docs
description: Copy/paste commands for building and previewing the Hotvect docsite locally (agent-first)
tags: [agents, runbook, docs, mkdocs]
---

# Runbook: Build & Preview Docs

## Facts (do not guess)

- MkDocs config: `mkdocs.yml` (repo root)
- Docs source: `python/hotvect/mcp/bundled_docs/docs/`
- Output directory: `site/`
- MkDocs is **strict** (`strict: true`): warnings fail the build.

## Build docs (fast path)

From repo root:

```bash
PYENV_VERSION=mkdocs mkdocs build -q
```

Alternative (repo script):

```bash
bash python/scripts/docs.sh build
```

Alternative (Make target):

```bash
cd python
make docs-build
```

## Build docs (fallback via `uv`)

If `mkdocs` is not on PATH but you have `uv`:

```bash
cd python
uv run --with "mkdocs<2" --with "mkdocs-material<10" --with pymdown-extensions \
  mkdocs build -f ../mkdocs.yml -q
```

## Preview locally (serve)

From repo root:

```bash
PYENV_VERSION=mkdocs mkdocs serve -a 127.0.0.1:8001
```

Alternative (repo script):

```bash
bash python/scripts/docs.sh serve
```

Alternative (Make target):

```bash
cd python
make docs-serve
```

Fallback via `uv`:

```bash
cd python
uv run --with "mkdocs<2" --with "mkdocs-material<10" --with pymdown-extensions \
  mkdocs serve -f ../mkdocs.yml -a 127.0.0.1:8001
```

## Editing rules

- Add/move pages under `python/hotvect/mcp/bundled_docs/docs/`.
- Update navigation in `mkdocs.yml` (repo root).
- Keep headings stable (agents depend on exact section names for retrieval).
