from __future__ import annotations

import argparse
import os
from importlib.resources import as_file
from pathlib import Path
from typing import Any

from mcp.server.fastmcp import FastMCP
from pydantic import BaseModel, ConfigDict

import hotvect

from .docs_repo import DocsRepository, bundled_docs_traversable
from .prompts import PROMPTS


class SearchDocsResult(BaseModel):
    model_config = ConfigDict(extra="allow")

    backend: str
    query: str
    matches: list[dict[str, Any]]


class ListDocsResult(BaseModel):
    model_config = ConfigDict(extra="allow")

    docs: list[dict[str, Any]]


class HealthResult(BaseModel):
    model_config = ConfigDict(extra="allow")

    docs: dict[str, Any]
    index: dict[str, Any]
    server: dict[str, Any]


def _parse_bool_env(value: str | None) -> bool | None:
    if value is None:
        return None
    v = value.strip().casefold()
    if v in {"1", "true", "t", "yes", "y", "on"}:
        return True
    if v in {"0", "false", "f", "no", "n", "off"}:
        return False
    # If set to some unexpected value, don't treat it as authoritative.
    return None


def create_fastmcp_server(*, repo: DocsRepository) -> FastMCP:
    hotvect_version = getattr(hotvect, "__version__", "unknown")
    server = FastMCP(
        name="hotvect-docs-mcp",
        instructions=(
            "Hotvect documentation-only MCP server.\n\n"
            "Capabilities:\n"
            "- Read Markdown docs as MCP resources (read-only)\n"
            "- Search docs via the `search_docs` tool\n"
            "- List docs via the `list_docs` tool\n"
            "- Provide runbook-style prompts\n"
        ),
    )

    # FastMCP defaults serverInfo.version to the installed `mcp` package version.
    # Expose the Hotvect version instead.
    server._mcp_server.version = str(hotvect_version)  # noqa: SLF001

    @server.tool(
        name="search_docs",
        title="Search Docs",
        description="Search local Hotvect markdown documentation (read-only).",
        structured_output=True,
    )
    def search_docs(query: str, limit: int = 20) -> SearchDocsResult:
        return SearchDocsResult.model_validate(repo.search(query, limit=limit))

    @server.tool(
        name="list_docs",
        title="List Docs",
        description="List available documentation pages (read-only).",
        structured_output=True,
    )
    def list_docs() -> ListDocsResult:
        docs = repo.list_docs()
        return ListDocsResult(docs=docs)

    @server.tool(
        name="health",
        title="Health",
        description="Return MCP server health and indexing status (read-only).",
        structured_output=True,
    )
    def health() -> HealthResult:
        result = repo.health()
        result["server"] = {"name": "hotvect-docs-mcp", "version": str(hotvect_version)}
        return HealthResult.model_validate(result)

    for prompt in PROMPTS.values():
        server.prompt(
            name=prompt.name,
            title=None,
            description=prompt.description,
        )(_make_prompt_fn(prompt.text))

    for r in repo.list_resources():
        server.resource(
            r.uri,
            name=r.name,
            title=r.name,
            description=None,
            mime_type=r.mime_type,
        )(_make_resource_reader_fn(repo, r.uri))

    return server


def _make_prompt_fn(text: str):
    def _prompt() -> str:
        return text

    return _prompt


def _make_resource_reader_fn(repo: DocsRepository, uri: str):
    def _read() -> str:
        return repo.read_markdown(uri)["text"]

    return _read


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(
        prog="hv-mcp",
        description="Hotvect documentation-only MCP server (stdio, newline-delimited JSON-RPC).",
    )
    parser.add_argument(
        "--reindex",
        action="store_true",
        help="Rebuild the local docs search index before serving (for development / troubleshooting).",
    )
    parser.add_argument(
        "--sqlite-index",
        action=argparse.BooleanOptionalAction,
        default=None,
        help=(
            "Enable SQLite FTS indexing for faster search (may write a local cache file). "
            "Defaults to enabled; use --no-sqlite-index to disable."
        ),
    )
    parser.add_argument(
        "--sqlite-index-path",
        default=None,
        help="Optional explicit path for the SQLite index file (implies --sqlite-index).",
    )
    args = parser.parse_args(argv or [])
    if args.sqlite_index is False and args.sqlite_index_path:
        parser.error("--sqlite-index-path cannot be used with --no-sqlite-index")

    docs = bundled_docs_traversable()
    with as_file(docs) as docs_root:
        env_override = _parse_bool_env(os.environ.get("HOTVECT_MCP_SQLITE_INDEX"))
        if args.sqlite_index is not None:
            sqlite_index = bool(args.sqlite_index)
        elif args.sqlite_index_path:
            sqlite_index = True
        elif env_override is not None:
            sqlite_index = env_override
        else:
            sqlite_index = True

        repo = DocsRepository(
            docs_root=Path(docs_root).resolve(),
            sqlite_index=sqlite_index,
            sqlite_index_path=Path(args.sqlite_index_path).expanduser() if args.sqlite_index_path else None,
            hotvect_version=getattr(hotvect, "__version__", "unknown"),
            reindex=bool(args.reindex and sqlite_index),
        )
        server = create_fastmcp_server(repo=repo)
        server.run(transport="stdio")
