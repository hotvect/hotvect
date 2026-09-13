from __future__ import annotations

from datetime import datetime
from typing import Any

import requests
from requests.auth import AuthBase

from ._generated_operations import _GeneratedEmsOperations
from .models import (
    Algorithm,
    AlgorithmCreate,
    AlgorithmParameter,
    AlgorithmPatch,
    AlgorithmStateLog,
    AlgorithmsWithVariantsDTO,
    AlgorithmWithActiveVariantsResponse,
    AlgorithmWithLatestParameter,
    ChangeRampUpPercentageInput,
    Experiment,
    ExperimentCreate,
    ExperimentRampUpLog,
    ExperimentWithVariantDetails,
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

DEFAULT_CONNECT_TIMEOUT_SECONDS = 5.0
DEFAULT_READ_TIMEOUT_SECONDS = 15.0


class ExperimentManagementClient:
    """Synchronous typed client for a configured EMS endpoint."""

    def __init__(
        self,
        *,
        base_url: str,
        auth: AuthBase,
        connect_timeout: float = DEFAULT_CONNECT_TIMEOUT_SECONDS,
        read_timeout: float = DEFAULT_READ_TIMEOUT_SECONDS,
    ):
        normalized_base_url = base_url.rstrip("/")
        if not normalized_base_url:
            raise ValueError("base_url must not be empty")
        if connect_timeout <= 0:
            raise ValueError("connect_timeout must be greater than zero")
        if read_timeout <= 0:
            raise ValueError("read_timeout must be greater than zero")
        self._base_url = normalized_base_url
        self._connect_timeout = connect_timeout
        self._read_timeout = read_timeout
        self._auth = auth
        self._operations = _GeneratedEmsOperations(self._make_request)

    def _make_request(
        self,
        *,
        method: str,
        endpoint: str,
        params: dict[str, Any] | None = None,
        body: dict[str, Any] | None = None,
    ) -> requests.Response:
        if not endpoint.startswith("/"):
            raise ValueError(f"EMS endpoint must start with '/': {endpoint}")
        return requests.request(
            method=method,
            url=self._base_url + endpoint,
            params=params,
            json=body,
            auth=self._auth,
            timeout=(self._connect_timeout, self._read_timeout),
        )

    def get_experiment(self, slot_name: str, experiment_id: int) -> Experiment:
        return self._operations.operation_get_one_2(slot_name, experiment_id)

    def create_experiment(self, slot_name: str, experiment: ExperimentCreate) -> Experiment:
        return self._operations.operation_add_1(slot_name, experiment)

    def terminate_experiment(self, slot_name: str, experiment_id: int) -> None:
        self._operations.operation_terminate(slot_name, experiment_id)

    def change_ramp_up_percentage(
        self,
        slot_name: str,
        experiment_id: int,
        change: ChangeRampUpPercentageInput,
    ) -> Experiment:
        return self._operations.operation_change_ramp_up_percentage(slot_name, experiment_id, change)

    def get_experiments(self, slot_name: str) -> list[ExperimentWithVariantDetails]:
        response: SlotAllExperimentsWithVariantDetailsResponse = self._operations.operation_get_all(slot_name)
        return response.experiments

    def get_algorithms(self) -> list[AlgorithmWithLatestParameter]:
        return self._operations.operation_list_1()

    def get_algorithm(self, algorithm_name: str, algorithm_version: str) -> Algorithm:
        return self._operations.operation_get_one(algorithm_name, algorithm_version)

    def get_latest_algorithm_parameter(self, algorithm_name: str, algorithm_version: str) -> AlgorithmParameter:
        return self._operations.operation_get_latest_algorithm_parameter(algorithm_name, algorithm_version)

    def get_active_algorithms(self) -> list[AlgorithmWithLatestParameter]:
        return self._operations.operation_list_active_1()

    def create_algorithm(self, algorithm: AlgorithmCreate) -> AlgorithmWithLatestParameter:
        return self._operations.operation_add_2(algorithm)

    def update_algorithm(self, algorithm_name: str, algorithm_version: str, patch: AlgorithmPatch) -> None:
        self._operations.operation_patch(algorithm_name, algorithm_version, patch)

    def get_shards(self, slot_name: str) -> list[Shard]:
        return self._operations.operation_list_3(slot_name)

    def get_shard(self, slot_name: str, shard_id: int) -> Shard:
        return self._operations.operation_get_one_1(slot_name, shard_id)

    def get_shard_logs(self, slot_name: str) -> list[ShardLog]:
        return self._operations.operation_list_4(slot_name)

    def get_algorithm_parameters(self) -> list[AlgorithmParameter]:
        return self._operations.operation_list_2()

    def get_algorithm_parameter(self, algorithm_parameter_id: str) -> AlgorithmParameter:
        return self._operations.operation_get_one_3(algorithm_parameter_id)

    def get_algorithm_parameters_by_algorithm(
        self,
        algorithm_name: str,
        algorithm_version: str,
        *,
        top: int | None = None,
        min_created_at: datetime | None = None,
    ) -> list[AlgorithmParameter]:
        return self._operations.operation_get_by_algorithm(
            algorithm_name,
            algorithm_version,
            top=top,
            min_created_at=min_created_at,
        )

    def create_algorithm_parameter(self, parameter: AlgorithmParameter) -> AlgorithmParameter:
        return self._operations.operation_add_3(parameter)

    def get_algorithm_state_logs(self) -> list[AlgorithmStateLog]:
        return self._operations.operation_list_5()

    def get_algorithms_with_active_variants(self) -> list[AlgorithmsWithVariantsDTO]:
        response: AlgorithmWithActiveVariantsResponse = self._operations.operation_list_active_with_variants()
        if response.algorithms is None:
            raise ValueError("EMS active-algorithm response must contain algorithms")
        return response.algorithms

    def get_variants(self, slot_name: str) -> list[Variant]:
        return self._operations.operation_list_all(slot_name)

    def get_active_variants(self, slot_name: str) -> list[Variant]:
        return self._operations.operation_list_active(slot_name)

    def get_slots(self) -> list[Slot]:
        return self._operations.operation_list()

    def get_slot(self, slot_name: str) -> Slot:
        return self._operations.operation_get_one_by_name(slot_name)

    def create_slot(self, slot: SlotInput) -> Slot:
        return self._operations.operation_add(slot)

    def get_slot_salts(self, slot_name: str) -> list[SlotSalt]:
        return self._operations.operation_list_salts(slot_name)

    def refresh_slot_salt(self, slot_name: str) -> Slot:
        return self._operations.operation_refresh_salt(slot_name)

    def get_default_variant_and_active_experiments(self, slot_name: str) -> SlotActiveInfo:
        return self._operations.operation_get_default_variant_and_active_experiments(slot_name)

    def get_user_forced_assignments(self, slot_name: str) -> list[UserForcedAssignment]:
        response: UserForcedAssignmentResponse = self._operations.operation_list_all_by_slot_1(slot_name)
        return response.user_forced_assignments or []

    def get_user_forced_assignment(self, slot_name: str, user_id: str) -> UserForcedAssignment:
        return self._operations.operation_get_one_by_user_id(slot_name, user_id)

    def upsert_user_forced_assignment(
        self,
        slot_name: str,
        user_id: str,
        assignment: UserForcedAssignmentInput,
    ) -> None:
        self._operations.operation_upsert(slot_name, user_id, assignment)

    def delete_user_forced_assignment(self, slot_name: str, user_id: str) -> None:
        self._operations.operation_delete(slot_name, user_id)

    def get_variant_algorithm_logs(self, slot_name: str) -> list[VariantAlgorithmLog]:
        return self._operations.operation_list_all_by_slot(slot_name)

    def get_variant(self, slot_name: str, variant_id: int) -> Variant:
        return self._operations.operation_get_variant(slot_name, variant_id)

    def update_variant_algorithm(
        self,
        slot_name: str,
        variant_id: int,
        update: VariantUpdateAlgorithmInput,
    ) -> Variant:
        return self._operations.operation_update_algorithm(slot_name, variant_id, update)

    def promote_algorithm(self, algorithm_name: str, algorithm_version: str, slot: SlotInput) -> None:
        self._operations.operation_promote(algorithm_name, algorithm_version, slot)

    def get_experiment_ramp_up_logs(self, slot_name: str) -> list[ExperimentRampUpLog]:
        return self._operations.operation_list_all_by_experiment(slot_name)
