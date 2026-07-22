import pathlib
from collections.abc import Iterable, Sequence
from typing import Any, Literal

import numpy as np

from hotvect.evaluation.conversion import repack_metrics
from hotvect.evaluation.data_models import EvaluationMetadata, MetricEstimate, MetricEvaluationResult
from hotvect.evaluation.metrics import (
    MetricDiversity,
    MetricMap,
    MetricMeanScore,
    MetricNdcg,
    MetricPenalisedNdcg,
    MetricPrAuc,
    MetricRocAuc,
)
from hotvect.prediction_reader import MissingRewardPolicy, PredictionReader, PredictionRow, ReadStats

_SEED = 6948


class EvaluatorBase:
    """Base class for computation of evaluation metrics from predictions."""

    def __init__(self, missing_reward_policy: MissingRewardPolicy) -> None:
        self._missing_reward_policy = missing_reward_policy

    def run(self, path: pathlib.Path) -> tuple[list[MetricEvaluationResult], EvaluationMetadata]:
        reader = PredictionReader(path, missing_reward_policy=self._missing_reward_policy)
        metrics = self._run(row for row in reader.iter_rows())
        meta = self._build_metadata(reader.get_stats())
        return metrics, meta

    def _build_metadata(self, read_stats: ReadStats) -> EvaluationMetadata:
        meta: EvaluationMetadata = {"evaluation_policy": {"missing_reward_policy": str(self._missing_reward_policy)}}
        total_results = read_stats.total_results
        if self._missing_reward_policy != MissingRewardPolicy.ERROR:
            missing_reward_count = read_stats.missing_reward_count
            meta["missing_reward"] = {
                "total_examples": read_stats.total_examples,
                "examples_with_missing_reward": read_stats.examples_with_missing_reward,
                "total_results": read_stats.total_results,
                "missing_reward_count": read_stats.missing_reward_count,
                "judged_result_count": total_results - missing_reward_count,
                "missing_reward_rate": 0.0 if total_results == 0 else missing_reward_count / total_results,
                "judged_result_rate": 0.0
                if total_results == 0
                else (total_results - missing_reward_count) / total_results,
            }
        if total_results == 0:
            meta["skipped"] = "This algorithm always returns empty results. No metrics can be calculated."
        return meta

    def _run(self, rows: Iterable[PredictionRow]) -> list[MetricEvaluationResult]:
        raise NotImplementedError


class StandardEvaluator(EvaluatorBase):
    """
    Computation of standard evaluation metrics.

    Includes NDCG, MAP, ROC AUC, PR AUC, diversity metrics, and mean score. Online metrics are also
    computed when corresponding data are available.
    """

    def __init__(
        self,
        ks: Sequence[int],
        diversity_ks: Sequence[int],
        missing_reward_policy: MissingRewardPolicy,
    ) -> None:
        """
        Initialise.

        Parameters:
        - ks: k values for NDCG and MAP calculations.
        - diversity_ks: k values for diversity calculations.
        - missing_reward_policy: Missing reward policy to be forwarded to the base class.
        """

        super().__init__(missing_reward_policy)
        self._ks = ks
        self._diversity_ks = diversity_ks

    def _calculate_metrics(
        self,
        reward_arrays: Sequence[np.ndarray],
        binary_reward_arrays: Sequence[np.ndarray],
        rank_arrays: Sequence[np.ndarray],
        score_arrays: Sequence[np.ndarray],
    ) -> dict[str, MetricEstimate]:
        """
        Calculate multiple evaluation metrics.

        Parameters:
        - reward_arrays: Reward arrays for each example.
        - binary_reward_arrays: Binary reward arrays for each example.
        - rank_arrays: Rankings for each example.
        - score_arrays: Score arrays for each example.

        Returns:
        - Dictionary with calculated metrics.
        """

        evaluate_dict = {}

        # Example-level metrics
        ndcg_calculator = MetricNdcg(k=self._ks)
        evaluate_dict.update(ndcg_calculator.compute(reward_arrays, rank_arrays))

        map_calculator = MetricMap(k=self._ks)
        evaluate_dict.update(map_calculator.compute(binary_reward_arrays, rank_arrays))

        # Action-level metrics
        all_rewards = np.concatenate(binary_reward_arrays)
        all_scores = np.concatenate(score_arrays)

        roc_auc_calculator = MetricRocAuc()
        evaluate_dict.update(roc_auc_calculator.compute(all_rewards, all_scores))

        pr_auc_calculator = MetricPrAuc(seed=_SEED)
        evaluate_dict.update(pr_auc_calculator.compute(all_rewards, all_scores))

        mean_score_calculator = MetricMeanScore()
        evaluate_dict.update(mean_score_calculator.compute(all_scores))

        return evaluate_dict

    def _calculate_all_metrics(
        self,
        reward_arrays: Sequence[np.ndarray],
        binary_reward_arrays: Sequence[np.ndarray],
        rank_arrays: Sequence[np.ndarray],
        score_arrays: Sequence[np.ndarray],
        categories: Sequence[np.ndarray],
        dimensions: dict[str, dict[Literal["ranks", "scores"], Sequence[np.ndarray]]],
    ) -> list[MetricEvaluationResult]:
        if len(rank_arrays) == 0 and len(reward_arrays) == 0 and len(score_arrays) == 0:
            return []

        metrics_dict = self._calculate_metrics(reward_arrays, binary_reward_arrays, rank_arrays, score_arrays)
        if categories:
            diversity_calculator = MetricDiversity(k=self._diversity_ks)
            metrics_dict.update(diversity_calculator.compute(rank_arrays, categories))

        metrics = [
            MetricEvaluationResult(name=name, is_online=False, estimate=estimate)
            for name, estimate in metrics_dict.items()
        ]

        if dimensions:
            # Only complete dimensions must be included
            for dim_name, dim_dict in dimensions.items():
                ranks = dim_dict["ranks"]
                scores = dim_dict["scores"]
                metrics_dict = self._calculate_metrics(reward_arrays, binary_reward_arrays, ranks, scores)
                for name, estimate in metrics_dict.items():
                    metrics.append(
                        MetricEvaluationResult(name=name, is_online=True, dimension=dim_name, estimate=estimate)
                    )
        return metrics

    def _run(self, rows: Iterable[PredictionRow]) -> list[MetricEvaluationResult]:
        rewards = []
        binary_rewards = []
        ranks = []
        scores = []
        categories = []
        dimensions = {}

        for prediction in rows:
            rewards.append(np.asarray(prediction.rewards))
            binary_rewards.append(np.asarray(prediction.binary_rewards))
            ranks.append(np.asarray(prediction.ranks))
            scores.append(np.asarray(prediction.scores))
            if prediction.categories is not None:
                categories.append(np.asarray(prediction.categories))
            for dimension_name in prediction.online_ranks:
                if dimension_name not in dimensions:
                    dimensions[dimension_name] = {
                        "ranks": [],
                        "scores": [],
                    }
                dimensions[dimension_name]["ranks"].append(np.asarray(prediction.online_ranks[dimension_name]))
                dimensions[dimension_name]["scores"].append(np.asarray(prediction.online_scores[dimension_name]))

        complete_online_dimensions = {
            name for name, dim_data in dimensions.items() if len(dim_data["ranks"]) == len(rewards)
        }
        dimensions = {name: data for name, data in dimensions.items() if name in complete_online_dimensions}

        return self._calculate_all_metrics(rewards, binary_rewards, ranks, scores, categories, dimensions)


class PenalisedRewardEvaluator(EvaluatorBase):
    """
    Computation of evaluation metrics with penalisation for negative rewards.

    Only a limited set of metrics is computed.
    """

    def __init__(self, ks: Sequence[int]) -> None:
        """
        Initialise.

        Parameters:
        - ks: k values for NDCG and MAP calculations.
        - missing_reward_policy: Missing reward policy to be forwarded to the base class.
        """

        super().__init__(MissingRewardPolicy.ERROR)
        self._ks = ks

    def _run(self, rows: Iterable[PredictionRow]) -> list[MetricEvaluationResult]:
        rewards = []
        ranks = []

        for prediction in rows:
            current_rewards = np.asarray(prediction.rewards)
            current_ranks = np.asarray(prediction.ranks)
            rewards.append(current_rewards)
            ranks.append(current_ranks)

        ndcg_calculator = MetricPenalisedNdcg(k=self._ks)
        ndcgs = ndcg_calculator.compute(rewards, ranks)
        metrics = [
            MetricEvaluationResult(name=name, is_online=False, estimate=estimate) for name, estimate in ndcgs.items()
        ]

        return metrics


def standard_evaluation(
    path: str | pathlib.Path,
    ks: Sequence[int] | None = None,
    diversity_ks: Sequence[int] | None = None,
    missing_reward_policy: MissingRewardPolicy | str = MissingRewardPolicy.ERROR,
) -> dict[str, Any]:
    """Wrapper around StandardEvaluator."""

    if isinstance(path, str):
        path = pathlib.Path(path)
    if not ks:
        ks = [10, 50, 100]
    if not diversity_ks:
        diversity_ks = [5, 10, 30]
    if not isinstance(missing_reward_policy, MissingRewardPolicy):
        missing_reward_policy = MissingRewardPolicy(missing_reward_policy)

    evaluator = StandardEvaluator(ks=ks, diversity_ks=diversity_ks, missing_reward_policy=missing_reward_policy)
    metrics, stats = evaluator.run(path)
    return repack_metrics(metrics) | stats


def real_numbers_reward_evaluation(path: str | pathlib.Path, ks: list[int] | None = None) -> dict[str, Any]:
    """Wrapper around PenalisedRewardEvaluator."""

    if isinstance(path, str):
        path = pathlib.Path(path)
    if not ks:
        ks = [10, 50, 100]

    evaluator = PenalisedRewardEvaluator(ks=ks)
    metrics, stats = evaluator.run(path)
    return repack_metrics(metrics) | stats
