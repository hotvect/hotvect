from __future__ import annotations

import base64
import json
from datetime import datetime

import pytest
import requests

from hotvect.experiment_management.approval import (
    NativeMutation,
    approval_review,
    get_approval_request,
    list_approval_requests,
    submit_native_mutation,
)

_APPROVAL_ID = "00000000-0000-4000-8000-000000000001"


def _approval_document() -> dict:
    return {
        "approval_id": _APPROVAL_ID,
        "requester_id": "requester-a",
        "method": "PATCH",
        "target": "/slots/my-slot/experiments/42/changeRampUpPercentage",
        "content_type": "application/json",
        "body_base64": base64.b64encode(b'{"new_ramp_up_percentage":25}').decode("ascii"),
        "request_sha256": "a" * 64,
        "reason": "Increase the experiment ramp-up after reviewing online metrics.",
        "created_at": "2000-07-25T10:00:00Z",
        "approved_by": None,
        "approved_at": None,
        "expires_at": None,
        "executed_by": None,
        "executed_at": None,
        "rejected_by": None,
        "rejected_at": None,
        "emergency_reference": None,
    }


class _Response:
    def __init__(self, status_code: int, document: dict | list | None = None, text: str = ""):
        self.status_code = status_code
        self._document = document
        self.text = text
        self.raised = False

    def json(self):
        if self._document is None:
            raise ValueError("no JSON")
        return self._document

    def raise_for_status(self):
        self.raised = True
        if self.status_code >= 400:
            raise RuntimeError(f"HTTP {self.status_code}")


class _Client:
    def __init__(self, responses: list[_Response]):
        self._responses = iter(responses)
        self.calls: list[dict] = []

    def make_raw_request(self, **kwargs):
        self.calls.append(kwargs)
        return next(self._responses)


def test_protected_native_mutation_creates_a_server_record_after_428():
    mutation = NativeMutation(
        method="PATCH",
        target="/slots/my-slot/experiments/42/changeRampUpPercentage",
        body=b'{"new_ramp_up_percentage":25}',
        reason="Increase the experiment ramp-up after reviewing online metrics.",
    )
    client = _Client([_Response(428), _Response(201, _approval_document())])

    result = submit_native_mutation(client, mutation)

    assert result["status"] == "approval_requested"
    assert result["approval"]["approval_id"] == _APPROVAL_ID
    assert result["approval"]["body"] == {"new_ramp_up_percentage": 25}
    assert client.calls == [
        {
            "method": "PATCH",
            "endpoint": "/slots/my-slot/experiments/42/changeRampUpPercentage",
            "body": b'{"new_ramp_up_percentage":25}',
            "headers": {"Content-Type": "application/json"},
        },
        {
            "method": "POST",
            "endpoint": "/approval-requests",
            "body": json.dumps(
                {
                    "method": "PATCH",
                    "target": "/slots/my-slot/experiments/42/changeRampUpPercentage",
                    "content_type": "application/json",
                    "body_base64": "eyJuZXdfcmFtcF91cF9wZXJjZW50YWdlIjoyNX0=",
                    "reason": "Increase the experiment ramp-up after reviewing online metrics.",
                },
                sort_keys=True,
                separators=(",", ":"),
            ).encode("utf-8"),
            "headers": {"Content-Type": "application/json"},
        },
    ]


def test_native_mutation_reports_direct_execution_when_ems_does_not_require_approval():
    mutation = NativeMutation(
        method="PATCH",
        target="/algorithms/ranker/1.0.0",
        body=b'{"state":"ACTIVE"}',
        reason="Activate the reviewed algorithm version.",
    )
    client = _Client([_Response(200, text='{"state":"ACTIVE"}')])

    result = submit_native_mutation(client, mutation)

    assert result == {
        "ok": True,
        "status": "executed",
        "operation": {
            "method": "PATCH",
            "target": "/algorithms/ranker/1.0.0",
            "reason": "Activate the reviewed algorithm version.",
        },
        "status_code": 200,
        "response_body": '{"state":"ACTIVE"}',
    }
    assert len(client.calls) == 1


def test_native_mutation_does_not_report_a_redirect_as_execution():
    mutation = NativeMutation(
        method="PATCH",
        target="/algorithms/ranker/1.0.0",
        body=b'{"state":"ACTIVE"}',
        reason="Activate the reviewed algorithm version.",
    )
    client = _Client([_Response(307)])

    with pytest.raises(requests.HTTPError, match="non-success status code 307"):
        submit_native_mutation(client, mutation)


def test_approval_review_shows_json_body_from_the_ems_record():
    review = approval_review(_approval_document())

    assert review["body"] == {"new_ramp_up_percentage": 25}
    assert review["body_base64"] == "eyJuZXdfcmFtcF91cF9wZXJjZW50YWdlIjoyNX0="


def test_get_approval_reads_the_stored_operation_without_sending_it():
    approval = _approval_document()
    client = _Client([_Response(200, approval)])

    assert get_approval_request(client, _APPROVAL_ID) == approval
    assert client.calls == [
        {"method": "GET", "endpoint": f"/approval-requests/{_APPROVAL_ID}", "body": b"", "headers": {}}
    ]


def test_list_pending_approvals_reads_the_server_records():
    client = _Client([_Response(200, [_approval_document()])])
    approvals = list_approval_requests(client)
    assert approvals[0]["body"] == {"new_ramp_up_percentage": 25}
    assert client.calls == [{"method": "GET", "endpoint": "/approval-requests", "body": b"", "headers": {}}]


def test_list_history_preserves_an_explicit_timezone_in_the_query():
    client = _Client([_Response(200, [])])
    assert list_approval_requests(client, since=datetime.fromisoformat("2000-10-07T12:00:00+02:00")) == []
    assert client.calls[0]["endpoint"] == "/approval-requests/recent?since=2000-10-07T12%3A00%3A00%2B02%3A00"


def test_approval_history_rejects_a_timezone_ambiguous_timestamp():
    client = _Client([])
    with pytest.raises(ValueError, match="timestamp with a timezone"):
        list_approval_requests(client, since=datetime(2000, 10, 7))
    assert client.calls == []


def test_denied_native_mutation_does_not_register_an_approval():
    mutation = NativeMutation(
        method="PATCH", target="/algorithms/ranker/1.0.0", body=b'{"state":"ACTIVE"}', reason="Activate algorithm"
    )
    client = _Client([_Response(403)])
    with pytest.raises(RuntimeError, match="HTTP 403"):
        submit_native_mutation(client, mutation)
    assert len(client.calls) == 1
