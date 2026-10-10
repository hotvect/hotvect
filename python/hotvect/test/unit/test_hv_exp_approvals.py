from __future__ import annotations

import argparse
import json
from datetime import datetime
from types import SimpleNamespace

import pytest

from hotvect.experiment_management.commands import ExperimentCommand


def _capture_print(monkeypatch):
    printed = {}
    monkeypatch.setattr("builtins.print", lambda s: printed.setdefault("out", s))
    return printed


def test_hv_exp_parser_exposes_native_mutations_and_approval_inspection():
    parser = argparse.ArgumentParser()
    ExperimentCommand.register_parser(parser)

    args = parser.parse_args(
        [
            "experiment",
            "ramp-up",
            "--experiment-id",
            "42",
            "--percentage",
            "25",
            "--reason",
            "Increase the experiment ramp-up.",
        ]
    )

    approval = parser.parse_args(["approval", "get", "00000000-0000-4000-8000-000000000001"])

    assert args.subcommand == "experiment"
    assert args.experiment_subcommand == "ramp-up"
    assert args.experiment_id == 42
    assert args.percentage == 25
    assert approval.subcommand == "approval"
    assert approval.approval_subcommand == "get"

    with pytest.raises(SystemExit):
        parser.parse_args(["approval", "00000000-0000-4000-8000-000000000001"])


def test_hv_exp_approval_get_shows_the_server_record(monkeypatch):
    args = SimpleNamespace(
        subcommand="approval",
        approval_subcommand="get",
        approval_id="00000000-0000-4000-8000-000000000001",
    )
    client = object()
    approval = {"approval_id": args.approval_id}

    monkeypatch.setattr("hotvect.experiment_management.commands._create_client_from_args", lambda _args: client)
    monkeypatch.setattr(
        "hotvect.experiment_management.commands.get_approval_request",
        lambda actual_client, approval_id: approval
        if actual_client is client and approval_id == args.approval_id
        else None,
    )
    monkeypatch.setattr(
        "hotvect.experiment_management.commands.approval_review", lambda actual_approval: actual_approval
    )
    printed = _capture_print(monkeypatch)

    assert ExperimentCommand().execute(args) == 0

    assert json.loads(printed["out"]) == {"ok": True, "approval": approval}


@pytest.mark.parametrize("since", [None, "2000-10-07T12:00:00+02:00"])
def test_hv_exp_approval_list_reads_pending_requests_or_history(since, monkeypatch):
    parser = argparse.ArgumentParser()
    ExperimentCommand.register_parser(parser)
    argv = ["approval", "list"]
    if since is not None:
        argv += ["--since", since]
    args = parser.parse_args(argv)
    client = object()
    approvals = [{"approval_id": "00000000-0000-4000-8000-000000000001"}]

    def list_requests(actual_client, *, since):
        assert actual_client is client
        assert since == args.since
        return approvals

    monkeypatch.setattr("hotvect.experiment_management.commands._create_client_from_args", lambda _args: client)
    monkeypatch.setattr("hotvect.experiment_management.commands.list_approval_requests", list_requests)
    printed = _capture_print(monkeypatch)

    assert ExperimentCommand().execute(args) == 0
    assert args.since == (datetime.fromisoformat(since) if since else None)
    assert json.loads(printed["out"]) == {"ok": True, "approvals": approvals}


def test_approval_history_parser_rejects_a_timestamp_without_timezone():
    parser = argparse.ArgumentParser()
    ExperimentCommand.register_parser(parser)
    with pytest.raises(SystemExit):
        parser.parse_args(["approval", "list", "--since", "2000-10-07T10:00:00"])
