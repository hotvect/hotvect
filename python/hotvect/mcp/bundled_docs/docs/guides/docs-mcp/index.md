# Use Hotvect Docs MCP (Codex)

Hotvect ships a **documentation-only** MCP server. It is read-only: it can **search** and **read** the bundled Markdown docs, and expose a few prompt templates to help agents follow common runbooks.

## Protocol

This server uses **newline-delimited JSON-RPC (NDJSON)** over stdio: one JSON object per line.

## Start the server

If you installed Hotvect (wheel/venv), the `hv-mcp` entrypoint should be available:

```bash
hv-mcp
```

### Source checkout (recommended)

From a source checkout, run `hv-mcp` using the repo venv Python (so dependencies like `mcp` are available):

```bash
"$(pwd)/python/.venv/bin/python" "$(pwd)/python/bin/hv-mcp"
```

This is the most reliable way to run the server locally without depending on your global Python environment.

### Force rebuilding the local search index

The docs server caches a local SQLite search index by Hotvect version (enabled by default). If you’re developing docs locally and want to force a rebuild:

```bash
hv-mcp --reindex
```

### Use an explicit SQLite index path (optional)

If you want deterministic caching (or you’re debugging index issues), set an explicit index path:

```bash
hv-mcp --sqlite-index-path /tmp/hotvect-docs-index.sqlite
```

### Disable the SQLite index (fallback to scan)

If you want to guarantee **no local index writes**, disable indexing:

```bash
hv-mcp --no-sqlite-index
```

You can also control this via environment variable:

```bash
# disable
HOTVECT_MCP_SQLITE_INDEX=0 hv-mcp

# enable
HOTVECT_MCP_SQLITE_INDEX=1 hv-mcp
```

If the cache/temp directory is not writable, the server automatically falls back to scan-based search.

## Connect Codex

Add it as a stdio MCP server:

```bash
codex mcp add hotvectDocs -- hv-mcp
```

Verify:

```bash
codex mcp list
```

### If Codex can’t find `hv-mcp`

If Codex reports `No such file or directory`, it means the configured command is not resolvable from Codex’s environment.

Use an **absolute path** to the server command. From the repo root:

```bash
codex mcp add hotvectDocs -- "$(pwd)/python/bin/hv-mcp"
```

If your Hotvect repo uses a dedicated Python venv (recommended), you can pin the Python interpreter too:

```bash
codex mcp add hotvectDocs -- "$(pwd)/python/.venv/bin/python" "$(pwd)/python/bin/hv-mcp"
```

If you want to avoid local index writes in Codex, add `--no-sqlite-index`:

```bash
codex mcp add hotvectDocs -- "$(pwd)/python/.venv/bin/python" "$(pwd)/python/bin/hv-mcp" --no-sqlite-index
```

## What it provides

- **Docs resources**: read Markdown pages by URI (read-only).
- **Search tool**: `search_docs` with `{ "query": "...", "limit": 20 }`.
- **List tool**: `list_docs` with `{}` (returns `{ "docs": [...] }`).
- **Prompts**: runbooks available via MCP prompts (useful for agent workflows).

## Tool examples

### List docs

Call:

```json
{}
```

Result (shape):

```json
{
  "docs": [
    {
      "uri": "hotvect://docs/agents/contracts/index.md",
      "title": "Contracts (Agent-First)",
      "relpath": "agents/contracts/index.md"
    }
  ]
}
```

### Search docs

Call:

```json
{ "query": "backtest", "limit": 5 }
```

Result (shape):

```json
{
  "backend": "sqlite_fts5",
  "query": "backtest",
  "matches": [
    {
      "uri": "hotvect://docs/guides/sagemaker-backtests/index.md",
      "title": "Run backtests on AWS SageMaker",
      "relpath": "guides/sagemaker-backtests/index.md",
      "score": 12,
      "snippets": [{ "line": 42, "text": "..." }]
    }
  ]
}
```

## Prompts

This server also exposes high-signal runbook prompts (client support varies). Current prompt names include:

- `setup_config`
- `quality_regression_backtest`
- `major_backward_compat_predict_regression`
- `sagemaker_backtest_runbook`
- `run_and_compare_feature_audits`
- `predict_with_feature_logging`
- `use_hotvect_caching`
- `reuse_existing_outputs`
- `performance_regression_check`
- `performance_investigation_runbook`
- `predict_score_equivalence_testing`
- `ordered_backtest_with_pinned_parameters`

## Tips

- For local grep-style search (outside MCP), search the docs directory:
  - `rg -n "<pattern>" python/hotvect/mcp/bundled_docs/docs`
