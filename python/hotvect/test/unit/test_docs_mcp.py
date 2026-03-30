import os
import tempfile
import unittest
from importlib.resources import as_file
from pathlib import Path
from unittest import mock

import anyio

from hotvect.mcp.docs_repo import DocsRepository, bundled_docs_traversable
from hotvect.mcp.server import create_fastmcp_server


class TestDocsRepository(unittest.TestCase):
    def test_bundled_docs_traversable_resolves(self):
        docs = bundled_docs_traversable()
        with as_file(docs) as p:
            p = Path(p)
            self.assertTrue(p.exists())
            self.assertTrue(p.is_dir())
            self.assertTrue((p / "index.md").exists())

    def test_list_and_read_markdown(self):
        with tempfile.TemporaryDirectory() as td:
            docs = Path(td)
            (docs / "a.md").write_text("# A Title\n\nHello\n")
            (docs / "sub").mkdir()
            (docs / "sub" / "b.md").write_text("# B Title\n\nWorld\n")

            repo = DocsRepository(docs_root=docs)
            resources = repo.list_resources()
            self.assertEqual([r.relpath for r in resources], ["a.md", "sub/b.md"])
            self.assertEqual(resources[0].uri, "hotvect://docs/a.md")

            a = repo.read_markdown("hotvect://docs/a.md")
            self.assertEqual(a["mimeType"], "text/markdown")
            self.assertIn("A Title", a["name"])
            self.assertIn("Hello", a["text"])

    def test_rejects_path_traversal(self):
        with tempfile.TemporaryDirectory() as td:
            docs = Path(td)
            (docs / "a.md").write_text("# A\n")
            repo = DocsRepository(docs_root=docs)
            with self.assertRaises(ValueError):
                repo.read_markdown("hotvect://docs/../x.md")

    def test_sqlite_index_uses_tmp_cache_if_home_unwritable(self):
        with tempfile.TemporaryDirectory() as td:
            docs = Path(td)
            (docs / "a.md").write_text("# A\n\nHello world\n")
            with mock.patch.dict(os.environ, {"HOME": "/var/empty"}, clear=False):
                repo = DocsRepository(docs_root=docs, sqlite_index=True, hotvect_version="test")
                result = repo.search("hello", limit=5)
                self.assertIn(result.get("backend"), {"scan", "sqlite_fts5"})

    def test_search_sqlite_or_fallback(self):
        with tempfile.TemporaryDirectory() as td:
            docs = Path(td)
            # One doc contains both terms, one contains only one term.
            (docs / "both.md").write_text("# Both\n\nalpha beta\n")
            (docs / "alpha.md").write_text("# Alpha\n\nalpha only\n")
            repo = DocsRepository(docs_root=docs, sqlite_index=True, hotvect_version="test")

            # AND match should be first, but OR-only matches may be appended after it.
            r_and = repo.search("alpha beta", limit=10)
            relpaths = [m["relpath"] for m in r_and["matches"]]
            self.assertEqual(relpaths[0], "both.md")
            self.assertEqual(set(relpaths), {"both.md", "alpha.md"})

            # A query that can never match both terms in one doc should return OR matches.
            r_or = repo.search("alpha gamma", limit=10)
            self.assertEqual({m["relpath"] for m in r_or["matches"]}, {"alpha.md", "both.md"})

    def test_search_sqlite_and_then_or_ordering(self):
        with tempfile.TemporaryDirectory() as td:
            docs = Path(td)
            (docs / "both.md").write_text("# Both\n\nalpha beta\n")
            (docs / "alpha.md").write_text("# Alpha\n\nalpha only\n")
            (docs / "beta.md").write_text("# Beta\n\nbeta only\n")
            repo = DocsRepository(docs_root=docs, sqlite_index=True, hotvect_version="test")

            r = repo.search("alpha beta", limit=10)
            relpaths = [m["relpath"] for m in r["matches"]]
            # AND match must appear first; OR-only matches follow.
            self.assertEqual(relpaths[0], "both.md")
            self.assertEqual(set(relpaths), {"both.md", "alpha.md", "beta.md"})


class TestDocsMcpServer(unittest.TestCase):
    def test_resources_list_and_read(self):
        with tempfile.TemporaryDirectory() as td:
            docs = Path(td)
            (docs / "a.md").write_text("# A\n\nHello\n")
            repo = DocsRepository(docs_root=docs)
            server = create_fastmcp_server(repo=repo)

            resources = anyio.run(server.list_resources)
            self.assertEqual([str(r.uri) for r in resources], ["hotvect://docs/a.md"])

            uri = str(resources[0].uri)
            contents = list(anyio.run(server.read_resource, uri))
            self.assertEqual(contents[0].mime_type, "text/markdown")
            self.assertIn("Hello", contents[0].content)

    def test_tools_search_docs(self):
        with tempfile.TemporaryDirectory() as td:
            docs = Path(td)
            (docs / "a.md").write_text("# A\n\nHello world\n")
            (docs / "b.md").write_text("# B\n\nSomething else\n")
            repo = DocsRepository(docs_root=docs, sqlite_index=False)
            server = create_fastmcp_server(repo=repo)

            tools = anyio.run(server.list_tools)
            self.assertEqual([t.name for t in tools], ["search_docs", "list_docs", "health"])

            unstructured, payload = anyio.run(server.call_tool, "search_docs", {"query": "hello"})
            self.assertTrue(unstructured)
            self.assertEqual(payload["query"], "hello")
            self.assertEqual(len(payload["matches"]), 1)
            self.assertTrue(payload["matches"][0]["uri"].endswith("/a.md"))
            self.assertIn(payload.get("backend"), {"scan", "sqlite_fts5"})

    def test_tools_list_docs_and_health(self):
        with tempfile.TemporaryDirectory() as td:
            docs = Path(td)
            (docs / "a.md").write_text("# A\n\nHello world\n")
            (docs / "sub").mkdir()
            (docs / "sub" / "b.md").write_text("# B\n\nSomething else\n")
            (docs / "c.md").write_text("# C\n\nThird\n")
            repo = DocsRepository(docs_root=docs, sqlite_index=False)
            server = create_fastmcp_server(repo=repo)

            _, payload = anyio.run(server.call_tool, "list_docs", {})
            self.assertEqual(len(payload["docs"]), 3)
            self.assertEqual([d["relpath"] for d in payload["docs"]], ["a.md", "c.md", "sub/b.md"])
            self.assertNotIn("nextCursor", payload)

            _, health_payload = anyio.run(server.call_tool, "health", {})
            self.assertIn("docs", health_payload)
            self.assertEqual(health_payload["docs"]["count"], 3)
            self.assertIn("index", health_payload)
            self.assertIn("server", health_payload)

    def test_prompts_list_and_get(self):
        with tempfile.TemporaryDirectory() as td:
            docs = Path(td)
            (docs / "a.md").write_text("# A\n\nHello world\n")
            repo = DocsRepository(docs_root=docs, sqlite_index=False)
            server = create_fastmcp_server(repo=repo)

            prompts = anyio.run(server.list_prompts)
            names = [p.name for p in prompts]
            self.assertIn("setup_config", names)
            self.assertIn("quality_regression_backtest", names)
            self.assertIn("sagemaker_backtest_runbook", names)

            setup = anyio.run(server.get_prompt, "setup_config", None)
            text = setup.messages[0].content.text
            self.assertIn("hv-ext config init", text)
