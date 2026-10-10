"""EMS operator inputs, separate from the pinned generated library contract."""

from __future__ import annotations

from typing import Literal

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field

from hotvect.experiment_management.models import AlgorithmCreate, ExperimentCreate, ExperimentVariantInput


class AlgorithmCreateInput(AlgorithmCreate):
    algorithm_name: str = Field(min_length=1, max_length=255)
    algorithm_version: str = Field(min_length=1, max_length=255)
    algorithm_training_image_name: str = Field(default="", max_length=255)
    parameter_mode: Literal["REQUIRED", "NONE"] = "REQUIRED"


class ExperimentVariantCreateInput(ExperimentVariantInput):
    shard_allocation_ratio: int = Field(default=1, gt=0)


class ExperimentCreateInput(ExperimentCreate):
    variants: list[ExperimentVariantCreateInput] = Field(min_length=1)
    number_of_shards: int = Field(ge=0, le=100)
    ramp_up_percentage: int = Field(ge=0, le=100)


class AlgorithmReference(BaseModel):
    model_config = ConfigDict(extra="forbid")

    algorithm_name: str = Field(min_length=1)
    algorithm_version: str = Field(min_length=1)


class AlgorithmParameterCreateInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    algorithm_parameter_id: str
    algorithm: AlgorithmReference
    absolute_s3_path: str = Field(min_length=1)
    evaluation_results: str = "{}"
    created_at: AwareDatetime | None = None
