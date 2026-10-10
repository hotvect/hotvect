"""Request and inspect server-stored approvals for protected EMS mutations."""

from __future__ import annotations

import base64
import json
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Mapping
from urllib.parse import urlencode
from uuid import UUID

import requests

from hotvect.experiment_management.client import ExperimentManagementClient

_APPROVAL_REQUESTS_ENDPOINT = "/approval-requests"
_MUTATION_METHODS = {"POST", "PUT", "PATCH", "DELETE"}


@dataclass(frozen=True)
class NativeMutation:
    """One exact EMS mutation that may need an independent approval."""

    method: str
    target: str
    body: bytes
    reason: str
    content_type: str = "application/json"

    def __post_init__(self) -> None:
        method = self.method.upper()
        if method not in _MUTATION_METHODS:
            raise ValueError(f"Unsupported EMS mutation method: {self.method}")
        if not self.target.startswith("/"):
            raise ValueError("EMS mutation targets must start with '/'.")
        if not self.reason.strip():
            raise ValueError("--reason must not be empty for a protected EMS mutation.")
        if not self.content_type.strip():
            raise ValueError("EMS mutation content type must not be empty.")
        object.__setattr__(self, "method", method)


def json_mutation(*, method: str, target: str, payload: Mapping[str, Any], reason: str) -> NativeMutation:
    return NativeMutation(
        method=method,
        target=target,
        body=json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8"),
        reason=reason,
    )


def register_approval_arguments(parser: Any) -> None:
    approval = parser.add_parser("approval", help="Inspect server-stored EMS approval requests")
    approval_subcommands = approval.add_subparsers(dest="approval_subcommand", required=True, metavar="<operation>")
    approval_get = approval_subcommands.add_parser("get", help="Show the exact operation recorded by EMS")
    approval_get.add_argument("approval_id", help="EMS approval request UUID")
    approval_list = approval_subcommands.add_parser("list", help="List pending requests or approval history")
    approval_list.add_argument(
        "--since",
        type=_parse_since,
        help="List all requests created since this ISO-8601 timestamp with a timezone; omit to list pending requests",
    )


def _parse_since(value: str) -> datetime:
    since = datetime.fromisoformat(value)
    if since.utcoffset() is None:
        raise ValueError("--since requires an ISO-8601 timestamp with a timezone.")
    return since


def list_approval_requests(
    client: ExperimentManagementClient, *, since: datetime | None = None
) -> list[dict[str, Any]]:
    target = _APPROVAL_REQUESTS_ENDPOINT
    if since is not None:
        if since.utcoffset() is None:
            raise ValueError("--since requires an ISO-8601 timestamp with a timezone.")
        target += "/recent?" + urlencode({"since": since.isoformat()})
    response = client.make_raw_request(method="GET", endpoint=target, body=b"", headers={})
    require_success(response)
    return [approval_review(approval) for approval in response.json()]


def submit_native_mutation(client: ExperimentManagementClient, mutation: NativeMutation) -> dict[str, Any]:
    """Execute directly, creating a server request only when EMS requires approval."""

    response = _send_mutation(client, mutation)
    if response.status_code != 428:
        require_success(response)
        return {
            "ok": True,
            "status": "executed",
            "operation": _mutation_summary(mutation),
            "status_code": response.status_code,
            "response_body": response.text,
        }

    approval = create_approval_request(client, mutation)
    return {
        "ok": True,
        "status": "approval_requested",
        "operation": _mutation_summary(mutation),
        "approval": approval_review(approval),
    }


def create_approval_request(client: ExperimentManagementClient, mutation: NativeMutation) -> dict[str, Any]:
    request_body = json.dumps(
        {
            "method": mutation.method,
            "target": mutation.target,
            "content_type": mutation.content_type,
            "body_base64": base64.b64encode(mutation.body).decode("ascii"),
            "reason": mutation.reason,
        },
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    response = client.make_raw_request(
        method="POST",
        endpoint=_APPROVAL_REQUESTS_ENDPOINT,
        body=request_body,
        headers={"Content-Type": "application/json"},
    )
    require_success(response)
    return _approval_document(response)


def get_approval_request(client: ExperimentManagementClient, approval_id: str) -> dict[str, Any]:
    normalized_id = _normalize_approval_id(approval_id)
    response = client.make_raw_request(
        method="GET",
        endpoint=f"{_APPROVAL_REQUESTS_ENDPOINT}/{normalized_id}",
        body=b"",
        headers={},
    )
    require_success(response)
    return _approval_document(response)


def approval_review(approval: Mapping[str, Any]) -> dict[str, Any]:
    """Render the server record for a human reviewer without relying on requester files."""

    review = dict(approval)
    body = _decode_body(approval)
    content_type = _require_text(approval, "content_type")
    if content_type.split(";", 1)[0].strip().lower() == "application/json":
        review["body"] = json.loads(body.decode("utf-8")) if body else None
    else:
        review["body"] = body.decode("utf-8")
    return review


def _send_mutation(client: ExperimentManagementClient, mutation: NativeMutation):
    return client.make_raw_request(
        method=mutation.method,
        endpoint=mutation.target,
        body=mutation.body,
        headers={"Content-Type": mutation.content_type},
    )


def _approval_document(response: Any) -> dict[str, Any]:
    try:
        document = response.json()
    except ValueError as exc:
        raise ValueError("EMS approval endpoint returned invalid JSON.") from exc
    if not isinstance(document, dict):
        raise ValueError("EMS approval endpoint returned a non-object JSON response.")
    for field in ("approval_id", "method", "target", "content_type", "request_sha256", "reason"):
        _require_text(document, field)
    _require_string(document, "body_base64", allow_empty=True)
    _normalize_approval_id(_require_text(document, "approval_id"))
    return document


def require_success(response: Any) -> None:
    if 200 <= response.status_code < 300:
        return
    response.raise_for_status()
    raise requests.HTTPError(
        f"EMS returned non-success status code {response.status_code}.",
        response=response,
    )


def _decode_body(approval: Mapping[str, Any]) -> bytes:
    try:
        return base64.b64decode(_require_string(approval, "body_base64", allow_empty=True), validate=True)
    except ValueError as exc:
        raise ValueError("EMS approval record has invalid body_base64.") from exc


def _normalize_approval_id(value: str) -> str:
    try:
        return str(UUID(value))
    except ValueError as exc:
        raise ValueError(f"Invalid approval id: {value}") from exc


def _require_text(document: Mapping[str, Any], field: str) -> str:
    return _require_string(document, field, allow_empty=False)


def _require_string(document: Mapping[str, Any], field: str, *, allow_empty: bool) -> str:
    value = document.get(field)
    if not isinstance(value, str) or (not allow_empty and not value):
        raise ValueError(f"EMS approval record is missing '{field}'.")
    return value


def _mutation_summary(mutation: NativeMutation) -> dict[str, str]:
    return {
        "method": mutation.method,
        "target": mutation.target,
        "reason": mutation.reason,
    }
