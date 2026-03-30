"""
Hotvect documentation-only MCP (Model Context Protocol) server.

This MCP server intentionally exposes *read-only* documentation resources from the local filesystem.
It does not wrap or invoke `hv`/`hv-ext` commands.

For faster search, the MCP server uses a SQLite full-text index by default (writing to a temp/cache
directory). If the cache directory is not writable, it falls back to scan-based search.
"""
