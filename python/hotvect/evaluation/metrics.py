import math
from abc import ABC, abstractmethod
from collections.abc import Sequence
from typing import Generic, ParamSpec, TypeAlias

import numpy as np
from scipy import stats
from sklearn.metrics import average_precision_score
from sklearn.metrics._ranking import _ndcg_sample_scores as ndcg_sample_scores
from typing_extensions import override

from hotvect.evaluation.data_models import MetricEstimate
from hotvect.stats import delong_roc_variance

P = ParamSpec("P")
MetricLabel: TypeAlias = str


class MetricMeanBase(Generic[P], ABC):
    """
    Base class for metrics computed as the mean of per-example values.

    Multiple variants of the metric can be computed simultaneously. They are identified by arbitrary
    labels used as keys in the returned dictionaries in `compute_sample_metric` and `compute`.
    """

    @abstractmethod
    def compute_sample_metric(self, *args: P.args, **kwargs: P.kwargs) -> dict[MetricLabel, np.ndarray]:
        """
        Compute per-example values of the metric.

        The returned dictionary must contain a 1D array with the per-example values for each variant
        of the metric. The lengths and order of elements in the arrays must match the inputs.
        """
        pass

    def compute(self, *args: P.args, **kwargs: P.kwargs) -> dict[MetricLabel, MetricEstimate]:
        """Compute the overall mean value of each variant of the metric and its uncertainty."""
        sample_metrics = self.compute_sample_metric(*args, **kwargs)
        return {label: self._estimate_mean(sample) for label, sample in sample_metrics.items()}

    @staticmethod
    def _estimate_mean(sample: np.ndarray) -> MetricEstimate:
        """Compute sample mean with its 95% confidence interval."""
        mean = np.mean(sample).item()
        if len(sample) < 2:
            return MetricEstimate(value=mean)
        sem = stats.sem(sample)
        ci: tuple[np.float64, np.float64] = stats.t.interval(confidence=0.95, df=len(sample) - 1, loc=mean, scale=sem)
        return MetricEstimate(value=mean, ci95_lower=ci[0].item(), ci95_upper=ci[1].item())


class MetricNdcg(MetricMeanBase[[Sequence[np.ndarray] | np.ndarray, Sequence[np.ndarray] | np.ndarray]]):
    """Computation of NDCG at given top k values."""

    def __init__(self, k: int | Sequence[int]) -> None:
        super().__init__()
        self._ks = k if isinstance(k, Sequence) else [k]
        self._labels = [f"ndcg_at_{k}" for k in self._ks]

    @override
    def compute_sample_metric(
        self, reward_arrays: Sequence[np.ndarray] | np.ndarray, rank_arrays: Sequence[np.ndarray] | np.ndarray
    ) -> dict[MetricLabel, np.ndarray]:
        """
        Compute NDCG for different top k values.

        Parameters:
        - reward_arrays: Reward arrays for each example.
        - rank_arrays: Rankings for each example.

        Returns:
        - Per-example NDCG scores for each requested value of k.
        """

        # Pad arrays so that all examples have the same length
        num_examples = len(reward_arrays)
        if len(rank_arrays) != num_examples:
            raise ValueError("Mismatched shapes in rewards and ranks")
        max_sequence_len = max(map(len, reward_arrays))
        rewards = np.zeros((num_examples, max_sequence_len))
        ranks = 1e8 * np.ones((num_examples, max_sequence_len))

        for i, (reward, rank) in enumerate(zip(reward_arrays, rank_arrays)):
            n = len(reward)
            if len(rank) != n:
                raise ValueError("Mismatched shapes in rewards and ranks")
            rewards[i, :n] = reward
            ranks[i, :n] = rank

        return {label: ndcg_sample_scores(rewards, -ranks, k=k) for label, k in zip(self._labels, self._ks)}


class MetricMap(MetricMeanBase[[Sequence[np.ndarray] | np.ndarray, Sequence[np.ndarray] | np.ndarray]]):
    """Computation of Mean Average Precision (MAP) at given top k values."""

    def __init__(self, k: int | None | Sequence[int | None]) -> None:
        """
        Initialise from values of top k to compute MAP at.

        A value of None means to compute MAP for the entire sequence.
        """
        super().__init__()
        self._ks = k if isinstance(k, Sequence) else [k]
        self._labels = list(map(self._get_label, self._ks))

    @override
    def compute_sample_metric(
        self, binary_reward_arrays: Sequence[np.ndarray] | np.ndarray, rank_arrays: Sequence[np.ndarray] | np.ndarray
    ) -> dict[MetricLabel, np.ndarray]:
        """
        Compute MAP for different top k values.

        Parameters:
        - binary_reward_arrays: Binary reward arrays (1 for relevant, 0 for non-relevant).
        - rank_arrays: Rankings for each example.

        Returns:
        - Per-example MAP scores for each requested value of k.
        """

        if len(binary_reward_arrays) == 0:
            return {}

        average_precisions = [[] for _ in range(len(self._ks))]
        for rewards, ranks in zip(binary_reward_arrays, rank_arrays):
            ranking_indices = np.argsort(ranks)
            rewards = rewards[ranking_indices]
            # Calculate cumulative sum of relevant items (1s)
            relevant_items = np.cumsum(rewards)

            # Calculate precision at each position where there is a relevant item
            positions = np.arange(1, len(rewards) + 1)
            precisions = relevant_items / positions

            # Only consider precision at positions where we have 1s
            precisions_for_ones = precisions * rewards

            # Now calculate for each k
            for i, k in enumerate(self._ks):
                if k is None:
                    k = rewards.shape[0]
                total_ones = np.sum(rewards[:k])
                if total_ones == 0:
                    ap_score_at_k = 0.0
                else:
                    total_precision_for_ones = np.sum(precisions_for_ones[:k])
                    ap_score_at_k = total_precision_for_ones / total_ones
                average_precisions[i].append(ap_score_at_k)

        return {label: np.array(ap) for label, ap in zip(self._labels, average_precisions)}

    @staticmethod
    def _get_label(k: int | None) -> MetricLabel:
        return f"map_at_{k}" if k is not None else "map_at_all"


class MetricDiversity(MetricMeanBase[[Sequence[np.ndarray], Sequence[np.ndarray]]]):
    """
    Computation of micro-average diversity at given top k values.

    Per-example the diversity is defined as the fraction of the number of unique categories among
    all actions in that example.
    """

    def __init__(self, k: int | Sequence[int]) -> None:
        super().__init__()
        self._ks = k if isinstance(k, Sequence) else [k]
        self._labels = [f"diversity@{k}" for k in self._ks]

    @override
    def compute_sample_metric(
        self, rank_arrays: Sequence[np.ndarray], category_arrays: Sequence[np.ndarray]
    ) -> dict[MetricLabel, np.ndarray]:
        """
        Calculate diversity for different top k values.

        Parameters:
        - rank_arrays: Rankings for a sequence of examples.
        - category_arrays: Categories corresponding to each item in rank_arrays.

        Returns:
        - Per-example diversity for each requested value of k.
        """

        if len(rank_arrays) == 0:
            return {}

        frac_unique = [[] for _ in range(len(self._ks))]
        for ranks, categories in zip(rank_arrays, category_arrays):
            ranking_indices = np.argsort(ranks)
            sorted_categories = categories[ranking_indices]
            for i, k in enumerate(self._ks):
                current_num_unique = len(np.unique(sorted_categories[:k]))
                frac_unique[i].append(
                    current_num_unique / min(k, len(sorted_categories)) if len(sorted_categories) > 0 else 0.0
                )

        return {label: np.array(frac) for label, frac in zip(self._labels, frac_unique)}


class MetricPenalisedNdcg(MetricMeanBase[[Sequence[np.ndarray] | np.ndarray, Sequence[np.ndarray] | np.ndarray]]):
    """
    Computation of adapted NDCG with worst-ranking normalization.

    This variant of NDCG allows for negative rewards, which are used as penalties. The resulting
    score is bounded between 0 and 1.
    """

    def __init__(self, k: int | Sequence[int]) -> None:
        self._ks = k if isinstance(k, Sequence) else [k]
        self._labels = [f"ndcg_at_{k}" for k in self._ks]

    @override
    def compute_sample_metric(
        self, score_arrays: Sequence[np.ndarray] | np.ndarray, rank_arrays: Sequence[np.ndarray] | np.ndarray
    ) -> dict[MetricLabel, np.ndarray]:
        """
        Calculate normalized discounted cumulative gain with worst-case normalization.

        Parameters:
        - score_arrays: Sequence of score arrays containing positive and negative values.
        - rank_arrays: Sequence of corresponding rank arrays.

        Returns:
        - Per-example NDCG scores for each configured k.
        """

        ndcgs = [[] for _ in range(len(self._ks))]

        for scores, ranks in zip(score_arrays, rank_arrays):
            # Order scores according to ranks
            ordered_scores = np.asarray(scores)[np.argsort(ranks)]

            worst_scores = np.sort(ordered_scores)
            ideal_scores = worst_scores[::-1]

            for i, k in enumerate(self._ks):
                dcg_current = self._dcg_adapted(ordered_scores[:k])
                dcg_worst = self._dcg_adapted(worst_scores[:k])
                dcg_ideal = self._dcg_adapted(ideal_scores[:k])
                ndcg = (dcg_current - dcg_worst) / (dcg_ideal - dcg_worst) if dcg_ideal - dcg_worst != 0 else 0
                ndcgs[i].append(ndcg)

        return {label: np.asarray(a) for label, a in zip(self._labels, ndcgs)}

    @staticmethod
    def _dcg_adapted(reward: np.ndarray | list[float]) -> float:
        """
        Calculate adapted DCG score with penalty for negative rewards.

        Parameters:
        - reward: Reward values after ranking.

        Returns:
        - Adapted DCG score.
        """
        dcg_gain = sum(g / math.log2(i + 1) for i, g in enumerate(reward, start=1))
        dcg_penalty = sum(abs(p) / math.log2(j + 1) for j, p in enumerate(reward, start=1) if p < 0)
        return dcg_gain - dcg_penalty


class MetricRocAuc:
    """Computation of ROC AUC score."""

    def __init__(self) -> None:
        self._name = "roc_auc"

    def compute(self, binary_rewards: np.ndarray, scores: np.ndarray) -> dict[MetricLabel, MetricEstimate]:
        """
        Calculate ROC AUC score.

        Parameters:
        - binary_rewards: Binariesed rewards (1 for relevant, 0 for non-relevant) for all actions.
        - scores: Corresponding scores for all actions.

        Returns:
        - ROC AUC score.
        """

        roc_auc, variance = delong_roc_variance(binary_rewards, scores)
        roc_auc = float(roc_auc)
        if math.isnan(variance):
            return {self._name: MetricEstimate(value=roc_auc)}
        delta = 1.96 * math.sqrt(variance)
        return {self._name: MetricEstimate(value=roc_auc, ci95_lower=roc_auc - delta, ci95_upper=roc_auc + delta)}


class MetricPrAuc:
    """Computation of PR AUC score."""

    def __init__(self, num_bootstrap: int = 1000, seed: int | None = None) -> None:
        self._name = "pr_auc"
        self._num_bootstrap = num_bootstrap
        self._seed = seed
        self._num_bins = 10_000

    def compute(self, binary_rewards: np.ndarray, scores: np.ndarray) -> dict[MetricLabel, MetricEstimate]:
        """
        Calculate PR AUC score.

        Parameters:
        - binary_rewards: Binariesed rewards (1 for relevant, 0 for non-relevant) for all actions.
        - scores: Corresponding scores for all actions.

        Returns:
        - PR AUC score.
        """

        pr_auc = float(average_precision_score(binary_rewards, scores))
        if self._num_bootstrap <= 0:
            return {self._name: MetricEstimate(value=pr_auc)}

        # Compute uncertainty using bootstrap sampling. If the sample is not too large, do the standard bootstrap.
        # Otherwise bin the sample to reduce its size.
        if len(binary_rewards) < 10 * self._num_bins:
            bootstrap_sample = self._build_bootstrap_sample(binary_rewards, scores)
        else:
            bootstrap_sample = self._build_quantised_bootstrap_sample(binary_rewards, scores)
        delta = 1.96 * float(np.std(bootstrap_sample, ddof=1))

        return {self._name: MetricEstimate(value=pr_auc, ci95_lower=pr_auc - delta, ci95_upper=pr_auc + delta)}

    def _build_bootstrap_sample(self, binary_rewards: np.ndarray, scores: np.ndarray) -> np.ndarray:
        rng = np.random.default_rng(self._seed)
        indices = np.arange(len(binary_rewards))
        sample = []
        for _ in range(self._num_bootstrap):
            sampled_indices = rng.choice(indices, size=len(indices), replace=True)
            sampled_rewards = binary_rewards[sampled_indices]
            sampled_scores = scores[sampled_indices]
            score = average_precision_score(sampled_rewards, sampled_scores)
            sample.append(score)
        return np.array(sample)

    def _build_quantised_bootstrap_sample(self, binary_rewards: np.ndarray, scores: np.ndarray) -> np.ndarray:
        # Build histograms of positive and negative examples
        inner_edges = np.quantile(scores, np.linspace(0, 1, self._num_bins + 1)[1:-1])
        binning = np.r_[np.min(scores) - 1e-3, np.unique(inner_edges), np.max(scores) + 1e-3]
        counts_pos, _ = np.histogram(scores[binary_rewards > 0], bins=binning)
        counts_neg, _ = np.histogram(scores[binary_rewards == 0], bins=binning)

        # Replace the original sample with a sample of aggregated examples
        quantised_rewards = np.repeat([1, 0], [len(counts_pos), len(counts_neg)])
        quantised_scores = np.concatenate([binning[:-1], binning[:-1]])
        weights = np.concatenate([counts_pos, counts_neg])

        # Drop empty entries
        sel = weights > 0
        quantised_rewards = quantised_rewards[sel]
        quantised_scores = quantised_scores[sel]
        weights = weights[sel]

        # Generate the bootstrap sample
        total_samples = weights.sum()
        probabilities = weights / total_samples
        rng = np.random.default_rng(self._seed)
        sample = []
        for _ in range(self._num_bootstrap):
            # Draw new counts for each bin based on original proportions
            sampled_weights = rng.multinomial(total_samples, probabilities)

            score = average_precision_score(quantised_rewards, quantised_scores, sample_weight=sampled_weights)
            sample.append(score)

        return np.array(sample)


class MetricMeanScore(MetricMeanBase[np.ndarray]):
    """Computation of mean score."""

    def __init__(self) -> None:
        super().__init__()
        self._name = "mean_score"

    @override
    def compute_sample_metric(self, scores: np.ndarray) -> dict[MetricLabel, np.ndarray]:
        return {self._name: scores}
