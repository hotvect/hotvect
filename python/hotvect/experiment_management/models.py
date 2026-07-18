from __future__ import annotations

from datetime import datetime
from enum import Enum

from pydantic import AliasChoices, BaseModel, Field


class AlgorithmSpec(BaseModel):
    algorithm_name: str
    algorithm_version: str
    absolute_s3_jar_path: str
    algorithm_training_image_name: str


class AlgorithmState(Enum):
    ACTIVE, INACTIVE = range(2)


class VariantSpec(BaseModel):
    algorithm_name: str
    algorithm_version: str
    is_control: bool
    shard_allocation_ratio: int


class ExperimentSpec(BaseModel):
    variants: list[VariantSpec]
    experiment_name: str
    number_of_shards: int
    ramp_up_percentage: int


class Algorithm(BaseModel):
    algorithm_name: str
    algorithm_version: str
    state: str
    absolute_s3_jar_path: str
    created_at: datetime
    algorithm_training_image_name: str


class AlgorithmParameter(BaseModel):
    algorithm_parameter_id: str
    algorithm: Algorithm
    evaluation_results: str
    created_at: datetime
    absolute_s3_path: str


class AlgorithmName(BaseModel):
    algorithm_name: str
    algorithm_version: str


class AlgorithmParameterSpec(BaseModel):
    algorithm_parameter_id: str
    algorithm: AlgorithmName
    evaluation_results: str
    absolute_s3_path: str


class AlgorithmWithLatestParameterSpec(BaseModel):
    algorithm_parameter_id: str
    absolute_s3_algorithm_parameter_path: str


class AlgorithmWithLatestParameter(BaseModel):
    algorithm_name: str
    algorithm_version: str
    state: str
    absolute_s3_jar_path: str
    created_at: datetime
    algorithm_training_image_name: str
    latest_algorithm_parameter: AlgorithmWithLatestParameterSpec | None


class AlgorithmStateLog(BaseModel):
    algorithm_stage_log_id: int
    algorithm: Algorithm
    state: str
    created_at: datetime


class VariantInfoForActiveAlgorithms(BaseModel):
    variant_id: int
    slot_name: str


class AlgorithmWithActiveVariantsResponse(BaseModel):
    algorithm_name: str
    algorithm_version: str
    state: str
    absolute_s3_jar_path: str
    created_at: datetime
    algorithm_training_image_name: str
    latest_algorithm_parameter: AlgorithmParameter | None
    variants: list[VariantInfoForActiveAlgorithms]


class Experiment(BaseModel):
    experiment_id: int
    experiment_name: str
    created_at: datetime
    terminated_at: datetime | None
    variants: list[int]
    ramp_up_percentage: int | None


class Variant(BaseModel):
    variant_id: int
    algorithm: Algorithm
    experiment: Experiment | None
    created_at: datetime
    terminated_at: datetime | None
    is_default: bool | None
    is_control: bool | None
    shard_allocation_ratio: int | None


class VariantBasicDetails(BaseModel):
    variant_id: int
    algorithm: Algorithm
    is_default: bool | None
    is_control: bool | None
    shard_allocation_ratio: int | None


class ExperimentWithVariantDetails(BaseModel):
    experiment_id: int
    experiment_name: str
    created_at: datetime
    terminated_at: datetime | None
    variants: list[VariantBasicDetails]
    ramp_up_percentage: int | None


class Shard(BaseModel):
    shard_id: int
    experiment: Experiment | None
    created_at: datetime


class ShardLog(BaseModel):
    shard_log_id: int
    shard: Shard
    experiment: Experiment | None
    created_at: datetime


class SlotSpec(BaseModel):
    slot_name: str
    num_shards: int = 100
    algorithm_name: str
    algorithm_version: str


class Slot(BaseModel):
    name: str
    created_at: datetime
    salts: list[str]


class SlotSalt(BaseModel):
    salt: str
    slot: Slot
    created_at: datetime
    terminated_at: datetime | None


class SlotActiveInfoAlgorithmResponse(BaseModel):
    algorithm_name: str
    algorithm_version: str
    latest_algorithm_parameter: str
    absolute_s3_algorithm_jar_path: str
    absolute_s3_algorithm_parameter_path: str


class SlotActiveInfoShardResponse(BaseModel):
    shard_id: int
    created_at: datetime


class SlotActiveInfoVariantResponse(BaseModel):
    variant_id: int
    algorithm: SlotActiveInfoAlgorithmResponse
    created_at: datetime
    is_default: bool | None
    is_control: bool | None
    shard_allocation_ratio: int | None


class SlotActiveInfoExperimentResponse(BaseModel):
    experiment_id: int
    experiment_name: str
    variants: list[SlotActiveInfoVariantResponse]
    shards: list[SlotActiveInfoShardResponse]
    ramp_up_percentage: int
    created_at: datetime


class SlotActiveInfoUserForcedAssignmentResponse(BaseModel):
    user_id: str
    variant_id: int


class SlotActiveInfoCampaignForcedAssignmentResponse(BaseModel):
    campaign_id: str
    variant_id: int


class SlotActiveInfo(BaseModel):
    slot_salt: str
    total_number_of_shards: int
    default_variant: SlotActiveInfoVariantResponse
    experiments: list[SlotActiveInfoExperimentResponse]
    user_forced_assignments: list[SlotActiveInfoUserForcedAssignmentResponse]
    campaign_forced_assignments: list[SlotActiveInfoCampaignForcedAssignmentResponse]


class UserForcedAssignment(BaseModel):
    user_forced_assignment_id: int
    user_id: str
    variant: Variant
    created_at: datetime


class CampaignForcedAssignment(BaseModel):
    campaign_forced_assignment_id: int
    campaign_id: str
    variant: Variant
    created_at: datetime


class VariantAlgorithmLog(BaseModel):
    variant_algorithm_log_id: int
    variant: Variant
    algorithm: Algorithm
    created_at: datetime


class ExperimentRampUpLog(BaseModel):
    experiment_ramp_up_log_id: int = Field(
        validation_alias=AliasChoices("experiment_ramp_up_log_id", "experiment_log_id")
    )
    experiment: Experiment | None = None
    experiment_id: int | None = None
    ramp_up_percentage: int
    terminated_at: datetime | None
    created_at: datetime
