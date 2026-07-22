from __future__ import annotations

import os
import re
import sqlite3
import tempfile
from collections.abc import Iterable
from dataclasses import dataclass
from hashlib import sha256
from pathlib import Path
from typing import Any


def _safe_relpath(root: Path, path: Path) -> str:
    root_resolved = root.resolve()
    path_resolved = path.resolve()
    try:
        rel = path_resolved.relative_to(root_resolved)
    except ValueError as e:
        raise ValueError(f"Path is outside docs root: {path}") from e
    return rel.as_posix()


_H1_RE = re.compile(r"^\s*#\s+(?P<title>.+?)\s*$")


def _extract_title(markdown: str, *, fallback: str) -> str:
    for line in markdown.splitlines():
        m = _H1_RE.match(line)
        if m:
            return m.group("title").strip()
    return fallback


@dataclass(frozen=True)
class DocResource:
    uri: str
    name: str
    mime_type: str
    relpath: str


class DocsRepository:
    def __init__(
        self,
        *,
        docs_root: Path,
        base_uri: str = "hotvect://docs",
        sqlite_index: bool = False,
        sqlite_index_path: Path | None = None,
        hotvect_version: str = "unknown",
        reindex: bool = False,
    ) -> None:
        self.docs_root = docs_root.resolve()
        self.base_uri = base_uri.rstrip("/")
        self._cache: dict[str, dict[str, Any]] = {}
        self._sqlite_index = bool(sqlite_index)
        self._sqlite_index_path_override = sqlite_index_path.resolve() if sqlite_index_path else None
        self._hotvect_version = str(hotvect_version or "unknown")
        self._reindex_on_start = bool(reindex)

        if not self.docs_root.exists():
            raise FileNotFoundError(f"Docs root does not exist: {self.docs_root}")
        if not self.docs_root.is_dir():
            raise NotADirectoryError(f"Docs root is not a directory: {self.docs_root}")

        self._fts_db_path: Path | None = None
        self._fts_ready = False
        if self._sqlite_index:
            try:
                self._fts_db_path = self._sqlite_index_path_override or self._default_index_path()
            except OSError:
                # In restricted environments (e.g. read-only sandboxes), creating a cache dir
                # may fail. Treat SQLite FTS as an optional acceleration and gracefully
                # fall back to scan-based search.
                self._sqlite_index = False
                self._fts_db_path = None

            if self._fts_db_path and self._reindex_on_start:
                try:
                    self._build_or_rebuild_fts(reindex=True)
                except Exception:
                    # `--reindex` should never prevent the server from starting; fall back.
                    self._fts_ready = False
                    self._sqlite_index = False
                    self._fts_db_path = None

    def iter_markdown_files(self) -> Iterable[Path]:
        yield from sorted(self.docs_root.rglob("*.md"))

    def _default_index_path(self) -> Path:
        candidates: list[Path] = []

        env_cache = os.environ.get("HOTVECT_CACHE_DIR")
        if env_cache:
            candidates.append(Path(env_cache))

        xdg_cache = os.environ.get("XDG_CACHE_HOME")
        if xdg_cache:
            candidates.append(Path(xdg_cache) / "hotvect")

        # Prefer tmp-style directories by default. This avoids surprising writes to HOME in
        # sandboxed / restricted environments. For persistent indexes, pass an explicit
        # `sqlite_index_path` (or set HOTVECT_CACHE_DIR / XDG_CACHE_HOME).
        tmpdir = os.environ.get("TMPDIR")
        if tmpdir:
            candidates.append(Path(tmpdir) / "hotvect")

        candidates.append(Path(tempfile.gettempdir()) / "hotvect")

        def _writable_dir(d: Path) -> bool:
            try:
                d.mkdir(parents=True, exist_ok=True)
                probe = d / f".write_probe_{os.getpid()}"
                probe.write_bytes(b"ok")
                probe.unlink()
                return True
            except OSError:
                return False

        last_err: OSError | None = None
        cache_dir: Path | None = None
        for base in candidates:
            d = base / "cache"
            try:
                if not _writable_dir(d):
                    raise OSError(f"Cache directory not writable: {d}")
                cache_dir = d
                break
            except OSError as e:
                last_err = e
                continue

        if cache_dir is None:
            raise last_err or OSError("Unable to create cache directory for docs index")

        safe_version = re.sub(r"[^A-Za-z0-9_.-]+", "_", self._hotvect_version)
        # Include docs_root in the filename so multiple roots (e.g. tests, multiple checkouts)
        # don't accidentally share the same FTS DB for a given hotvect version.
        root_hash = sha256(str(self.docs_root).encode("utf-8")).hexdigest()[:12]
        return cache_dir / f"docs_fts_hotvect-{safe_version}-{root_hash}.sqlite"

    def _build_or_rebuild_fts(self, *, reindex: bool) -> None:
        if not self._fts_db_path:
            return
        db_path = self._fts_db_path
        force_recreate = False
        if reindex and db_path.exists():
            try:
                db_path.unlink()
            except OSError:
                force_recreate = True

        con = sqlite3.connect(str(db_path))
        try:
            con.execute("PRAGMA journal_mode=WAL;")
            con.execute("PRAGMA synchronous=NORMAL;")

            if force_recreate:
                con.execute("DROP TABLE IF EXISTS docs_fts;")
                con.execute("DROP TABLE IF EXISTS meta;")

            # A minimal schema version guard (future-proofing).
            con.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            schema_version = "1"
            existing = con.execute("SELECT value FROM meta WHERE key='schema_version'").fetchone()
            if existing and existing[0] != schema_version:
                # Recreate if schema changed.
                con.close()
                db_path.unlink(missing_ok=True)
                con = sqlite3.connect(str(db_path))
                con.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")

            con.execute("CREATE VIRTUAL TABLE IF NOT EXISTS docs_fts USING fts5(relpath UNINDEXED, title, body)")

            # Rebuild when the index was created for a different docs root or base URI.
            existing_root = con.execute("SELECT value FROM meta WHERE key='docs_root'").fetchone()
            existing_base_uri = con.execute("SELECT value FROM meta WHERE key='base_uri'").fetchone()
            expected_root = str(self.docs_root)
            expected_base_uri = str(self.base_uri)
            needs_rebuild = (
                existing_root is None
                or existing_root[0] != expected_root
                or existing_base_uri is None
                or existing_base_uri[0] != expected_base_uri
            )

            # If table is empty or the metadata mismatches, populate/rebuild it.
            row = con.execute("SELECT count(*) FROM docs_fts").fetchone()
            empty = (row[0] == 0) if row else True
            if empty or needs_rebuild:
                con.execute("BEGIN")
                try:
                    con.execute("DELETE FROM docs_fts;")
                    for path in self.iter_markdown_files():
                        rel = _safe_relpath(self.docs_root, path)
                        raw = path.read_bytes()
                        text = raw.decode("utf-8", errors="replace")
                        title = _extract_title(text, fallback=rel)
                        con.execute(
                            "INSERT INTO docs_fts(relpath, title, body) VALUES (?, ?, ?)",
                            (rel, title, text),
                        )
                    con.execute(
                        "INSERT OR REPLACE INTO meta(key,value) VALUES ('schema_version', ?)", (schema_version,)
                    )
                    con.execute("INSERT OR REPLACE INTO meta(key,value) VALUES ('docs_root', ?)", (expected_root,))
                    con.execute("INSERT OR REPLACE INTO meta(key,value) VALUES ('base_uri', ?)", (expected_base_uri,))
                    con.commit()
                except Exception:
                    con.rollback()
                    raise

            self._fts_ready = True
        finally:
            con.close()

    def _ensure_fts(self) -> bool:
        if not self._sqlite_index:
            return False
        if self._fts_ready:
            return True
        try:
            self._build_or_rebuild_fts(reindex=False)
            return self._fts_ready
        except Exception:
            # Any failure should gracefully fall back to scan search.
            self._fts_ready = False
            self._sqlite_index = False
            self._fts_db_path = None
            return False

    def _fts_query(self, query: str, *, mode: str = "and") -> str:
        # Keep it simple and robust: tokenize on whitespace and combine the terms.
        terms = [t for t in re.split(r"\s+", (query or "").strip()) if t]
        quoted = []
        for t in terms:
            t = t.replace('"', '""')
            quoted.append(f'"{t}"')
        if mode == "and":
            return " AND ".join(quoted)
        if mode == "or":
            return " OR ".join(quoted)
        raise ValueError(f"Unsupported FTS query mode: {mode!r}")

    def _search_sqlite(self, query: str, *, limit: int) -> dict[str, Any]:
        if not self._fts_db_path:
            return {"backend": "sqlite_fts5", "query": query, "matches": []}
        con = sqlite3.connect(str(self._fts_db_path))
        try:
            terms = [t for t in re.split(r"\s+", (query or "").strip()) if t]
            q = self._fts_query(query, mode="and")
            if not q:
                raise ValueError("query must contain at least one searchable term")

            def _run(match_expr: str):
                return con.execute(
                    """
                    SELECT
                      relpath,
                      title,
                      bm25(docs_fts, 0.0, 5.0, 1.0) AS rank,
                      snippet(docs_fts, 2, '[', ']', ' … ', 20) AS snippet
                    FROM docs_fts
                    WHERE docs_fts MATCH ?
                    ORDER BY rank
                    LIMIT ?
                    """,
                    (match_expr, int(limit)),
                ).fetchall()

            and_rows = _run(q)

            rows = list(and_rows)
            # FTS5 queries default to AND semantics. When users ask multi-topic questions, the
            # relevant information is often spread across multiple pages. Always run an OR
            # query as well (for multi-term input) and append additional results after the
            # stricter AND matches, de-duped, up to the requested limit.
            if len(terms) >= 2 and len(rows) < int(limit):
                or_rows = _run(self._fts_query(query, mode="or"))
                seen = {r[0] for r in rows}  # relpath
                for r in or_rows:
                    if r[0] in seen:
                        continue
                    rows.append(r)
                    seen.add(r[0])
                    if len(rows) >= int(limit):
                        break

            matches: list[dict[str, Any]] = []
            for relpath, title, rank, snippet in rows:
                uri = f"{self.base_uri}/{relpath}"
                matches.append(
                    {
                        "uri": uri,
                        "title": title,
                        "relpath": relpath,
                        # bm25: smaller is better; expose a higher-is-better score for readability.
                        "score": float(-rank) if rank is not None else 0.0,
                        "snippets": ([{"line": None, "text": snippet}] if snippet else []),
                    }
                )
            return {"backend": "sqlite_fts5", "query": query, "matches": matches}
        finally:
            con.close()

    def list_resources(self) -> list[DocResource]:
        out: list[DocResource] = []
        for path in self.iter_markdown_files():
            rel = _safe_relpath(self.docs_root, path)
            uri = f"{self.base_uri}/{rel}"
            out.append(DocResource(uri=uri, name=rel, mime_type="text/markdown", relpath=rel))
        return out

    def list_docs(self) -> list[dict[str, Any]]:
        docs: list[dict[str, Any]] = []
        for path in self.iter_markdown_files():
            doc = self._load_cached_doc(path)
            docs.append({"uri": doc["uri"], "title": doc["title"], "relpath": doc["relpath"]})
        return docs

    def health(self) -> dict[str, Any]:
        docs_count = sum(1 for _ in self.iter_markdown_files())
        return {
            "docs": {"baseUri": self.base_uri, "root": str(self.docs_root), "count": docs_count},
            "index": {
                "enabled": bool(self._sqlite_index),
                "backend": ("sqlite_fts5" if self._sqlite_index else "scan"),
                "ready": bool(self._fts_ready),
                "path": (str(self._fts_db_path) if self._fts_db_path else None),
            },
        }

    def resolve_uri_to_path(self, uri: str) -> Path:
        if not uri.startswith(self.base_uri + "/"):
            raise ValueError(f"Unsupported uri: {uri}")
        rel = uri[len(self.base_uri) + 1 :]
        if not rel or rel.startswith(("/", "\\")) or ".." in Path(rel).parts:
            raise ValueError(f"Invalid uri path: {uri}")
        return (self.docs_root / rel).resolve()

    def read_markdown(self, uri: str, *, max_bytes: int = 2_000_000) -> dict:
        path = self.resolve_uri_to_path(uri)
        # Enforce containment after resolution (defense in depth).
        rel = _safe_relpath(self.docs_root, path)
        if path.suffix.lower() != ".md":
            raise ValueError(f"Not a markdown resource: {uri}")
        if not path.exists():
            raise FileNotFoundError(f"Not found: {uri}")
        if not path.is_file():
            raise ValueError(f"Not a file: {uri}")

        raw = path.read_bytes()
        if len(raw) > max_bytes:
            raise ValueError(f"Document too large: {uri} ({len(raw)} bytes)")
        text = raw.decode("utf-8", errors="replace")
        title = _extract_title(text, fallback=rel)
        return {"uri": f"{self.base_uri}/{rel}", "mimeType": "text/markdown", "name": title, "text": text}

    def _load_cached_doc(self, path: Path) -> dict[str, Any]:
        rel = _safe_relpath(self.docs_root, path)
        stat = path.stat()
        cached = self._cache.get(rel)
        if cached and cached.get("mtime_ns") == stat.st_mtime_ns and cached.get("size") == stat.st_size:
            return cached

        raw = path.read_bytes()
        text = raw.decode("utf-8", errors="replace")
        title = _extract_title(text, fallback=rel)
        lower = text.casefold()
        doc = {
            "relpath": rel,
            "uri": f"{self.base_uri}/{rel}",
            "title": title,
            "text": text,
            "lower": lower,
            "mtime_ns": stat.st_mtime_ns,
            "size": stat.st_size,
        }
        self._cache[rel] = doc
        return doc

    def search(self, query: str, *, limit: int = 20) -> dict[str, Any]:
        q = (query or "").strip()
        if not q:
            raise ValueError("query must be non-empty")
        if limit <= 0 or limit > 100:
            raise ValueError("limit must be between 1 and 100")

        if self._ensure_fts():
            return self._search_sqlite(q, limit=limit)

        terms = [t for t in re.split(r"\s+", q.casefold()) if t]
        if not terms:
            raise ValueError("query must contain at least one searchable term")

        matches: list[dict[str, Any]] = []
        for path in self.iter_markdown_files():
            doc = self._load_cached_doc(path)
            text_lower: str = doc["lower"]
            title_lower: str = str(doc["title"]).casefold()

            score = 0
            for term in terms:
                c = text_lower.count(term)
                if c:
                    score += c
                if term in title_lower:
                    score += 5

            phrase = q.casefold()
            if phrase in text_lower:
                score += 10

            if score <= 0:
                continue

            # Snippet around first match (phrase preferred).
            pos = text_lower.find(phrase)
            if pos < 0:
                pos = min([p for p in (text_lower.find(t) for t in terms) if p >= 0], default=-1)

            snippet = None
            if pos >= 0:
                line = text_lower[:pos].count("\n") + 1
                start = max(0, pos - 80)
                end = min(len(doc["text"]), pos + 200)
                snippet_text = doc["text"][start:end].replace("\n", " ").strip()
                snippet = {"line": line, "text": snippet_text}

            matches.append(
                {
                    "uri": doc["uri"],
                    "title": doc["title"],
                    "relpath": doc["relpath"],
                    "score": score,
                    "snippets": ([snippet] if snippet else []),
                }
            )

        matches.sort(key=lambda m: (-int(m["score"]), str(m["relpath"])))
        return {"backend": "scan", "query": q, "matches": matches[:limit]}


def bundled_docs_traversable():
    """Return the bundled docs directory as an importlib.resources Traversable."""
    from importlib.resources import files

    return files("hotvect.mcp.bundled_docs").joinpath("docs")
