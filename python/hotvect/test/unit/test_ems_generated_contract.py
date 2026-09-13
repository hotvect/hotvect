from __future__ import annotations

from datetime import datetime, timezone
from types import SimpleNamespace

import pytest
from pydantic import ValidationError

from hotvect.experiment_management import (
    AlgorithmCreate,
    AlgorithmPatch,
    ChangeRampUpPercentageInput,
    ExperimentManagementClient,
    Slot,
    SlotActiveInfo,
    SlotActiveInfoVariantResponse,
    SlotInput,
    UserForcedAssignment,
    Variant,
    VariantBasicDetails,
    VariantUpdateAlgorithmInput,
)
from hotvect.experiment_management._generated_operations import _GeneratedEmsOperations


def test_generated_models_accept_singleton_default_experiment_and_forced_assignment_payloads():
    active_variant = _active_variant_payload()
    slot = SlotActiveInfo.model_validate(
        {
            "slot_salt": "salt-1",
            "total_number_of_shards": 100,
            "default_variant": active_variant,
            "experiments": [
                {
                    "experiment_id": 42,
                    "experiment_name": "experiment",
                    "variants": [active_variant | {"variant_id": 7}],
                    "shards": [{"shard_id": 1, "created_at": "2026-04-11T10:15:30Z"}],
                    "ramp_up_percentage": 50,
                    "created_at": "2026-04-11T10:15:30Z",
                }
            ],
            "user_forced_assignments": [{"user_id": "user-1", "variant_id": 7}],
        }
    )
    assignment = UserForcedAssignment.model_validate(
        {
            "user_id": "user-1",
            "variant": _variant_payload(),
        }
    )

    assert slot.default_variant.algorithm.algorithm_name == "active-a"
    assert slot.experiments[0].variants[0].algorithm.algorithm_name == "active-a"
    assert assignment.variant.algorithm.algorithm_name == "algorithm-a"


def test_generated_models_accept_parameterless_active_algorithm():
    payload = _active_variant_payload()
    payload["algorithm"] = {
        "algorithm_name": "parameterless",
        "algorithm_version": "1.0.0",
        "absolute_s3_algorithm_jar_path": "s3://bucket/parameterless.jar",
    }

    variant = SlotActiveInfoVariantResponse.model_validate(payload)

    assert variant.algorithm.latest_algorithm_parameter is None
    assert variant.algorithm.absolute_s3_algorithm_parameter_path is None


def test_slot_salts_use_direct_string_ids():
    slot = Slot.model_validate({"name": "catalog", "salts": ["salt-1"]})

    assert slot.salts == ["salt-1"]
    with pytest.raises(ValidationError, match="Input should be a valid string"):
        Slot.model_validate({"name": "catalog", "salts": [{"salt": "salt-1"}]})


def test_generated_slot_operation_accepts_nonempty_salt_ids():
    connection = _RecordingConnection(
        {
            "name": "catalog",
            "created_at": "2026-04-11T10:15:30Z",
            "salts": ["salt-1"],
        }
    )
    operations = _GeneratedEmsOperations(connection.make_request)

    slot = operations.operation_get_one_by_name("catalog")

    assert slot.salts == ["salt-1"]
    assert connection.calls == [
        {
            "method": "GET",
            "endpoint": "/slots/catalog",
            "params": None,
            "body": None,
        }
    ]


def test_generated_variant_models_use_one_algorithm_and_reject_plural_fields():
    payloads = (
        (Variant, {"variant_id": 7, "algorithm": _algorithm_payload()}),
        (VariantBasicDetails, {"variant_id": 7, "algorithm": _algorithm_payload()}),
        (SlotActiveInfoVariantResponse, _active_variant_payload()),
    )

    for model, payload in payloads:
        validated = model.model_validate(payload)

        assert validated.algorithm is not None
        with pytest.raises(ValidationError, match="algorithms"):
            model.model_validate(payload | {"algorithms": [_algorithm_payload()]})


def test_generated_variant_models_reject_unknown_fields():
    payloads = (
        (Variant, {"variant_id": 7, "algorithm": _algorithm_payload()}),
        (VariantBasicDetails, {"variant_id": 7, "algorithm": _algorithm_payload()}),
        (SlotActiveInfoVariantResponse, _active_variant_payload()),
    )

    for model, payload in payloads:
        with pytest.raises(ValidationError, match="unknown_field"):
            model.model_validate(payload | {"unknown_field": "unexpected"})


def test_generated_request_models_enforce_direct_stage2_constraints():
    with pytest.raises(ValidationError, match="Field required"):
        VariantUpdateAlgorithmInput(algorithm_name="algorithm-a")
    with pytest.raises(ValidationError, match="less than or equal to 100"):
        ChangeRampUpPercentageInput(new_ramp_up_percentage=101)
    with pytest.raises(ValidationError, match="String should match pattern"):
        AlgorithmPatch(state="PENDING")
    with pytest.raises(ValidationError, match="'REQUIRED' or 'NONE'"):
        AlgorithmCreate(
            algorithm_name="algorithm-a",
            algorithm_version="1.0.0",
            absolute_s3_jar_path="s3://bucket/algorithm-a.jar",
            algorithm_training_image_name="algorithm-a:1",
            parameter_mode="TYPO",
        )


def test_generated_operation_uses_direct_update_algorithm_path_and_body():
    connection = _RecordingConnection(
        {
            "variant_id": 7,
            "algorithm": _algorithm_payload(),
        }
    )
    operations = _GeneratedEmsOperations(connection.make_request)

    updated = operations.operation_update_algorithm(
        "slot one/blue",
        7,
        VariantUpdateAlgorithmInput(
            algorithm_name="algorithm-a",
            algorithm_version="1.0.0",
        ),
    )

    assert updated.variant_id == 7
    assert connection.calls == [
        {
            "method": "PATCH",
            "endpoint": "/slots/slot%20one%2Fblue/variants/7/updateAlgorithm",
            "params": None,
            "body": {"algorithm_name": "algorithm-a", "algorithm_version": "1.0.0"},
        }
    ]


def test_generated_operation_serializes_query_parameters():
    connection = _RecordingConnection([])
    operations = _GeneratedEmsOperations(connection.make_request)

    assert (
        operations.operation_get_by_algorithm(
            "algorithm-a",
            "1.0.0",
            top=10,
            min_created_at=datetime(2026, 4, 11, 10, 15, 30, tzinfo=timezone.utc),
        )
        == []
    )
    assert connection.calls[0]["params"] == {"top": 10, "minCreatedAt": "2026-04-11T10:15:30+00:00"}


def test_algorithm_promotion_uses_direct_algorithm_path_and_slot_body():
    connection = _RecordingConnection(None)
    client = ExperimentManagementClient(base_url="http://localhost:8080", auth=SimpleNamespace())
    client._operations = _GeneratedEmsOperations(connection.make_request)

    client.promote_algorithm(
        "ranking",
        "4.2.0",
        SlotInput(
            slot_name="slot-one",
            num_shards=100,
            algorithm_name="ranking",
            algorithm_version="4.2.0",
        ),
    )

    assert connection.calls == [
        {
            "method": "PUT",
            "endpoint": "/algorithms/ranking/4.2.0/promote",
            "params": None,
            "body": {
                "slot_name": "slot-one",
                "num_shards": 100,
                "algorithm_name": "ranking",
                "algorithm_version": "4.2.0",
            },
        }
    ]
    assert not hasattr(client, "promote_variant")


def test_client_does_not_expose_generated_operations():
    client = ExperimentManagementClient(base_url="http://localhost:8080", auth=SimpleNamespace())

    assert not hasattr(client, "operations")


class _RecordingConnection:
    def __init__(self, payload):
        self._payload = payload
        self.calls: list[dict[str, object]] = []

    def make_request(self, *, method, endpoint, params=None, body=None):
        self.calls.append({"method": method, "endpoint": endpoint, "params": params, "body": body})
        return SimpleNamespace(
            status_code=200,
            text="payload" if self._payload is not None else "",
            json=lambda: self._payload,
            raise_for_status=lambda: None,
        )


def _algorithm_payload() -> dict[str, str]:
    return {
        "algorithm_name": "algorithm-a",
        "algorithm_version": "1.0.0",
        "absolute_s3_jar_path": "s3://bucket/algorithm-a.jar",
        "algorithm_training_image_name": "algorithm-a:1",
        "parameter_mode": "REQUIRED",
    }


def _variant_payload() -> dict[str, object]:
    return {
        "variant_id": 7,
        "algorithm": _algorithm_payload(),
    }


def _active_variant_payload() -> dict[str, object]:
    return {
        "variant_id": 1,
        "algorithm": {
            "algorithm_name": "active-a",
            "algorithm_version": "1.0.0",
            "latest_algorithm_parameter": "active-a-parameter",
            "absolute_s3_algorithm_jar_path": "s3://bucket/active-a.jar",
            "absolute_s3_algorithm_parameter_path": "s3://bucket/active-a.zip",
        },
        "created_at": "2026-04-11T10:15:30Z",
    }
