import json
import math
import os
import tempfile

import numpy as np
import pandas as pd
import pytest
from sklearn.metrics import average_precision_score, ndcg_score, roc_auc_score

from hotvect import mlutils


def test_mean_average_precision():
    binary_reward1 = [np.array([1.0, 1.0, 1.0]), np.array([0.0, 1.0, 1.0])]
    rank1 = [np.array([1, 2, 3]), np.array([1, 2, 3])]

    binary_reward2 = [np.array([1.0, 1.0, 1.0]), np.array([0.0, 0.0, 0.0])]
    rank2 = [np.array([1, 2, 3]), np.array([1, 2, 3])]

    binary_reward3 = [np.array([1.0, 1.0, 1.0]), np.array([0.0, 1.0, 1.0, 1.0])]
    rank3 = [np.array([1, 2, 3]), np.array([1, 2, 3, 4])]

    expected1 = (1.0 + 0.583333) / 2  # Approximately 0.7916665
    result1 = mlutils.map_for_each_k(binary_reward1, rank1, [None])[0]
    assert result1 == pytest.approx(expected1, abs=1e-5)

    expected2 = 0.5
    result2 = mlutils.map_for_each_k(binary_reward2, rank2, [None])[0]
    assert result2 == pytest.approx(expected2, abs=1e-5)

    expected3 = (np.mean([1 / 2, 2 / 3]) + 1) / 2  # Approximately 0.7916667
    result3 = mlutils.map_for_each_k(binary_reward3, rank3, [3])[0]
    assert result3 == pytest.approx(expected3, abs=1e-5)


def test_ndcg():
    rewards = [
        np.array([1.0, 0.0, 1.0]),  # Needs padding
        np.array([10.0, 0.0, 0.0, 1.0, 5.0]),
        np.array([0.0, 1.0, 0.0, 0.0, 1.0, 0.0, 1.0]),  # Needs truncating
    ]
    ranks = [np.array([1, 2, 3]), np.array([4, 3, 2, 1, 0]), np.array([7, 3, 6, 4, 1, 5, 2])]

    # The  NDCG must be the mean of the NDCG score for each ranking
    expected = ((1 + 1 / math.log2(4)) / (1 + 1 / math.log2(3)) + 0.695694 + 1.0) / 3
    result = mlutils.ndcg_for_each_k(rewards, ranks, ks=[5])[0]
    assert result == pytest.approx(expected, abs=1e-5)


def test_diversity():
    categories1 = np.array(["11B", "11A", "11B", "11A", "11A", "11B", "11A", "11A"])
    rankings = np.array([8, 1, 7, 2, 3, 6, 4, 5])
    # Notice all the 11B's will be filtered out due to k=5, so diversity is 1/5
    result = mlutils.diversity([rankings], [categories1], k_list=[5])
    assert result[0] == 1 / 5

    categories2 = np.array(["12A", "12A", "12A", "13A", "14A", "15A", "11A"])
    rankings = np.array([6, 7, 1, 2, 3, 4, 5])
    result = mlutils.diversity([rankings], [categories2], k_list=[5])
    assert result[0] == 1.0


def test_standard_evaluation():
    input = [
        {
            "example_id": "example1",
            "result": [
                {
                    "reward": 1.0,
                    "rank": 3,
                    "score": 0.75,
                    "additional_properties": {
                        "action_category": "categoryA",
                        "online": {"dimension1": {"score": 0.78, "rank": 2}, "dimension2": {"score": 0.80, "rank": 1}},
                    },
                },
                {
                    "reward": 0.0,
                    "rank": 1,
                    "score": 0.85,
                    "additional_properties": {
                        "action_category": "categoryB",
                        "online": {"dimension1": {"score": 0.70, "rank": 3}, "dimension2": {"score": 0.72, "rank": 2}},
                    },
                },
                {
                    "reward": 0.0,
                    "rank": 4,
                    "score": 0.65,
                    "additional_properties": {
                        "action_category": "categoryC",
                        "online": {"dimension1": {"score": 0.65, "rank": 4}, "dimension2": {"score": 0.68, "rank": 4}},
                    },
                },
                {
                    "reward": 1.0,
                    "rank": 2,
                    "score": 0.80,
                    "additional_properties": {
                        "action_category": "categoryD",
                        "online": {"dimension1": {"score": 0.82, "rank": 1}, "dimension2": {"score": 0.78, "rank": 3}},
                    },
                },
            ],
        },
        {
            "example_id": "example2",
            "result": [
                {
                    "reward": 0.0,
                    "rank": 2,
                    "score": 0.82,
                    "additional_properties": {
                        "action_category": "categoryE",
                        "online": {"dimension1": {"score": 0.75, "rank": 2}, "dimension2": {"score": 0.70, "rank": 1}},
                    },
                },
                {
                    "reward": 1.0,
                    "rank": 3,
                    "score": 0.88,
                    "additional_properties": {
                        "action_category": "categoryF",
                        "online": {"dimension1": {"score": 0.90, "rank": 1}, "dimension2": {"score": 0.85, "rank": 3}},
                    },
                },
                {
                    "reward": 1.0,
                    "rank": 1,
                    "score": 0.78,
                    "additional_properties": {
                        "action_category": "categoryG",
                        "online": {"dimension1": {"score": 0.72, "rank": 3}, "dimension2": {"score": 0.68, "rank": 4}},
                    },
                },
                {
                    "reward": 0.0,
                    "rank": 4,
                    "score": 0.60,
                    "additional_properties": {
                        "action_category": "categoryH",
                        "online": {"dimension1": {"score": 0.65, "rank": 4}, "dimension2": {"score": 0.60, "rank": 2}},
                    },
                },
            ],
        },
    ]

    with tempfile.NamedTemporaryFile(mode="w+t", suffix=".jsonl", delete=False) as temp_file:
        with open(temp_file.name, "wt") as prediction_file:
            for item in input:
                prediction_file.write(json.dumps(item) + "\n")

        result = mlutils.standard_evaluation(temp_file.name, ks=[2, 3, 4], diversity_ks=[2, 3, 4])

    # We will re-use the sk-learn libraries in the test (no point testing those) but we've manually tested the MAP
    # These values directly match the test input above, just copied them here for convenience / readibility
    all_rewards = [1.0, 0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0]
    all_scores = [0.75, 0.85, 0.65, 0.8, 0.82, 0.88, 0.78, 0.60]
    all_scores1 = [0.78, 0.7, 0.65, 0.82, 0.75, 0.90, 0.72, 0.65]
    all_scores2 = [0.8, 0.72, 0.68, 0.78, 0.70, 0.85, 0.68, 0.60]

    rewards = [np.array([1.0, 0.0, 0.0, 1.0]), np.array([0.0, 1.0, 1.0, 0.0])]
    ranks = [np.array([3, 1, 4, 2]), np.array([2, 3, 1, 4])]
    ranks1 = [np.array([2, 3, 4, 1]), np.array([2, 1, 3, 4])]
    ranks2 = [np.array([1, 2, 4, 3]), np.array([1, 3, 4, 2])]
    r2 = [-np.arange(2) - 1] * 2
    r3 = [-np.arange(3) - 1] * 2

    expected = {
        "roc_auc": {"mean": roc_auc_score(all_rewards, all_scores)},
        "pr_auc": {"mean": average_precision_score(all_rewards, all_scores)},
        "mean_score": {"mean": np.mean(all_scores)},
        "map_at_2": 0.75,
        "map_at_3": 0.708333,
        "map_at_4": 0.708333,
        "ndcg_at_2": ndcg_score([[0.0, 1.0], [1.0, 0.0]], r2),
        "ndcg_at_3": ndcg_score([[0.0, 1.0, 1.0], [1.0, 0.0, 1.0]], r3),
        "ndcg_at_4": ndcg_score(rewards, [-r for r in ranks]),
        "diversity@2": 1.0,
        "diversity@3": 1.0,
        "diversity@4": 1.0,
        "online": {
            "dimension1": {
                "roc_auc": {"mean": roc_auc_score(all_rewards, all_scores1)},
                "pr_auc": {"mean": average_precision_score(all_rewards, all_scores1)},
                "mean_score": {"mean": np.mean(all_scores1)},
                "map_at_2": 1.0,
                "map_at_3": 0.916667,
                "map_at_4": 0.916667,
                "ndcg_at_2": ndcg_score([[1.0, 1.0], [1.0, 0.0]], r2),
                "ndcg_at_3": ndcg_score([[1.0, 1.0, 0.0], [1.0, 0.0, 1.0]], r3),
                "ndcg_at_4": ndcg_score(rewards, [-r for r in ranks1]),
            },
            "dimension2": {
                "roc_auc": {"mean": roc_auc_score(all_rewards, all_scores2)},
                "pr_auc": {"mean": average_precision_score(all_rewards, all_scores2)},
                "mean_score": {"mean": np.mean(all_scores2)},
                "map_at_2": 0.5,
                "map_at_3": 0.583333,
                "map_at_4": 0.625,
                "ndcg_at_2": ndcg_score([[1.0, 0.0], [0.0, 0.0]], r2),
                "ndcg_at_3": ndcg_score([[1.0, 0.0, 1.0], [0.0, 0.0, 1.0]], r3),
                "ndcg_at_4": ndcg_score(rewards, [-r for r in ranks2]),
            },
        },
    }

    # Convert dictionaries to DataFrames (flattened if necessary)
    expected_df = pd.json_normalize(expected, sep="_").sort_index(axis=1)
    actual_df = pd.json_normalize(result, sep="_").sort_index(axis=1)

    # Uncomment when modifying test - Find columns with at least one unequal value
    # unequal_columns = []
    # for col in expected_df.columns:
    #     if not np.allclose(expected_df[col], actual_df[col], atol=1e-5, equal_nan=True):
    #         unequal_columns.append(col)
    # print("Columns with at least one unequal value:", unequal_columns)

    assert expected_df.columns.equals(actual_df.columns)
    assert np.allclose(expected_df, actual_df, atol=1e-8, rtol=1e-5)

    os.unlink(temp_file.name)
