import math
from unittest.mock import patch

import numpy as np
import pytest
from sklearn.metrics import average_precision_score, ndcg_score

from hotvect.evaluation.metrics import (
    MetricDiversity,
    MetricMap,
    MetricMeanBase,
    MetricNdcg,
    MetricPenalisedNdcg,
    MetricPrAuc,
)


class TestMetricMeanBase:
    def test_compute(self):
        class _ConcreteMetric(MetricMeanBase):
            def compute_sample_metric(self, *args, **kwargs):
                return {}

        metric_calculator = _ConcreteMetric()
        mocked_samples = {
            "metric_a": np.array([1.0, 2.0, 3.0]),
            "metric_b": np.array([4.0, 6.0, 8.0]),
        }
        with patch.object(metric_calculator, "compute_sample_metric", return_value=mocked_samples) as mock_fn:
            result = metric_calculator.compute("arg1", key="val")

        mock_fn.assert_called_once_with("arg1", key="val")
        assert result["metric_a"]["value"] == pytest.approx(2.0)
        assert result["metric_b"]["value"] == pytest.approx(6.0)

    def test_estimate_mean(self):
        sample = np.array([1.0, 2.0, 3.0, 4.0, 5.0])
        estimate = MetricMeanBase._estimate_mean(sample)
        assert estimate["value"] == pytest.approx(3.0)
        assert estimate.get("ci95_lower") == pytest.approx(1.036, abs=1e-3)
        assert estimate.get("ci95_upper") == pytest.approx(4.964, abs=1e-3)


class TestMetricNdcg:
    def test_compute(self):
        metric_calculator = MetricNdcg(k=5)

        rewards = [
            np.array([1.0, 0.0, 1.0]),  # Needs padding
            np.array([10.0, 0.0, 0.0, 1.0, 5.0]),
            np.array([0.0, 1.0, 0.0, 0.0, 1.0, 0.0, 1.0]),  # Needs truncating
        ]
        ranks = [np.array([1, 2, 3]), np.array([4, 3, 2, 1, 0]), np.array([7, 3, 6, 4, 1, 5, 2])]

        # The  NDCG must be the mean of the NDCG score for each ranking
        expected = ((1 + 1 / math.log2(4)) / (1 + 1 / math.log2(3)) + 0.695694 + 1.0) / 3

        result = metric_calculator.compute(rewards, ranks)

        assert len(result) == 1
        value = result["ndcg_at_5"]["value"]
        assert value == pytest.approx(expected, abs=1e-5)

    @pytest.mark.parametrize(
        ("rewards", "ranks", "k"),
        [
            pytest.param(
                [[0.0, 1.0, 5.0, 0.0]],
                [[1, 2, 3, 0]],
                5,
                id="short sequence",
            ),
            pytest.param(
                [[0.0, 1.0, 5.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]],
                [[1, 2, 3, 4, 0, 5, 6, 7, 8, 9]],
                5,
                id="long sequence good rank high",
            ),
            pytest.param(
                [[0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 10.0, 0.0]],
                [[1, 2, 3, 4, 0, 5, 6, 7, 8, 9]],
                5,
                id="long sequence good rank low",
            ),
            pytest.param(
                [[0.0, 1.0, 0.0], [1.0, 0.0, 0.0]],
                [[0, 1, 2], [0, 2, 1]],
                5,
                id="multiple sequences",
            ),
        ],
    )
    def test_compute_against_sklearn(self, rewards: np.ndarray | list[list], ranks: np.ndarray | list[list], k: int):
        metric_calculator = MetricNdcg(k=k)
        rewards = np.asarray(rewards)
        ranks = np.asarray(ranks)
        expected = ndcg_score(rewards, -ranks, k=k)

        result = metric_calculator.compute(rewards, ranks)

        assert len(result) == 1
        value = result[f"ndcg_at_{k}"]["value"]
        assert value == pytest.approx(expected, abs=1e-5)


class TestMetricMap:
    @pytest.mark.parametrize(
        ("binary_rewards", "ranks", "k", "expected_key", "expected_map"),
        [
            pytest.param(
                [np.array([1.0, 1.0, 1.0]), np.array([0.0, 1.0, 1.0])],
                [np.array([1, 2, 3]), np.array([1, 2, 3])],
                None,
                "map_at_all",
                (1.0 + 0.583333) / 2,  # Approximately 0.7916665
            ),
            pytest.param(
                [np.array([1.0, 1.0, 1.0]), np.array([0.0, 0.0, 0.0])],
                [np.array([1, 2, 3]), np.array([1, 2, 3])],
                None,
                "map_at_all",
                0.5,
            ),
            pytest.param(
                [np.array([1.0, 1.0, 1.0]), np.array([0.0, 1.0, 1.0, 1.0])],
                [np.array([1, 2, 3]), np.array([1, 2, 3, 4])],
                3,
                "map_at_3",
                (np.mean([1 / 2, 2 / 3]) + 1) / 2,  # Approximately 0.7916667
            ),
        ],
    )
    def test_compute(
        self,
        binary_rewards: list[np.ndarray],
        ranks: list[np.ndarray],
        k: int | None,
        expected_key: str,
        expected_map: float,
    ):
        metric_calculator = MetricMap(k=k)
        result = metric_calculator.compute(binary_rewards, ranks)

        assert len(result) == 1
        assert result[expected_key]["value"] == pytest.approx(expected_map, abs=1e-5)


class TestMetricDiversity:
    @pytest.mark.parametrize(
        ("rankings", "categories", "expected_diversity"),
        [
            pytest.param(
                # All the 11B's will be filtered out due to k=5, so diversity is 1/5
                np.array([8, 1, 7, 2, 3, 6, 4, 5]),
                np.array(["11B", "11A", "11B", "11A", "11A", "11B", "11A", "11A"]),
                1 / 5,
            ),
            pytest.param(
                np.array([6, 7, 1, 2, 3, 4, 5]),
                np.array(["12A", "12A", "12A", "13A", "14A", "15A", "11A"]),
                1.0,
            ),
        ],
    )
    def test_diversity(self, rankings: np.ndarray, categories: np.ndarray, expected_diversity: float):
        metric_calculator = MetricDiversity(k=[5])
        result = metric_calculator.compute([rankings], [categories])
        assert result["diversity@5"]["value"] == expected_diversity


class TestMetricPenalisedNdcg:
    @pytest.mark.parametrize(
        ("rewards", "expected_score"),
        [
            pytest.param([], 0.0, id="empty rewards"),
            pytest.param([3.0, 2.0, 1.0], 3.0 + 2.0 / math.log2(3) + 1.0 / math.log2(4), id="all positive rewards"),
            pytest.param(
                [3.0, -2.0, 1.0],
                3.0 - 2 * 2.0 / math.log2(3) + 1.0 / math.log2(4),
                id="with negative reward",
            ),
        ],
    )
    def test_dcg_adapted(self, rewards: list[float], expected_score: float):
        assert MetricPenalisedNdcg._dcg_adapted(rewards) == pytest.approx(expected_score)

    @pytest.mark.parametrize(
        ("scores", "ranks", "k", "expected_ndcg"),
        [
            pytest.param([3.0, 2.0, 1.0, -1.0], None, 4, 1.0, id="perfect ranking"),
            pytest.param([-1.0, 1.0, 2.0, 3.0], None, 4, 0.0, id="worst ranking"),
            pytest.param([0.5, -1.0, 2.0, 0.0], None, 2, 0.287, id="partial ranking"),
            pytest.param([0.0, 0.0, 0.0, 0.0], None, 2, 0.0, id="zero denominator"),
            pytest.param([2.0, 3.0, 1.0, -1.0], [1, 0, 2, 3], 4, 1.0, id="mixed ranking"),
        ],
    )
    def test_compute(self, scores: list[float], ranks: list[int] | None, k: int, expected_ndcg: float):
        if ranks is None:
            ranks = list(range(len(scores)))
        metric_calculator = MetricPenalisedNdcg(k=[k])
        result = metric_calculator.compute([np.asarray(scores)], [np.asarray(ranks)])
        assert len(result) == 1
        value = result[f"ndcg_at_{k}"]["value"]
        assert value == pytest.approx(expected_ndcg, abs=1e-3)


class TestMetricPrAuc:
    def test_compute(self):
        metric_calculator = MetricPrAuc(num_bootstrap=10, seed=42)

        binary_rewards = np.array([1, 0, 1, 0, 1, 0, 1, 0])
        scores = np.array([0.9, 0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2])

        with patch.object(
            metric_calculator,
            "_build_bootstrap_sample",
            wraps=metric_calculator._build_bootstrap_sample,
        ) as bootstrap_sample_mock:
            result = metric_calculator.compute(binary_rewards, scores)

        bootstrap_sample_mock.assert_called_once()

        assert len(result) == 1
        value = result["pr_auc"]["value"]
        assert value == pytest.approx(0.7095, abs=1e-4)

    def test_quantised_bootstrap(self):
        # Test that the quantised bootstrap gives a similar result to the standard one

        # Generate a test dataset
        rng = np.random.default_rng(42)
        num_pos = 1_000
        num_neg = 10_000
        binary_rewards = np.repeat([1, 0], [num_pos, num_neg])
        scores = np.concatenate([rng.beta(3, 2, size=num_pos), rng.beta(2, 3, size=num_neg)])
        pr_auc = average_precision_score(binary_rewards, scores)

        metric_calculator = MetricPrAuc(num_bootstrap=1000, seed=42)

        standard_sample = metric_calculator._build_bootstrap_sample(binary_rewards, scores)
        quantised_sample = metric_calculator._build_quantised_bootstrap_sample(binary_rewards, scores)

        assert np.mean(standard_sample) == pytest.approx(pr_auc, abs=1e-3)
        assert np.mean(quantised_sample) == pytest.approx(pr_auc, abs=1e-3)
        assert np.std(quantised_sample) == pytest.approx(np.std(standard_sample), abs=1e-3)
