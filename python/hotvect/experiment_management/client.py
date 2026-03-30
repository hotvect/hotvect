from __future__ import annotations

import typing
from enum import Enum

import requests
from pydantic import TypeAdapter
from requests.auth import AuthBase

from .models import (
    AlgorithmParameter,
    AlgorithmParameterSpec,
    AlgorithmSpec,
    AlgorithmState,
    AlgorithmStateLog,
    AlgorithmWithActiveVariantsResponse,
    AlgorithmWithLatestParameter,
    CampaignForcedAssignment,
    Experiment,
    ExperimentRampUpLog,
    ExperimentSpec,
    ExperimentWithVariantDetails,
    Shard,
    ShardLog,
    Slot,
    SlotActiveInfo,
    SlotSalt,
    SlotSpec,
    UserForcedAssignment,
    Variant,
    VariantAlgorithmLog,
)


class Environment(Enum):
    def __init__(self, url: str):
        self.url = url

    STAGING = "https://example-experiment-service-stg.example.com"
    PROD = "https://example-experiment-service.example.com"
    LOCAL = "http://localhost:8080"


class Method(Enum):
    GET, POST, PUT, PATCH, DELETE = range(5)


class ExperimentManagementConnection:
    def __init__(
        self,
        *,
        environment: Environment | str,
        connect_timeout: float = 1.0,
        read_timeout: float = 1.0,
        bearer_auth: AuthBase,
    ):
        self._base_url = environment.url if isinstance(environment, Environment) else str(environment)
        self._connect_timeout = connect_timeout
        self._read_timeout = read_timeout
        self._bearer_auth = bearer_auth

    def make_request(
        self,
        *,
        method: Method,
        endpoint: str,
        params: typing.Optional[typing.Dict] = None,
        body: typing.Optional[typing.Dict] = None,
    ) -> requests.Response:
        return requests.request(
            method=method.name,
            url=self._base_url + endpoint,
            params=params,
            json=body,
            auth=self._bearer_auth,
            timeout=(self._connect_timeout, self._read_timeout),
        )


class ExperimentManagementClient:
    """
    Wrapper around example-experiment-service REST API.
    """

    def __init__(self, connection: ExperimentManagementConnection):
        self._connection = connection

    def _request(
        self,
        method: Method,
        endpoint: str,
        params: typing.Optional[typing.Dict] = None,
        body: typing.Optional[typing.Dict] = None,
        *,
        ignore_404: bool = False,
    ):
        response = self._connection.make_request(method=method, endpoint=endpoint, params=params, body=body)
        if not (response.status_code == 404 and ignore_404):
            response.raise_for_status()
        return response.json() if response.text else None

    def get_experiment(
        self, slot_name: str, experiment_id: int, *, ignore_404: bool = False
    ) -> typing.Optional[Experiment]:
        endpoint = f"/slots/{slot_name}/experiments/{experiment_id}"
        experiment_json = self._request(Method.GET, endpoint, ignore_404=ignore_404)
        return Experiment.model_validate(experiment_json) if experiment_json else None

    def create_experiment(self, slot_name: str, experiment: ExperimentSpec) -> None:
        endpoint = f"/slots/{slot_name}/experiments"
        self._request(Method.POST, endpoint, body=experiment.model_dump())

    def terminate_experiment(self, slot_name: str, experiment_id: int) -> None:
        endpoint = f"/slots/{slot_name}/experiments/{experiment_id}/terminate"
        self._request(Method.PUT, endpoint)

    def change_ramp_up_percentage(
        self, slot_name: str, experiment_id: int, new_ramp_up_percentage: int
    ) -> typing.Optional[Experiment]:
        endpoint = f"/slots/{slot_name}/experiments/{experiment_id}/changeRampUpPercentage"
        patched_experiment_json = self._request(
            Method.PATCH, endpoint, body={"new_ramp_up_percentage": new_ramp_up_percentage}
        )
        return Experiment.model_validate(patched_experiment_json) if patched_experiment_json else None

    def get_experiments(self, slot_name: str) -> typing.Optional[typing.List[ExperimentWithVariantDetails]]:
        endpoint = f"/slots/{slot_name}/experiments"
        response = self._request(Method.GET, endpoint)
        return (
            TypeAdapter(list[ExperimentWithVariantDetails]).validate_python(response["experiments"])
            if response
            else None
        )

    def get_algorithms(self) -> typing.Optional[typing.List[AlgorithmWithLatestParameter]]:
        algorithms_json = self._request(Method.GET, "/algorithms")
        if not algorithms_json:
            return None
        return TypeAdapter(list[AlgorithmWithLatestParameter]).validate_python(algorithms_json["algorithms"])

    def get_algorithm(
        self, algorithm_name: str, algorithm_version: str, *, ignore_404: bool = False
    ) -> typing.Optional[AlgorithmWithLatestParameter]:
        endpoint = f"/algorithms/{algorithm_name}/{algorithm_version}"
        algorithm_json = self._request(Method.GET, endpoint, ignore_404=ignore_404)
        return AlgorithmWithLatestParameter.model_validate(algorithm_json) if algorithm_json else None

    def get_latest_algorithm_parameter(
        self, algorithm_name: str, algorithm_version: str
    ) -> typing.Optional[AlgorithmParameter]:
        endpoint = f"/algorithms/{algorithm_name}/{algorithm_version}/latest-algorithm-parameter"
        algorithm_parameter_json = self._request(Method.GET, endpoint)
        return AlgorithmParameter.model_validate(algorithm_parameter_json) if algorithm_parameter_json else None

    def get_active_algorithms(self) -> typing.Optional[typing.List[AlgorithmWithLatestParameter]]:
        algorithms_json = self._request(Method.GET, "/algorithms/active")
        if not algorithms_json:
            return None
        return TypeAdapter(list[AlgorithmWithLatestParameter]).validate_python(algorithms_json["algorithms"])

    def create_algorithm(self, algorithm: AlgorithmSpec) -> None:
        self._request(Method.POST, "/algorithms", body=algorithm.model_dump())

    def promote_algorithm(self, slot_name: str, algorithm_name: str, algorithm_version: str) -> None:
        endpoint = f"/algorithms/{algorithm_name}/{algorithm_version}/promote"
        self._request(Method.PUT, endpoint, body={"slot_name": slot_name})

    def update_algorithm(
        self, algorithm_name: str, algorithm_version: str, new_state: AlgorithmState
    ) -> typing.Optional[AlgorithmWithLatestParameter]:
        endpoint = f"/algorithms/{algorithm_name}/{algorithm_version}"
        updated_algorithm_json = self._request(Method.PATCH, endpoint, body={"state": new_state.name})
        return AlgorithmWithLatestParameter.model_validate(updated_algorithm_json) if updated_algorithm_json else None

    def activate_algorithm(
        self, algorithm_name: str, algorithm_version: str
    ) -> typing.Optional[AlgorithmWithLatestParameter]:
        return self.update_algorithm(algorithm_name, algorithm_version, AlgorithmState.ACTIVE)

    def get_shards(self, slot_name: str) -> typing.Optional[typing.List[Shard]]:
        shards_json = self._request(Method.GET, f"/slots/{slot_name}/shards")
        if not shards_json:
            return None
        return TypeAdapter(list[Shard]).validate_python(shards_json["shards"])

    def get_shard(self, slot_name: str, shard_id: int) -> typing.Optional[Shard]:
        shard_json = self._request(Method.GET, f"/slots/{slot_name}/shards/{shard_id}")
        return Shard.model_validate(shard_json) if shard_json else None

    def get_shard_logs(self, slot_name: str) -> typing.Optional[typing.List[ShardLog]]:
        shard_logs_json = self._request(Method.GET, f"/slots/{slot_name}/shard-logs")
        if not shard_logs_json:
            return None
        return TypeAdapter(list[ShardLog]).validate_python(shard_logs_json["shard_logs"])

    def get_algorithm_parameters(self) -> typing.Optional[typing.List[AlgorithmParameter]]:
        algorithm_parameters_json = self._request(Method.GET, "/algorithm-parameters")
        if not algorithm_parameters_json:
            return None
        if isinstance(algorithm_parameters_json, list):
            return TypeAdapter(list[AlgorithmParameter]).validate_python(algorithm_parameters_json)
        if isinstance(algorithm_parameters_json, dict) and "algorithm_parameters" in algorithm_parameters_json:
            return TypeAdapter(list[AlgorithmParameter]).validate_python(
                algorithm_parameters_json["algorithm_parameters"]
            )
        raise ValueError("Unexpected /algorithm-parameters response shape")

    def get_algorithm_parameter(self, algorithm_parameter_id: str) -> typing.Optional[AlgorithmParameter]:
        endpoint = f"/algorithm-parameters/{algorithm_parameter_id}"
        algorithm_parameter_json = self._request(Method.GET, endpoint)
        return AlgorithmParameter.model_validate(algorithm_parameter_json) if algorithm_parameter_json else None

    def create_algorithm_parameter(self, algorithm_parameter: AlgorithmParameterSpec) -> None:
        self._request(Method.POST, "/algorithm-parameters", body=algorithm_parameter.model_dump())

    def get_algorithm_state_logs(self) -> typing.Optional[typing.List[AlgorithmStateLog]]:
        algorithm_state_logs_json = self._request(Method.GET, "/algorithm-state-logs")
        if not algorithm_state_logs_json:
            return None
        if isinstance(algorithm_state_logs_json, list):
            return TypeAdapter(list[AlgorithmStateLog]).validate_python(algorithm_state_logs_json)
        if isinstance(algorithm_state_logs_json, dict) and "algorithm_state_logs" in algorithm_state_logs_json:
            return TypeAdapter(list[AlgorithmStateLog]).validate_python(
                algorithm_state_logs_json["algorithm_state_logs"]
            )
        raise ValueError("Unexpected /algorithm-state-logs response shape")

    def get_algorithms_with_active_variants(self) -> typing.Optional[typing.List[AlgorithmWithActiveVariantsResponse]]:
        algorithms_json = self._request(Method.GET, "/algorithms/with-active-variants")
        if not algorithms_json:
            return None
        return TypeAdapter(list[AlgorithmWithActiveVariantsResponse]).validate_python(algorithms_json["algorithms"])

    def get_variants(self, slot_name: str) -> typing.Optional[typing.List[Variant]]:
        variants_json = self._request(Method.GET, f"/slots/{slot_name}/variants")
        if not variants_json:
            return None
        if isinstance(variants_json, list):
            return TypeAdapter(list[Variant]).validate_python(variants_json)
        if isinstance(variants_json, dict) and "variants" in variants_json:
            return TypeAdapter(list[Variant]).validate_python(variants_json["variants"])
        raise ValueError("Unexpected /variants response shape")

    def get_active_variants(self, slot_name: str) -> typing.Optional[typing.List[Variant]]:
        variants_json = self._request(Method.GET, f"/slots/{slot_name}/variants/active")
        if not variants_json:
            return None
        if isinstance(variants_json, list):
            return TypeAdapter(list[Variant]).validate_python(variants_json)
        if isinstance(variants_json, dict) and "variants" in variants_json:
            return TypeAdapter(list[Variant]).validate_python(variants_json["variants"])
        raise ValueError("Unexpected /variants/active response shape")

    def get_slots(self) -> typing.Optional[typing.List[Slot]]:
        slots_json = self._request(Method.GET, "/slots")
        return TypeAdapter(list[Slot]).validate_python(slots_json) if slots_json else None

    def get_slot(self, slot_name: str) -> typing.Optional[Slot]:
        slot_json = self._request(Method.GET, f"/slots/{slot_name}")
        return Slot.model_validate(slot_json) if slot_json else None

    def create_slot(self, slot: SlotSpec) -> None:
        self._request(Method.POST, "/slots", body=slot.model_dump())

    def get_slot_salts(self, slot_name: str) -> typing.Optional[typing.List[SlotSalt]]:
        slot_salts_json = self._request(Method.GET, f"/slots/{slot_name}/salts")
        return TypeAdapter(list[SlotSalt]).validate_python(slot_salts_json["slot_salts"]) if slot_salts_json else None

    def refresh_slot_salt(self, slot_name: str) -> typing.Optional[Slot]:
        slot_json = self._request(Method.PATCH, f"/slots/{slot_name}/salts/refresh")
        return Slot.model_validate(slot_json) if slot_json else None

    def get_default_variant_and_active_experiments(self, slot_name: str) -> typing.Optional[SlotActiveInfo]:
        slot_active_info_json = self._request(Method.GET, f"/slots/{slot_name}/defaultVariantAndActiveExperiments")
        return SlotActiveInfo.model_validate(slot_active_info_json) if slot_active_info_json else None

    def get_user_forced_assignments(self, slot_name: str) -> typing.Optional[typing.List[UserForcedAssignment]]:
        user_forced_assignments_json = self._request(Method.GET, f"/slots/{slot_name}/userForcedAssignments")
        if not user_forced_assignments_json:
            return None
        return TypeAdapter(list[UserForcedAssignment]).validate_python(
            user_forced_assignments_json["user_forced_assignments"]
        )

    def get_user_forced_assignment(self, slot_name: str, user_id: str) -> typing.Optional[UserForcedAssignment]:
        endpoint = f"/slots/{slot_name}/userForcedAssignments/{user_id}"
        user_forced_assignment_json = self._request(Method.GET, endpoint)
        return UserForcedAssignment.model_validate(user_forced_assignment_json) if user_forced_assignment_json else None

    def upsert_user_forced_assignment(self, slot_name: str, user_id: str, variant_id: int) -> None:
        endpoint = f"/slots/{slot_name}/userForcedAssignments/{user_id}"
        self._request(Method.PUT, endpoint, body={"variant_id": variant_id})

    def delete_user_forced_assignment(self, slot_name: str, user_id: str) -> None:
        endpoint = f"/slots/{slot_name}/userForcedAssignments/{user_id}"
        self._request(Method.DELETE, endpoint)

    def get_variant_algorithm_logs(self, slot_name: str) -> typing.Optional[typing.List[VariantAlgorithmLog]]:
        variant_algorithm_logs_json = self._request(Method.GET, f"/slots/{slot_name}/variantAlgorithmLogs")
        if not variant_algorithm_logs_json:
            return None
        return TypeAdapter(list[VariantAlgorithmLog]).validate_python(
            variant_algorithm_logs_json["variant_algorithm_logs"]
        )

    def get_variant(self, slot_name: str, variant_id: int) -> Variant:
        variant_json = self._request(Method.GET, f"/slots/{slot_name}/variants/{variant_id}")
        return Variant.model_validate(variant_json)

    def update_variant_algorithm(
        self,
        slot_name: str,
        variant_id: int,
        new_algorithm_name: str,
        new_algorithm_version: str,
    ) -> typing.Optional[Variant]:
        endpoint = f"/slots/{slot_name}/variants/{variant_id}/updateAlgorithm"
        updated_variant_json = self._request(
            Method.PATCH,
            endpoint,
            body={"algorithm_name": new_algorithm_name, "algorithm_version": new_algorithm_version},
        )
        return Variant.model_validate(updated_variant_json) if updated_variant_json else None

    def get_campaign_forced_assignments(self, slot_name: str) -> typing.Optional[typing.List[CampaignForcedAssignment]]:
        campaign_forced_assignments_json = self._request(Method.GET, f"/slots/{slot_name}/campaignForcedAssignments")
        if not campaign_forced_assignments_json:
            return None
        return TypeAdapter(list[CampaignForcedAssignment]).validate_python(
            campaign_forced_assignments_json["campaign_forced_assignments"]
        )

    def get_campaign_forced_assignment(
        self, slot_name: str, campaign_id: str
    ) -> typing.Optional[CampaignForcedAssignment]:
        endpoint = f"/slots/{slot_name}/campaignForcedAssignments/{campaign_id}"
        campaign_forced_assignment_json = self._request(Method.GET, endpoint)
        return (
            CampaignForcedAssignment.model_validate(campaign_forced_assignment_json)
            if campaign_forced_assignment_json
            else None
        )

    def upsert_campaign_forced_assignment(self, slot_name: str, campaign_id: str, variant_id: int) -> None:
        endpoint = f"/slots/{slot_name}/campaignForcedAssignments/{campaign_id}"
        self._request(Method.PUT, endpoint, body={"variant_id": variant_id})

    def delete_campaign_forced_assignment(self, slot_name: str, campaign_id: str) -> None:
        endpoint = f"/slots/{slot_name}/campaignForcedAssignments/{campaign_id}"
        self._request(Method.DELETE, endpoint)

    def get_experiment_rampup_logs(self, slot_name: str) -> typing.Optional[typing.List[ExperimentRampUpLog]]:
        # Note: endpoint name is `experimentRampUpLog` in the OpenAPI spec. Some older clients used
        # `ExperimentRampUpLog`. Prefer the spec casing.
        rampup_logs_json = self._request(Method.GET, f"/slots/{slot_name}/experimentRampUpLog")
        if not rampup_logs_json:
            return None
        if isinstance(rampup_logs_json, list):
            return TypeAdapter(list[ExperimentRampUpLog]).validate_python(rampup_logs_json)
        if isinstance(rampup_logs_json, dict) and "experiment_ramp_up_log" in rampup_logs_json:
            return TypeAdapter(list[ExperimentRampUpLog]).validate_python(rampup_logs_json["experiment_ramp_up_log"])
        raise ValueError("Unexpected experimentRampUpLog response shape")
