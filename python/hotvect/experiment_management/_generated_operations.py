# Generated EMS client binding. Do not edit manually.
from __future__ import annotations

from collections.abc import Callable
from datetime import datetime
from typing import Any
from urllib.parse import quote

from pydantic import BaseModel, TypeAdapter

from .models import (
    Algorithm,
    AlgorithmCreate,
    AlgorithmParameter,
    AlgorithmPatch,
    AlgorithmStateLog,
    AlgorithmWithActiveVariantsResponse,
    AlgorithmWithLatestParameter,
    ChangeRampUpPercentageInput,
    Experiment,
    ExperimentCreate,
    ExperimentRampUpLog,
    Shard,
    ShardLog,
    Slot,
    SlotActiveInfo,
    SlotAllExperimentsWithVariantDetailsResponse,
    SlotInput,
    SlotSalt,
    UserForcedAssignment,
    UserForcedAssignmentInput,
    UserForcedAssignmentResponse,
    Variant,
    VariantAlgorithmLog,
    VariantUpdateAlgorithmInput,
)


class _GeneratedEmsOperations:
    """Internal generated operations delegated to by ExperimentManagementClient."""

    def __init__(self, send_request: Callable[..., Any]):
        self._send_request = send_request

    def _request(
        self,
        method: str,
        endpoint: str,
        *,
        body: BaseModel | None = None,
        params: dict[str, Any] | None = None,
    ) -> Any:
        payload = body.model_dump(by_alias=True, exclude_none=True) if body is not None else None
        response = self._send_request(method=method, endpoint=endpoint, params=params, body=payload)
        response.raise_for_status()
        return response.json() if response.text else None

    def operation_get_one_by_user_id(
        self,
        slot_name: str,
        user_id: str,
    ) -> Any:
        payload = self._request(
            "GET",
            f"/slots/{quote(str(slot_name), safe='')}/userForcedAssignments/{quote(str(user_id), safe='')}",
        )
        return TypeAdapter(UserForcedAssignment).validate_python(payload)

    def operation_upsert(
        self,
        slot_name: str,
        user_id: str,
        body: UserForcedAssignmentInput,
    ) -> Any:
        payload = self._request(
            "PUT",
            f"/slots/{quote(str(slot_name), safe='')}/userForcedAssignments/{quote(str(user_id), safe='')}",
            body=body,
        )
        if payload is None:
            return None
        return TypeAdapter(dict[str, Any]).validate_python(payload)

    def operation_delete(
        self,
        slot_name: str,
        user_id: str,
    ) -> Any:
        self._request(
            "DELETE",
            f"/slots/{quote(str(slot_name), safe='')}/userForcedAssignments/{quote(str(user_id), safe='')}",
        )
        return None

    def operation_terminate(
        self,
        slot_name: str,
        experiment_id: int,
    ) -> Any:
        self._request(
            "PUT",
            f"/slots/{quote(str(slot_name), safe='')}/experiments/{quote(str(experiment_id), safe='')}/terminate",
        )
        return None

    def operation_promote(
        self,
        algorithm_name: str,
        algorithm_version: str,
        body: SlotInput,
    ) -> Any:
        self._request(
            "PUT",
            f"/algorithms/{quote(str(algorithm_name), safe='')}/{quote(str(algorithm_version), safe='')}/promote",
            body=body,
        )
        return None

    def operation_list(
        self,
    ) -> Any:
        payload = self._request(
            "GET",
            "/slots",
        )
        return TypeAdapter(list[Slot]).validate_python(payload)

    def operation_add(
        self,
        body: SlotInput,
    ) -> Any:
        payload = self._request(
            "POST",
            "/slots",
            body=body,
        )
        return TypeAdapter(Slot).validate_python(payload)

    def operation_get_all(
        self,
        slot_name: str,
    ) -> Any:
        payload = self._request(
            "GET",
            f"/slots/{quote(str(slot_name), safe='')}/experiments",
        )
        return TypeAdapter(SlotAllExperimentsWithVariantDetailsResponse).validate_python(payload)

    def operation_add_1(
        self,
        slot_name: str,
        body: ExperimentCreate,
    ) -> Any:
        payload = self._request(
            "POST",
            f"/slots/{quote(str(slot_name), safe='')}/experiments",
            body=body,
        )
        return TypeAdapter(Experiment).validate_python(payload)

    def operation_list_1(
        self,
    ) -> Any:
        payload = self._request(
            "GET",
            "/algorithms",
        )
        return TypeAdapter(list[AlgorithmWithLatestParameter]).validate_python(payload)

    def operation_add_2(
        self,
        body: AlgorithmCreate,
    ) -> Any:
        payload = self._request(
            "POST",
            "/algorithms",
            body=body,
        )
        return TypeAdapter(AlgorithmWithLatestParameter).validate_python(payload)

    def operation_list_2(
        self,
    ) -> Any:
        payload = self._request(
            "GET",
            "/algorithm-parameters",
        )
        return TypeAdapter(list[AlgorithmParameter]).validate_python(payload)

    def operation_add_3(
        self,
        body: AlgorithmParameter,
    ) -> Any:
        payload = self._request(
            "POST",
            "/algorithm-parameters",
            body=body,
        )
        return TypeAdapter(AlgorithmParameter).validate_python(payload)

    def operation_update_algorithm(
        self,
        slot_name: str,
        variant_id: int,
        body: VariantUpdateAlgorithmInput,
    ) -> Any:
        payload = self._request(
            "PATCH",
            f"/slots/{quote(str(slot_name), safe='')}/variants/{quote(str(variant_id), safe='')}/updateAlgorithm",
            body=body,
        )
        return TypeAdapter(Variant).validate_python(payload)

    def operation_refresh_salt(
        self,
        slot_name: str,
    ) -> Any:
        payload = self._request(
            "PATCH",
            f"/slots/{quote(str(slot_name), safe='')}/salts/refresh",
        )
        return TypeAdapter(Slot).validate_python(payload)

    def operation_change_ramp_up_percentage(
        self,
        slot_name: str,
        experiment_id: int,
        body: ChangeRampUpPercentageInput,
    ) -> Any:
        payload = self._request(
            "PATCH",
            f"/slots/{quote(str(slot_name), safe='')}/experiments/{quote(str(experiment_id), safe='')}/changeRampUpPercentage",
            body=body,
        )
        return TypeAdapter(Experiment).validate_python(payload)

    def operation_get_one(
        self,
        algorithm_name: str,
        algorithm_version: str,
    ) -> Any:
        payload = self._request(
            "GET",
            f"/algorithms/{quote(str(algorithm_name), safe='')}/{quote(str(algorithm_version), safe='')}",
        )
        return TypeAdapter(Algorithm).validate_python(payload)

    def operation_patch(
        self,
        algorithm_name: str,
        algorithm_version: str,
        body: AlgorithmPatch,
    ) -> Any:
        self._request(
            "PATCH",
            f"/algorithms/{quote(str(algorithm_name), safe='')}/{quote(str(algorithm_version), safe='')}",
            body=body,
        )
        return None

    def operation_get_one_by_name(
        self,
        slot_name: str,
    ) -> Any:
        payload = self._request(
            "GET",
            f"/slots/{quote(str(slot_name), safe='')}",
        )
        return TypeAdapter(Slot).validate_python(payload)

    def operation_list_all(
        self,
        slot_name: str,
    ) -> Any:
        payload = self._request(
            "GET",
            f"/slots/{quote(str(slot_name), safe='')}/variants",
        )
        return TypeAdapter(list[Variant]).validate_python(payload)

    def operation_get_variant(
        self,
        slot_name: str,
        variant_id: int,
    ) -> Any:
        payload = self._request(
            "GET",
            f"/slots/{quote(str(slot_name), safe='')}/variants/{quote(str(variant_id), safe='')}",
        )
        return TypeAdapter(Variant).validate_python(payload)

    def operation_list_active(
        self,
        slot_name: str,
    ) -> Any:
        payload = self._request(
            "GET",
            f"/slots/{quote(str(slot_name), safe='')}/variants/active",
        )
        return TypeAdapter(list[Variant]).validate_python(payload)

    def operation_list_all_by_slot(
        self,
        slot_name: str,
    ) -> Any:
        payload = self._request(
            "GET",
            f"/slots/{quote(str(slot_name), safe='')}/variantAlgorithmLogs",
        )
        return TypeAdapter(list[VariantAlgorithmLog]).validate_python(payload)

    def operation_list_all_by_slot_1(
        self,
        slot_name: str,
    ) -> Any:
        payload = self._request(
            "GET",
            f"/slots/{quote(str(slot_name), safe='')}/userForcedAssignments",
        )
        return TypeAdapter(UserForcedAssignmentResponse).validate_python(payload)

    def operation_list_3(
        self,
        slot_name: str,
    ) -> Any:
        payload = self._request(
            "GET",
            f"/slots/{quote(str(slot_name), safe='')}/shards",
        )
        return TypeAdapter(list[Shard]).validate_python(payload)

    def operation_get_one_1(
        self,
        slot_name: str,
        shard_id: int,
    ) -> Any:
        payload = self._request(
            "GET",
            f"/slots/{quote(str(slot_name), safe='')}/shards/{quote(str(shard_id), safe='')}",
        )
        return TypeAdapter(Shard).validate_python(payload)

    def operation_list_4(
        self,
        slot_name: str,
    ) -> Any:
        payload = self._request(
            "GET",
            f"/slots/{quote(str(slot_name), safe='')}/shard-logs",
        )
        return TypeAdapter(list[ShardLog]).validate_python(payload)

    def operation_list_salts(
        self,
        slot_name: str,
    ) -> Any:
        payload = self._request(
            "GET",
            f"/slots/{quote(str(slot_name), safe='')}/salts",
        )
        return TypeAdapter(list[SlotSalt]).validate_python(payload)

    def operation_get_one_2(
        self,
        slot_name: str,
        experiment_id: int,
    ) -> Any:
        payload = self._request(
            "GET",
            f"/slots/{quote(str(slot_name), safe='')}/experiments/{quote(str(experiment_id), safe='')}",
        )
        return TypeAdapter(Experiment).validate_python(payload)

    def operation_list_all_by_experiment(
        self,
        slot_name: str,
    ) -> Any:
        payload = self._request(
            "GET",
            f"/slots/{quote(str(slot_name), safe='')}/experimentRampUpLog",
        )
        return TypeAdapter(list[ExperimentRampUpLog]).validate_python(payload)

    def operation_get_default_variant_and_active_experiments(
        self,
        slot_name: str,
    ) -> Any:
        payload = self._request(
            "GET",
            f"/slots/{quote(str(slot_name), safe='')}/defaultVariantAndActiveExperiments",
        )
        return TypeAdapter(SlotActiveInfo).validate_python(payload)

    def operation_get_latest_algorithm_parameter(
        self,
        algorithm_name: str,
        algorithm_version: str,
    ) -> Any:
        payload = self._request(
            "GET",
            f"/algorithms/{quote(str(algorithm_name), safe='')}/{quote(str(algorithm_version), safe='')}/latest-algorithm-parameter",
        )
        return TypeAdapter(AlgorithmParameter).validate_python(payload)

    def operation_list_active_with_variants(
        self,
    ) -> Any:
        payload = self._request(
            "GET",
            "/algorithms/with-active-variants",
        )
        return TypeAdapter(AlgorithmWithActiveVariantsResponse).validate_python(payload)

    def operation_list_active_1(
        self,
    ) -> Any:
        payload = self._request(
            "GET",
            "/algorithms/active",
        )
        return TypeAdapter(list[AlgorithmWithLatestParameter]).validate_python(payload)

    def operation_list_5(
        self,
    ) -> Any:
        payload = self._request(
            "GET",
            "/algorithm-state-logs",
        )
        return TypeAdapter(list[AlgorithmStateLog]).validate_python(payload)

    def operation_get_one_3(
        self,
        algorithm_parameter_id: str,
    ) -> Any:
        payload = self._request(
            "GET",
            f"/algorithm-parameters/{quote(str(algorithm_parameter_id), safe='')}",
        )
        return TypeAdapter(AlgorithmParameter).validate_python(payload)

    def operation_get_by_algorithm(
        self,
        algorithm_name: str,
        algorithm_version: str,
        *,
        top: int | None = None,
        min_created_at: datetime | None = None,
    ) -> Any:
        params = {
            "top": top,
            "minCreatedAt": min_created_at.isoformat() if min_created_at is not None else None,
        }
        params = {key: value for key, value in params.items() if value is not None}
        payload = self._request(
            "GET",
            f"/algorithm-parameters/by-algorithm/{quote(str(algorithm_name), safe='')}/{quote(str(algorithm_version), safe='')}",
            params=params,
        )
        return TypeAdapter(list[AlgorithmParameter]).validate_python(payload)
