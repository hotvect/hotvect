from __future__ import annotations

import argparse
import base64
import hashlib
import json
from urllib.parse import urlsplit

import pytest
import requests
from pydantic import ValidationError

from hotvect.experiment_management.client import ExperimentManagementClient
from hotvect.experiment_management.commands import ExperimentCommand

_APPROVAL_ID = "00000000-0000-4000-8000-000000000001"
_ALGORITHM = {
    "algorithm_name": "ranker",
    "algorithm_version": "1.0.0",
    "state": "ACTIVE",
    "absolute_s3_jar_path": "s3://artifacts/ranker-1.0.0.jar",
    "algorithm_training_image_name": "",
    "parameter_mode": "NONE",
    "created_at": "2000-10-07T10:00:00Z",
}
_EXPERIMENT = {
    "experiment_id": 42,
    "experiment_name": "treatment",
    "created_at": "2000-10-07T10:00:00Z",
    "terminated_at": None,
    "variants": [{"variant_id": 7, "algorithm": _ALGORITHM, "is_control": True, "shard_allocation_ratio": 1}],
    "ramp_up_percentage": 25,
}
_EXPERIMENT_SPEC = {
    "experiment_name": "treatment",
    "number_of_shards": 10,
    "ramp_up_percentage": 25,
    "variants": [
        {
            "algorithm_name": "ranker",
            "algorithm_version": "1.0.0",
            "is_control": True,
            "shard_allocation_ratio": 1,
        }
    ],
}
_ALGORITHM_SPEC = {
    "algorithm_name": "ranker",
    "algorithm_version": "1.0.0",
    "absolute_s3_jar_path": "s3://artifacts/ranker-1.0.0.jar",
    "parameter_mode": "NONE",
}
_PARAMETER_SPEC = {
    "algorithm_parameter_id": "ranker-params-1",
    "algorithm": {"algorithm_name": "ranker", "algorithm_version": "1.0.0"},
    "absolute_s3_path": "s3://artifacts/ranker-params-1.zip",
}


def _response(status: int, body=None) -> requests.Response:
    response = requests.Response()
    response.status_code = status
    response._content = b"" if body is None else json.dumps(body).encode()
    return response


@pytest.fixture
def ems_transport(monkeypatch):
    calls = []
    client = ExperimentManagementClient(base_url="https://ems.example.test", auth=None)

    def request(**kwargs):
        calls.append(kwargs)
        target = urlsplit(kwargs["url"]).path
        if kwargs["method"] == "GET":
            if target == "/slots":
                return _response(200, [{"name": "test-slot", "created_at": "2000-10-07T10:00:00Z", "salts": ["salt"]}])
            if target == "/slots/test-slot/experiments":
                return _response(200, {"slot_name": "test-slot", "experiments": [_EXPERIMENT]})
            if target == "/slots/test-slot/variants":
                return _response(
                    200,
                    [
                        {
                            "variant_id": 7,
                            "algorithm": _ALGORITHM,
                            "experiment": _EXPERIMENT,
                            "created_at": "2000-10-07T10:00:00Z",
                            "terminated_at": None,
                            "is_default": False,
                            "is_control": True,
                            "shard_allocation_ratio": 1,
                        }
                    ],
                )
            raise AssertionError(f"Unexpected GET {target}")
        if target == "/approval-requests":
            operation = json.loads(kwargs["data"])
            body = base64.b64decode(operation["body_base64"])
            digest_input = f'{operation["method"]}\n{operation["target"]}\n{hashlib.sha256(body).hexdigest()}\n'
            return _response(
                201,
                {
                    **operation,
                    "approval_id": _APPROVAL_ID,
                    "request_sha256": hashlib.sha256(digest_input.encode()).hexdigest(),
                    "requester_id": "requester-a",
                    "approved_at": None,
                    "executed_at": None,
                    "rejected_at": None,
                    "emergency_reference": None,
                },
            )
        return _response(428, {"error_message": "Dual approval is required."})

    monkeypatch.setattr("hotvect.experiment_management.client.requests.request", request)
    monkeypatch.setattr("hotvect.experiment_management.commands._create_client_from_args", lambda args: client)
    return calls


def _parse(argv):
    parser = argparse.ArgumentParser()
    ExperimentCommand.register_parser(parser)
    return parser.parse_args(argv)


# The 10 business write routes supported by the operator CLI.
@pytest.mark.parametrize(
    "argv,spec,method,target,body",
    [
        (
            ["experiment", "create", "--slot-name", "test-slot"],
            _EXPERIMENT_SPEC,
            "POST",
            "/slots/test-slot/experiments",
            _EXPERIMENT_SPEC,
        ),
        (
            ["experiment", "ramp-up", "--experiment-id", "42", "--percentage", "50"],
            None,
            "PATCH",
            "/slots/test-slot/experiments/42/changeRampUpPercentage",
            {"new_ramp_up_percentage": 50},
        ),
        (
            ["experiment", "terminate", "--experiment-id", "42"],
            None,
            "PUT",
            "/slots/test-slot/experiments/42/terminate",
            None,
        ),
        (
            ["algorithm", "create"],
            _ALGORITHM_SPEC,
            "POST",
            "/algorithms",
            {**_ALGORITHM_SPEC, "algorithm_training_image_name": ""},
        ),
        (
            [
                "algorithm",
                "state",
                "--algorithm-name",
                "ranker",
                "--algorithm-version",
                "1.0.0",
                "--state",
                "ACTIVE",
            ],
            None,
            "PATCH",
            "/algorithms/ranker/1.0.0",
            {"state": "ACTIVE"},
        ),
        (
            [
                "algorithm",
                "promote",
                "--slot-name",
                "test-slot",
                "--algorithm-name",
                "ranker",
                "--algorithm-version",
                "1.0.0",
            ],
            None,
            "PUT",
            "/algorithms/ranker/1.0.0/promote",
            {"slot_name": "test-slot"},
        ),
        (
            ["algorithm", "parameter", "create"],
            _PARAMETER_SPEC,
            "POST",
            "/algorithm-parameters",
            {**_PARAMETER_SPEC, "evaluation_results": "{}"},
        ),
        (
            [
                "variant",
                "algorithm",
                "--variant-id",
                "7",
                "--algorithm-name",
                "ranker",
                "--algorithm-version",
                "1.0.0",
            ],
            None,
            "PATCH",
            "/slots/test-slot/variants/7/updateAlgorithm",
            {"algorithm_name": "ranker", "algorithm_version": "1.0.0"},
        ),
        (
            ["forced-assignment", "set", "--slot-name", "test-slot", "--user-id", "user 1?", "--variant-id", "7"],
            None,
            "PUT",
            "/slots/test-slot/userForcedAssignments/user%201%3F",
            {"variant_id": 7},
        ),
        (
            ["forced-assignment", "delete", "--slot-name", "test-slot", "--user-id", "user 1?"],
            None,
            "DELETE",
            "/slots/test-slot/userForcedAssignments/user%201%3F",
            None,
        ),
    ],
)
def test_supported_business_mutations_register_the_exact_protected_operation(
    argv, spec, method, target, body, tmp_path, ems_transport, capsys
):
    if spec is not None:
        path = tmp_path / "spec.json"
        path.write_text(json.dumps(spec))
        argv = [*argv, "--spec-file", str(path)]
    ExperimentCommand().execute(_parse([*argv, "--reason", "Reviewed change"]))

    writes = [call for call in ems_transport if call["method"] != "GET"]
    assert len(writes) == 2
    operation, registration = writes
    assert operation["method"] == method
    assert operation["url"] == "https://ems.example.test" + target
    assert operation["headers"] == {"Content-Type": "application/json"}
    assert operation["allow_redirects"] is False
    expected_bytes = b"" if body is None else json.dumps(body, sort_keys=True, separators=(",", ":")).encode()
    assert operation["data"] == expected_bytes
    assert registration["url"] == "https://ems.example.test/approval-requests"
    assert registration["allow_redirects"] is False
    assert json.loads(registration["data"]) == {
        "method": method,
        "target": target,
        "content_type": "application/json",
        "body_base64": base64.b64encode(expected_bytes).decode(),
        "reason": "Reviewed change",
    }
    output = json.loads(capsys.readouterr().out)
    assert output["status"] == "approval_requested"
    assert output["approval"]["approval_id"] == _APPROVAL_ID
    assert output["approval"]["body"] == body


@pytest.mark.parametrize(
    "command,spec",
    [
        (["algorithm", "create"], {**_ALGORITHM_SPEC, "parameter_mode": None}),
        (["algorithm", "create"], {**_ALGORITHM_SPEC, "algorithm_name": ""}),
        (["algorithm", "create"], {**_ALGORITHM_SPEC, "unexpected_field": True}),
        (["algorithm", "parameter", "create"], {**_PARAMETER_SPEC, "created_at": "2000-10-07T10:00:00"}),
        (["experiment", "create", "--slot-name", "test-slot"], {**_EXPERIMENT_SPEC, "ramp_up_percentage": 101}),
        (["experiment", "create", "--slot-name", "test-slot"], {**_EXPERIMENT_SPEC, "variants": []}),
        (
            ["experiment", "create", "--slot-name", "test-slot"],
            {**_EXPERIMENT_SPEC, "variants": [{**_EXPERIMENT_SPEC["variants"][0], "algoritm_name": "typo"}]},
        ),
    ],
)
def test_invalid_specs_fail_before_any_ems_request(command, spec, tmp_path, ems_transport):
    path = tmp_path / "spec.json"
    path.write_text(json.dumps(spec))
    with pytest.raises(ValidationError):
        ExperimentCommand().execute(_parse([*command, "--spec-file", str(path), "--reason", "Reviewed change"]))
    assert ems_transport == []


@pytest.mark.parametrize(
    "command",
    [
        ["experiment", "terminate", "--experiment-id", "42"],
        ["algorithm", "parameter", "create", "--spec-file", "params.json"],
    ],
)
def test_new_mutations_require_an_audit_reason(command):
    with pytest.raises(SystemExit):
        _parse(command)


def test_default_parameter_mode_matches_the_ems_required_default(tmp_path, ems_transport, capsys):
    spec = {key: value for key, value in _ALGORITHM_SPEC.items() if key != "parameter_mode"}
    path = tmp_path / "algorithm.json"
    path.write_text(json.dumps(spec))
    ExperimentCommand().execute(
        _parse(["algorithm", "create", "--spec-file", str(path), "--reason", "Register artifact"])
    )
    assert json.loads(ems_transport[0]["data"])["parameter_mode"] == "REQUIRED"
    assert json.loads(capsys.readouterr().out)["status"] == "approval_requested"


def test_parameter_registration_can_preserve_the_creation_time(tmp_path, ems_transport, capsys):
    path = tmp_path / "parameter.json"
    path.write_text(json.dumps({**_PARAMETER_SPEC, "created_at": "2000-10-07T10:00:00Z"}))
    ExperimentCommand().execute(
        _parse(["algorithm", "parameter", "create", "--spec-file", str(path), "--reason", "Register artifact"])
    )
    assert json.loads(ems_transport[0]["data"])["created_at"] == "2000-10-07T10:00:00Z"
    assert json.loads(capsys.readouterr().out)["status"] == "approval_requested"


@pytest.mark.parametrize("percentage", [-1, 101])
def test_ramp_up_bounds_fail_before_reading_ems(percentage, ems_transport):
    with pytest.raises(ValueError, match="between 0 and 100"):
        ExperimentCommand().execute(
            _parse(
                [
                    "experiment",
                    "ramp-up",
                    "--experiment-id",
                    "42",
                    "--percentage",
                    str(percentage),
                    "--reason",
                    "Change traffic",
                ]
            )
        )
    assert ems_transport == []
