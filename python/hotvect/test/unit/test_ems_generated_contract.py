from __future__ import annotations

from datetime import datetime, timezone
from types import SimpleNamespace

import pytest
from pydantic import ValidationError

from hotvect.experiment_management import (
    AlgorithmCreate,
    AlgorithmPatch,
    AlgorithmWithLatestParameter,
    ChangeRampUpPercentageInput,
    ExperimentCreate,
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


def test_algorithm_summary_requires_explicit_nullable_parameter():
    payload = _algorithm_payload() | {"parameter_mode": "NONE", "latest_algorithm_parameter": None}
    algorithm = AlgorithmWithLatestParameter.model_validate(payload)

    assert algorithm.parameter_mode == "NONE"
    assert algorithm.latest_algorithm_parameter is None
    del payload["latest_algorithm_parameter"]
    with pytest.raises(ValidationError, match="latest_algorithm_parameter"):
        AlgorithmWithLatestParameter.model_validate(payload)


@pytest.mark.parametrize(
    "summary",
    [
        {"algorithm_parameter_id": "incomplete"},
        {
            "algorithm_parameter_id": "p1",
            "absolute_s3_algorithm_parameter_path": {"invalid": "type"},
        },
    ],
)
def test_algorithm_summary_rejects_malformed_parameter_details(summary):
    with pytest.raises(ValidationError):
        AlgorithmWithLatestParameter.model_validate(_algorithm_payload() | {"latest_algorithm_parameter": summary})


def test_algorithm_create_allows_legacy_parameter_mode_omission():
    algorithm = AlgorithmCreate(
        algorithm_name="algorithm-a",
        algorithm_version="1.0.0",
        absolute_s3_jar_path="s3://example-bucket/algorithm-a.jar",
        algorithm_training_image_name="algorithm-a:1",
    )
    connection = _RecordingConnection(_algorithm_payload() | {"latest_algorithm_parameter": None})
    operations = _GeneratedEmsOperations(connection.make_request)

    operations.operation_add_2(algorithm)

    assert algorithm.parameter_mode is None
    assert connection.calls == [
        {
            "method": "POST",
            "endpoint": "/algorithms",
            "params": None,
            "body": {
                "algorithm_name": "algorithm-a",
                "algorithm_version": "1.0.0",
                "absolute_s3_jar_path": "s3://example-bucket/algorithm-a.jar",
                "algorithm_training_image_name": "algorithm-a:1",
            },
        }
    ]


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
                    "shards": [{"shard_id": 1, "created_at": "2000-04-11T10:15:30Z"}],
                    "ramp_up_percentage": 50,
                    "created_at": "2000-04-11T10:15:30Z",
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
        "absolute_s3_algorithm_jar_path": "s3://example-bucket/parameterless.jar",
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
            "created_at": "2000-04-11T10:15:30Z",
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


def test_generated_variant_models_use_one_algorithm_and_ignore_additional_fields():
    payloads = (
        (Variant, {"variant_id": 7, "algorithm": _algorithm_payload()}),
        (VariantBasicDetails, {"variant_id": 7, "algorithm": _algorithm_payload()}),
        (SlotActiveInfoVariantResponse, _active_variant_payload()),
    )

    for model, payload in payloads:
        validated = model.model_validate(payload | {"algorithms": [_algorithm_payload()], "future_field": True})

        assert validated.algorithm is not None
        assert validated.model_dump() == model.model_validate(payload).model_dump()


@pytest.mark.parametrize("model", [Variant, SlotActiveInfoVariantResponse])
def test_generated_variant_models_still_require_known_algorithm_field(model):
    with pytest.raises(ValidationError, match="algorithm"):
        model.model_validate({"variant_id": 7, "algorithms": [_algorithm_payload()]})


def test_generated_models_ignore_additional_nested_response_fields():
    payload = _algorithm_payload() | {
        "future_algorithm_field": True,
        "latest_algorithm_parameter": {
            "algorithm_parameter_id": "p1",
            "absolute_s3_algorithm_parameter_path": "s3://example-bucket/p1.zip",
            "last_test_time": "2000-09-24T10:00:00Z",
            "future_parameter_field": {"value": 1},
        },
    }

    algorithm = AlgorithmWithLatestParameter.model_validate(payload)

    assert algorithm.algorithm_name == "algorithm-a"
    assert algorithm.latest_algorithm_parameter.model_dump() == {
        "algorithm_parameter_id": "p1",
        "absolute_s3_algorithm_parameter_path": "s3://example-bucket/p1.zip",
    }


def test_request_only_models_still_reject_unknown_fields_including_nested_models():
    with pytest.raises(ValidationError, match="extra_forbidden"):
        VariantUpdateAlgorithmInput(algorithm_name="algorithm-a", algorithm_version="1", future_field=True)
    with pytest.raises(ValidationError, match="variants.0.future_field"):
        ExperimentCreate.model_validate(
            {
                "experiment_name": "test",
                "number_of_shards": 1,
                "ramp_up_percentage": 1,
                "variants": [
                    {
                        "algorithm_name": "algorithm-a",
                        "algorithm_version": "1",
                        "is_control": True,
                        "shard_allocation_ratio": 100,
                        "future_field": True,
                    }
                ],
            }
        )


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
            absolute_s3_jar_path="s3://example-bucket/algorithm-a.jar",
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
            min_created_at=datetime(2000, 4, 11, 10, 15, 30, tzinfo=timezone.utc),
        )
        == []
    )
    assert connection.calls[0]["params"] == {"top": 10, "minCreatedAt": "2000-04-11T10:15:30+00:00"}


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
        "absolute_s3_jar_path": "s3://example-bucket/algorithm-a.jar",
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
            "absolute_s3_algorithm_jar_path": "s3://example-bucket/active-a.jar",
            "absolute_s3_algorithm_parameter_path": "s3://example-bucket/active-a.zip",
        },
        "created_at": "2000-04-11T10:15:30Z",
    }
