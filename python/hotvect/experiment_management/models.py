# Generated EMS client binding. Do not edit manually.
from __future__ import annotations

from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


class EmsModel(BaseModel):
    """Strict base for models generated from the pinned EMS contract."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)


class UserForcedAssignmentInput(EmsModel):
    variant_id: int


class Problem(EmsModel):
    title: str | None = None
    detail: str | None = None
    status: StatusType | None = None
    instance: str | None = None
    type: str | None = None
    parameters: dict[str, Any] | None = None


class StatusType(EmsModel):
    reason_phrase: str | None = Field(default=None, alias="reasonPhrase")
    status_code: int | None = Field(default=None, alias="statusCode")


class SlotInput(EmsModel):
    slot_name: str = Field(min_length=1, pattern="[a-z0-9-]+")
    num_shards: int = Field(le=100)
    algorithm_name: str = Field(min_length=1)
    algorithm_version: str = Field(min_length=1)


class Slot(EmsModel):
    name: str = Field(min_length=1)
    created_at: datetime | None = None
    salts: list[str]


class SlotSalt(EmsModel):
    salt: str | None = None
    slot: Slot
    created_at: datetime | None = None
    terminated_at: datetime | None = None


class Algorithm(EmsModel):
    algorithm_name: str = Field(min_length=1)
    algorithm_version: str = Field(min_length=1)
    state: str | None = None
    absolute_s3_jar_path: str = Field(min_length=1)
    created_at: datetime | None = None
    algorithm_training_image_name: str
    parameter_mode: Literal["REQUIRED", "NONE"]


class Experiment(EmsModel):
    experiment_id: int | None = None
    experiment_name: str = Field(min_length=1)
    created_at: datetime | None = None
    terminated_at: datetime | None = None
    variants: list[Variant] | None = None
    ramp_up_percentage: int = Field(le=100)


class Variant(EmsModel):
    variant_id: int | None = None
    algorithm: Algorithm
    experiment: Experiment | None = None
    created_at: datetime | None = None
    terminated_at: datetime | None = None
    is_default: bool | None = None
    is_control: bool | None = None
    shard_allocation_ratio: int | None = None


class ExperimentCreate(EmsModel):
    variants: list[ExperimentVariantInput] = Field(min_length=1, max_length=2147483647)
    experiment_name: str = Field(min_length=1)
    number_of_shards: int = Field(le=100)
    ramp_up_percentage: int = Field(le=100)


class ExperimentVariantInput(EmsModel):
    algorithm_name: str = Field(min_length=1)
    algorithm_version: str = Field(min_length=1)
    is_control: bool
    shard_allocation_ratio: int


class AlgorithmParameter(EmsModel):
    algorithm_parameter_id: str
    algorithm: Algorithm
    evaluation_results: str | None = None
    created_at: datetime | None = None
    absolute_s3_path: str = Field(min_length=1)


class AlgorithmWithLatestParameter(EmsModel):
    algorithm_name: str = Field(min_length=1)
    algorithm_version: str = Field(min_length=1)
    state: str | None = None
    absolute_s3_jar_path: str = Field(min_length=1)
    created_at: datetime | None = None
    algorithm_training_image_name: str
    parameter_mode: Literal["REQUIRED", "NONE"]
    latest_algorithm_parameter: AlgorithmParameter | None = None


class AlgorithmCreate(EmsModel):
    algorithm_name: str = Field(min_length=0, max_length=255)
    algorithm_version: str = Field(min_length=0, max_length=255)
    absolute_s3_jar_path: str = Field(min_length=1)
    algorithm_training_image_name: str = Field(min_length=0, max_length=255)
    parameter_mode: Literal["REQUIRED", "NONE"]


class VariantUpdateAlgorithmInput(EmsModel):
    algorithm_name: str = Field(min_length=1)
    algorithm_version: str = Field(min_length=1)


class ChangeRampUpPercentageInput(EmsModel):
    new_ramp_up_percentage: int = Field(le=100)


class AlgorithmPatch(EmsModel):
    state: str = Field(pattern="ACTIVE|INACTIVE")


class VariantAlgorithmLog(EmsModel):
    variant_algorithm_log_id: int | None = None
    variant: Variant
    algorithm: Algorithm
    created_at: datetime | None = None


class UserForcedAssignment(EmsModel):
    user_forced_assignment_id: int | None = None
    user_id: str = Field(min_length=1)
    variant: Variant
    created_at: datetime | None = None


class UserForcedAssignmentResponse(EmsModel):
    user_forced_assignments: list[UserForcedAssignment] | None = None


class Shard(EmsModel):
    id: int | None = None
    shard_id: int
    experiment: Experiment | None = None
    created_at: datetime | None = None


class ShardLog(EmsModel):
    shard_log_id: int | None = None
    shard: Shard
    experiment: Experiment | None = None
    created_at: datetime | None = None


class ExperimentWithVariantDetails(EmsModel):
    experiment_id: int
    experiment_name: str = Field(min_length=1)
    created_at: datetime
    terminated_at: datetime | None = None
    variants: list[VariantBasicDetails]
    ramp_up_percentage: int


class SlotAllExperimentsWithVariantDetailsResponse(EmsModel):
    slot_name: str = Field(min_length=1)
    experiments: list[ExperimentWithVariantDetails]


class VariantBasicDetails(EmsModel):
    variant_id: int | None = None
    algorithm: Algorithm | None = None
    is_default: bool | None = None
    is_control: bool | None = None
    shard_allocation_ratio: int | None = None


class ExperimentRampUpLog(EmsModel):
    experiment_ramp_up_log_id: int | None = None
    experiment: Experiment
    terminated_at: datetime | None = None
    ramp_up_percentage: int
    created_at: datetime | None = None


class SlotActiveInfo(EmsModel):
    slot_salt: str = Field(min_length=1)
    total_number_of_shards: int
    default_variant: SlotActiveInfoVariantResponse
    experiments: list[SlotActiveInfoExperimentResponse]
    user_forced_assignments: list[SlotActiveInfoUserForcedAssignmentResponse]


class SlotActiveInfoAlgorithmResponse(EmsModel):
    algorithm_name: str = Field(min_length=1)
    algorithm_version: str = Field(min_length=1)
    latest_algorithm_parameter: str | None = None
    absolute_s3_algorithm_jar_path: str = Field(min_length=1)
    absolute_s3_algorithm_parameter_path: str | None = None


class SlotActiveInfoExperimentResponse(EmsModel):
    experiment_id: int
    experiment_name: str = Field(min_length=1)
    variants: list[SlotActiveInfoVariantResponse]
    shards: list[SlotActiveInfoShardResponse]
    ramp_up_percentage: int
    created_at: datetime


class SlotActiveInfoShardResponse(EmsModel):
    shard_id: int
    created_at: datetime


class SlotActiveInfoUserForcedAssignmentResponse(EmsModel):
    user_id: str = Field(min_length=1)
    variant_id: int


class SlotActiveInfoVariantResponse(EmsModel):
    variant_id: int
    algorithm: SlotActiveInfoAlgorithmResponse
    created_at: datetime
    is_default: bool | None = None
    is_control: bool | None = None
    shard_allocation_ratio: int | None = None


class AlgorithmWithActiveVariantsResponse(EmsModel):
    algorithms: list[AlgorithmsWithVariantsDTO] | None = None


class AlgorithmsWithVariantsDTO(EmsModel):
    algorithm_name: str | None = Field(default=None, alias="algorithmName")
    algorithm_version: str | None = Field(default=None, alias="algorithmVersion")
    state: str | None = None
    absolute_s3_jar_path: str | None = Field(default=None, alias="absoluteS3JarPath")
    created_at: datetime | None = Field(default=None, alias="createdAt")
    algorithm_training_image_name: str | None = Field(default=None, alias="algorithmTrainingImageName")
    parameter_mode: Literal["REQUIRED", "NONE"] | None = Field(default=None, alias="parameterMode")
    latest_algorithm_parameter: dict[str, Any] | None = Field(default=None, alias="latestAlgorithmParameter")
    variants: list[VariantInfo] | None = None


class VariantInfo(EmsModel):
    variant_id: int | None = Field(default=None, alias="variantId")
    slot_name: str | None = Field(default=None, alias="slotName")


class AlgorithmStateLog(EmsModel):
    algorithm_stage_log_id: int | None = None
    algorithm: Algorithm
    state: str = Field(min_length=1)
    created_at: datetime | None = None


UserForcedAssignmentInput.model_rebuild()
Problem.model_rebuild()
StatusType.model_rebuild()
SlotInput.model_rebuild()
Slot.model_rebuild()
SlotSalt.model_rebuild()
Algorithm.model_rebuild()
Experiment.model_rebuild()
Variant.model_rebuild()
ExperimentCreate.model_rebuild()
ExperimentVariantInput.model_rebuild()
AlgorithmParameter.model_rebuild()
AlgorithmWithLatestParameter.model_rebuild()
AlgorithmCreate.model_rebuild()
VariantUpdateAlgorithmInput.model_rebuild()
ChangeRampUpPercentageInput.model_rebuild()
AlgorithmPatch.model_rebuild()
VariantAlgorithmLog.model_rebuild()
UserForcedAssignment.model_rebuild()
UserForcedAssignmentResponse.model_rebuild()
Shard.model_rebuild()
ShardLog.model_rebuild()
ExperimentWithVariantDetails.model_rebuild()
SlotAllExperimentsWithVariantDetailsResponse.model_rebuild()
VariantBasicDetails.model_rebuild()
ExperimentRampUpLog.model_rebuild()
SlotActiveInfo.model_rebuild()
SlotActiveInfoAlgorithmResponse.model_rebuild()
SlotActiveInfoExperimentResponse.model_rebuild()
SlotActiveInfoShardResponse.model_rebuild()
SlotActiveInfoUserForcedAssignmentResponse.model_rebuild()
SlotActiveInfoVariantResponse.model_rebuild()
AlgorithmWithActiveVariantsResponse.model_rebuild()
AlgorithmsWithVariantsDTO.model_rebuild()
VariantInfo.model_rebuild()
AlgorithmStateLog.model_rebuild()

__all__ = [
    "EmsModel",
    "UserForcedAssignmentInput",
    "Problem",
    "StatusType",
    "SlotInput",
    "Slot",
    "SlotSalt",
    "Algorithm",
    "Experiment",
    "Variant",
    "ExperimentCreate",
    "ExperimentVariantInput",
    "AlgorithmParameter",
    "AlgorithmWithLatestParameter",
    "AlgorithmCreate",
    "VariantUpdateAlgorithmInput",
    "ChangeRampUpPercentageInput",
    "AlgorithmPatch",
    "VariantAlgorithmLog",
    "UserForcedAssignment",
    "UserForcedAssignmentResponse",
    "Shard",
    "ShardLog",
    "ExperimentWithVariantDetails",
    "SlotAllExperimentsWithVariantDetailsResponse",
    "VariantBasicDetails",
    "ExperimentRampUpLog",
    "SlotActiveInfo",
    "SlotActiveInfoAlgorithmResponse",
    "SlotActiveInfoExperimentResponse",
    "SlotActiveInfoShardResponse",
    "SlotActiveInfoUserForcedAssignmentResponse",
    "SlotActiveInfoVariantResponse",
    "AlgorithmWithActiveVariantsResponse",
    "AlgorithmsWithVariantsDTO",
    "VariantInfo",
    "AlgorithmStateLog",
]
