from unittest.mock import ANY, patch

import numpy as np
import pytest
from sklearn.metrics import average_precision_score, ndcg_score, roc_auc_score

from hotvect.evaluation.evaluation import standard_evaluation
from hotvect.prediction_reader import PredictionRow, ReadStats


def test_standard_evaluation():
    prediction_rows = [
        PredictionRow(
            example_id="example1",
            rewards=[1.0, 0.0, 0.0, 1.0],
            ranks=[3, 1, 4, 2],
            scores=[0.75, 0.85, 0.65, 0.80],
            online_ranks={"dimension1": [2, 3, 4, 1], "dimension2": [1, 2, 4, 3]},
            online_scores={"dimension1": [0.78, 0.70, 0.65, 0.82], "dimension2": [0.80, 0.72, 0.68, 0.78]},
            categories=["categoryA", "categoryB", "categoryC", "categoryD"],
        ),
        PredictionRow(
            example_id="example2",
            rewards=[0.0, 1.0, 1.0, 0.0],
            ranks=[2, 3, 1, 4],
            scores=[0.82, 0.88, 0.78, 0.60],
            online_ranks={"dimension1": [2, 1, 3, 4], "dimension2": [1, 3, 4, 2]},
            online_scores={"dimension1": [0.75, 0.90, 0.72, 0.65], "dimension2": [0.70, 0.85, 0.68, 0.60]},
            categories=["categoryE", "categoryF", "categoryG", "categoryH"],
        ),
    ]

    with patch("hotvect.evaluation.evaluation.PredictionReader") as prediction_reader_cls:
        prediction_reader = prediction_reader_cls.return_value
        prediction_reader.iter_rows.return_value = prediction_rows
        prediction_reader.get_complete_online_dimensions.return_value = {"dimension1", "dimension2"}

        result = standard_evaluation("unused.jsonl", ks=[2, 3, 4], diversity_ks=[2, 3, 4])

    # We will re-use the sk-learn libraries in the test (no point testing those) but we've manually tested the MAP
    # These values directly match the test input above, just copied them here for convenience / readibility
    all_rewards = [1.0, 0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0]
    all_scores = [0.75, 0.85, 0.65, 0.8, 0.82, 0.88, 0.78, 0.60]
    all_scores1 = [0.78, 0.7, 0.65, 0.82, 0.75, 0.90, 0.72, 0.65]
    all_scores2 = [0.8, 0.72, 0.68, 0.78, 0.70, 0.85, 0.68, 0.60]

    rewards = np.asarray([[1.0, 0.0, 0.0, 1.0], [0.0, 1.0, 1.0, 0.0]])
    ranks = np.asarray([[3, 1, 4, 2], [2, 3, 1, 4]])
    ranks1 = np.asarray([[2, 3, 4, 1], [2, 1, 3, 4]])
    ranks2 = np.asarray([[1, 2, 4, 3], [1, 3, 4, 2]])

    expected = {
        "roc_auc": {
            "value": pytest.approx(roc_auc_score(all_rewards, all_scores), abs=1e-6),
            "ci95_lower": ANY,
            "ci95_upper": ANY,
        },
        "pr_auc": {
            "value": pytest.approx(average_precision_score(all_rewards, all_scores), abs=1e-6),
            "ci95_lower": ANY,
            "ci95_upper": ANY,
        },
        "mean_score": {
            "value": pytest.approx(np.mean(all_scores), abs=1e-6),
            "ci95_lower": ANY,
            "ci95_upper": ANY,
        },
        "map_at_2": {"value": pytest.approx(0.75, abs=1e-6), "ci95_lower": ANY, "ci95_upper": ANY},
        "map_at_3": {"value": pytest.approx(0.708333, abs=1e-6), "ci95_lower": ANY, "ci95_upper": ANY},
        "map_at_4": {"value": pytest.approx(0.708333, abs=1e-6), "ci95_lower": ANY, "ci95_upper": ANY},
        "ndcg_at_2": {
            "value": pytest.approx(ndcg_score(rewards, -ranks, k=2), abs=1e-6),
            "ci95_lower": ANY,
            "ci95_upper": ANY,
        },
        "ndcg_at_3": {
            "value": pytest.approx(ndcg_score(rewards, -ranks, k=3), abs=1e-6),
            "ci95_lower": ANY,
            "ci95_upper": ANY,
        },
        "ndcg_at_4": {
            "value": pytest.approx(ndcg_score(rewards, -ranks, k=4), abs=1e-6),
            "ci95_lower": ANY,
            "ci95_upper": ANY,
        },
        "diversity@2": {"value": pytest.approx(1.0, abs=1e-6), "ci95_lower": ANY, "ci95_upper": ANY},
        "diversity@3": {"value": pytest.approx(1.0, abs=1e-6), "ci95_lower": ANY, "ci95_upper": ANY},
        "diversity@4": {"value": pytest.approx(1.0, abs=1e-6), "ci95_lower": ANY, "ci95_upper": ANY},
        "online": {
            "dimension1": {
                "roc_auc": {
                    "value": pytest.approx(roc_auc_score(all_rewards, all_scores1), abs=1e-6),
                    "ci95_lower": ANY,
                    "ci95_upper": ANY,
                },
                "pr_auc": {
                    "value": pytest.approx(average_precision_score(all_rewards, all_scores1), abs=1e-6),
                    "ci95_lower": ANY,
                    "ci95_upper": ANY,
                },
                "mean_score": {
                    "value": pytest.approx(np.mean(all_scores1), abs=1e-6),
                    "ci95_lower": ANY,
                    "ci95_upper": ANY,
                },
                "map_at_2": {"value": pytest.approx(1.0, abs=1e-6), "ci95_lower": ANY, "ci95_upper": ANY},
                "map_at_3": {"value": pytest.approx(0.916667, abs=1e-6), "ci95_lower": ANY, "ci95_upper": ANY},
                "map_at_4": {"value": pytest.approx(0.916667, abs=1e-6), "ci95_lower": ANY, "ci95_upper": ANY},
                "ndcg_at_2": {
                    "value": pytest.approx(ndcg_score(rewards, -ranks1, k=2), abs=1e-6),
                    "ci95_lower": ANY,
                    "ci95_upper": ANY,
                },
                "ndcg_at_3": {
                    "value": pytest.approx(ndcg_score(rewards, -ranks1, k=3), abs=1e-6),
                    "ci95_lower": ANY,
                    "ci95_upper": ANY,
                },
                "ndcg_at_4": {
                    "value": pytest.approx(ndcg_score(rewards, -ranks1, k=4), abs=1e-6),
                    "ci95_lower": ANY,
                    "ci95_upper": ANY,
                },
            },
            "dimension2": {
                "roc_auc": {
                    "value": pytest.approx(roc_auc_score(all_rewards, all_scores2), abs=1e-6),
                    "ci95_lower": ANY,
                    "ci95_upper": ANY,
                },
                "pr_auc": {
                    "value": pytest.approx(average_precision_score(all_rewards, all_scores2), abs=1e-6),
                    "ci95_lower": ANY,
                    "ci95_upper": ANY,
                },
                "mean_score": {
                    "value": pytest.approx(np.mean(all_scores2), abs=1e-6),
                    "ci95_lower": ANY,
                    "ci95_upper": ANY,
                },
                "map_at_2": {"value": pytest.approx(0.5, abs=1e-6), "ci95_lower": ANY, "ci95_upper": ANY},
                "map_at_3": {"value": pytest.approx(0.583333, abs=1e-6), "ci95_lower": ANY, "ci95_upper": ANY},
                "map_at_4": {"value": pytest.approx(0.625, abs=1e-6), "ci95_lower": ANY, "ci95_upper": ANY},
                "ndcg_at_2": {
                    "value": pytest.approx(ndcg_score(rewards, -ranks2, k=2), abs=1e-6),
                    "ci95_lower": ANY,
                    "ci95_upper": ANY,
                },
                "ndcg_at_3": {
                    "value": pytest.approx(ndcg_score(rewards, -ranks2, k=3), abs=1e-6),
                    "ci95_lower": ANY,
                    "ci95_upper": ANY,
                },
                "ndcg_at_4": {
                    "value": pytest.approx(ndcg_score(rewards, -ranks2, k=4), abs=1e-6),
                    "ci95_lower": ANY,
                    "ci95_upper": ANY,
                },
            },
        },
        "evaluation_policy": {"missing_reward_policy": "error"},
    }

    assert set(result) == set(expected)
    assert result == expected
    # If this test fails, keep in mind that the ANY values will be reported in the visual diff. They are not the reason
    # for the failure, instead there might be some other meaninful difference.


def test_standard_evaluation_can_treat_missing_reward_as_zero():
    prediction_rows = [
        PredictionRow(
            example_id="example1",
            rewards=[1.0, 0.0, 0.0],  # One of these zeros has been filled
            ranks=[1, 2, 3],
            scores=[0.9, 0.2, 0.1],
            online_ranks={},
            online_scores={},
            categories=None,
        )
    ]

    with patch("hotvect.evaluation.evaluation.PredictionReader") as prediction_reader_cls:
        prediction_reader = prediction_reader_cls.return_value
        prediction_reader.iter_rows.return_value = prediction_rows
        prediction_reader.get_complete_online_dimensions.return_value = set()
        prediction_reader.get_stats.return_value = ReadStats(
            total_examples=1,
            examples_with_missing_reward=1,
            total_results=3,
            missing_reward_count=1,
        )

        result = standard_evaluation("unused.jsonl", ks=[1, 2, 3], missing_reward_policy="zero")

    assert result["evaluation_policy"] == {"missing_reward_policy": "zero"}
    assert result["missing_reward"] == {
        "total_examples": 1,
        "examples_with_missing_reward": 1,
        "total_results": 3,
        "missing_reward_count": 1,
        "judged_result_count": 2,
        "missing_reward_rate": pytest.approx(1 / 3),
        "judged_result_rate": pytest.approx(2 / 3),
    }
    assert result["roc_auc"] == {"value": pytest.approx(1.0)}


def test_standard_evaluation_rejects_unknown_missing_reward_policy():
    with pytest.raises(ValueError, match="'optimistic' is not a valid MissingRewardPolicy"):
        standard_evaluation("unused.jsonl", missing_reward_policy="optimistic")


def test_standard_evaluation_skips_online_dimensions_missing_from_any_scored_row():
    prediction_rows = [
        PredictionRow(
            example_id="example1",
            rewards=[1.0, 0.0],
            ranks=[1, 2],
            scores=[0.9, 0.1],
            online_ranks={"complete": [1, 2], "sometimes": [1, 2]},
            online_scores={"complete": [0.9, 0.1], "sometimes": [0.85, 0.15]},
            categories=None,
        ),
        PredictionRow(
            example_id="example2",
            rewards=[0.0, 1.0],
            ranks=[1, 2],
            scores=[0.8, 0.2],
            online_ranks={"complete": [2, 1]},
            online_scores={"complete": [0.7, 0.9]},
            categories=None,
        ),
    ]

    with patch("hotvect.evaluation.evaluation.PredictionReader") as prediction_reader_cls:
        prediction_reader = prediction_reader_cls.return_value
        prediction_reader.iter_rows.return_value = prediction_rows
        prediction_reader.get_complete_online_dimensions.return_value = {"complete"}

        result = standard_evaluation("unused.jsonl", ks=[2], diversity_ks=[2])

    assert "online" in result
    assert "complete" in result["online"]
    assert "sometimes" not in result["online"]
