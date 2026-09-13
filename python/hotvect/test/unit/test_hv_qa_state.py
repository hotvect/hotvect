from __future__ import annotations

import json
from pathlib import Path

import pytest

from hotvect.qa.commands.run import _plan_sha256, _validate_frozen_plan
from hotvect.qa.state import append_jsonl, lock_run, read_json, write_json


def test_qa_state_writes_json_and_decisions(tmp_path: Path) -> None:
    state_path = tmp_path / "status.json"
    decisions_path = tmp_path / "decisions.jsonl"

    write_json(state_path, {"state": "running"})
    append_jsonl(decisions_path, {"event": "started"})

    assert read_json(state_path) == {"state": "running"}
    assert json.loads(decisions_path.read_text(encoding="utf-8")) == {"event": "started"}
    assert not list(tmp_path.glob(".status.json.*"))


def test_qa_state_rejects_concurrent_run(tmp_path: Path) -> None:
    with lock_run(tmp_path):
        with pytest.raises(ValueError, match="QA run is already active"):
            with lock_run(tmp_path):
                pass


def test_qa_state_rejects_modified_plan() -> None:
    plan = {"run_id": "qa-run", "criteria": {"id": "noninferiority"}}
    status = {"plan_sha256": _plan_sha256(plan)}
    plan["criteria"] = {"id": "superiority"}

    with pytest.raises(ValueError, match="QA run plan was modified after start"):
        _validate_frozen_plan(plan, status)
