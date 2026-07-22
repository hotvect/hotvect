from typing import NotRequired, TypedDict


class MetricEstimate(TypedDict):
    """Metric with uncertainty band."""

    value: float
    ci95_lower: NotRequired[float]
    ci95_upper: NotRequired[float]


class MetricEvaluationResult(TypedDict):
    name: str
    """Name of the metric, e.g. ndcg@10."""

    is_online: bool
    """Whether the metric is computed from online or offline predictions."""

    dimension: NotRequired[str]
    """Name of the online dimension. Only present if is_online is True."""

    estimate: MetricEstimate


class EvaluationPolicy(TypedDict):
    missing_reward_policy: str


class MissingRewardMetadata(TypedDict):
    """Metadata about missing rewards in the evaluation."""

    total_examples: int
    examples_with_missing_reward: int
    total_results: int
    missing_reward_count: int
    judged_result_count: int
    missing_reward_rate: float
    judged_result_rate: float


class EvaluationMetadata(TypedDict):
    evaluation_policy: EvaluationPolicy
    missing_reward: NotRequired[MissingRewardMetadata]
    skipped: NotRequired[str]
