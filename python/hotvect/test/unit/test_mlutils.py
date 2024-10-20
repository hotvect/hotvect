import json
import os
import tempfile
from collections import defaultdict

import numpy as np
import pandas as pd
import pytest

from hotvect import mlutils


def test_average_precision():
    assert mlutils.average_precision([0, 1, 1]) == (1 / 2 + 2 / 3) / 2
    assert mlutils.average_precision([0, 0, 0]) == 0.0
    assert mlutils.average_precision([1, 1, 1]) == 1


def test_mean_average_precision():
    test_df1 = pd.DataFrame(
        {
            "example_id": ["foo1", "foo1", "foo1", "foo2", "foo2", "foo2"],
            "binary_reward": [1, 1, 1, 0, 1, 1],
            "rank": [1, 2, 3, 1, 2, 3],
        }
    )
    test_df2 = pd.DataFrame(
        {
            "example_id": ["foo1", "foo1", "foo1", "foo2", "foo2", "foo2"],
            "binary_reward": [1, 1, 1, 0, 0, 0],
            "rank": [1, 2, 3, 1, 2, 3],
        }
    )
    test_df3 = pd.DataFrame(
        {
            "example_id": ["foo1", "foo1", "foo1", "foo2", "foo2", "foo2", "foo2"],
            "binary_reward": [1, 1, 1, 0, 1, 1, 1],
            "rank": [1, 2, 3, 1, 2, 3, 4],
        }
    )

    # calculate_mean_average_precision(test_df1)
    assert pytest.approx(mlutils.calculate_mean_average_precision(test_df1), 0.5) == pytest.approx(
        (1 + 0.583333) / 2, 0.5
    )
    assert mlutils.calculate_mean_average_precision(test_df2) == 0.5
    assert mlutils.calculate_mean_average_precision(test_df3, 3) == (np.mean([1 / 2, 2 / 3]) + 1) / 2


def test_ndcg():
    test_df1 = pd.DataFrame(
        {
            "example_id": ["foo1", "foo2"],
            "reward": [1, 1],
            "rank": [1, 0],
        }
    )
    assert np.isnan(mlutils.calculate_ndcg(test_df1, k=None))
    test_df2 = pd.DataFrame(
        {
            "example_id": ["foo1", "foo1", "foo1", "foo1", "foo1"],
            "reward": [10, 0, 0, 1, 5],
            "rank": [4, 3, 2, 1, 0],
        }
    )
    assert pytest.approx(mlutils.calculate_ndcg(test_df2, k=None), 0.05) == 0.695


def test_diversity():
    test_df1 = pd.DataFrame(
        {
            "example_id": ["foo1", "foo1", "foo1", "foo1", "foo1"],
            "reward": [1, 1, 0, 0, 0],
            "rank": [0, 1, 2, 3, 4],
            "additional_properties.action_category": ["11A"] * 5,
        }
    )

    result = mlutils.calculate_diversity(test_df1, k_list=[5])
    assert result["diversity@5"] == 1 / 5

    test_df2 = pd.DataFrame(
        {
            "example_id": ["foo1", "foo1", "foo1", "foo1", "foo1"],
            "reward": [1, 1, 0, 0, 0],
            "rank": [0, 1, 2, 3, 4],
            "additional_properties.action_category": ["12A", "13A", "14A", "15A", "11A"],
        }
    )

    result = mlutils.calculate_diversity(test_df2, k_list=[5])
    assert result["diversity@5"] == 1


def test_standard_evaluation():
    def generate_permutation():
        return np.random.permutation(50)

    rank_permutations = defaultdict(generate_permutation)
    sample_data = [
        {
            "example_id": f"test_example_{i:03d}",
            "additional_properties": {
                "algorithm_parameter_id": "TEST_ALGO_PARAM_001",
                "variant_id": "TEST_VARIANT_001",
            },
            "result": [
                {
                    "rank": int(rank_permutations["rank"][j]),
                    "score": float(np.random.uniform(-1, 1)),
                    "reward": j,
                    "additional_properties": {
                        "action_id": f"ACTION_{j:03d}",
                        "original_rank": int(rank_permutations["original_rank"][j]),
                        "online": {
                            "algorithm": {
                                "rank": int(rank_permutations["algorithm_rank"][j]),
                                "score": float(np.random.uniform(-1, 1)),
                            },
                            "final": {
                                "rank": int(rank_permutations["final_rank"][j]),
                                "score": float(np.random.uniform(-1, 1)),
                            },
                        },
                    },
                }
                for j in range(50)
            ],
        }
        for i in range(3)
    ]

    with tempfile.NamedTemporaryFile(mode="w+t", suffix=".jsonl", delete=False) as temp_file:
        with open(temp_file.name, "wt") as prediction_file:
            for item in sample_data:
                prediction_file.write(json.dumps(item) + "\n")

        result = mlutils.standard_evaluation(temp_file.name)

    df = pd.DataFrame(
        [
            {
                "example_id": item["example_id"],
                "rank": result_item["rank"],
                "score": result_item["score"],
                "reward": result_item["reward"],
                "algorithm_rank": result_item["additional_properties"]["online"]["algorithm"]["rank"],
                "algorithm_score": result_item["additional_properties"]["online"]["algorithm"]["score"],
                "final_rank": result_item["additional_properties"]["online"]["final"]["rank"],
                "final_score": result_item["additional_properties"]["online"]["final"]["score"],
            }
            for item in sample_data
            for result_item in item["result"]
        ]
    )

    expected_metrics = {}
    for dimension in ["", "algorithm", "final"]:
        df_dim = df.copy()
        if dimension:
            score_column = f"{dimension}_score"
            rank_column = f"{dimension}_rank"
        else:
            score_column = "score"
            rank_column = "rank"
        has_score = score_column in df.columns
        has_rank = rank_column in df.columns
        dimension_metrics = {}
        if has_score:
            df_dim["score"] = df_dim[score_column]
            df_dim["binary_reward"] = (df_dim["reward"] > 0).astype(float)
            dimension_metrics["roc_auc"] = mlutils.roc_auc_score(df_dim["binary_reward"], df_dim["score"])
            dimension_metrics["pr_auc"] = mlutils.pr_auc_score(df_dim["binary_reward"], df_dim["score"])
            dimension_metrics["mean_score"] = np.mean(df_dim["score"])

        if has_rank:
            df_dim["rank"] = df_dim[rank_column]
            df_dim["binary_reward"] = (df_dim["reward"] > 0).astype(float)
            dimension_metrics["map_at_10"] = mlutils.calculate_mean_average_precision(df_dim, 10)
            dimension_metrics["map_at_50"] = mlutils.calculate_mean_average_precision(df_dim, 50)
            dimension_metrics["map_at_all"] = mlutils.calculate_mean_average_precision(df_dim)
            dimension_metrics["ndcg_at_10"] = mlutils.calculate_ndcg(df_dim, 10)
            dimension_metrics["ndcg_at_50"] = mlutils.calculate_ndcg(df_dim, 50)
            dimension_metrics["ndcg_at_all"] = mlutils.calculate_ndcg(df_dim)

        if dimension_metrics:
            expected_metrics[dimension] = dimension_metrics

    def compare_metrics(expected, evaluated, prefix=""):
        for key, value in expected.items():
            if isinstance(value, dict):
                if key in evaluated:
                    compare_metrics(value, evaluated[key], f"{prefix}{key}.")
                else:
                    pytest.fail(f"Missing key in evaluated metrics: {prefix}{key}")
            elif value is not None:
                if key not in evaluated:
                    pytest.fail(f"Missing key in evaluated metrics: {prefix}{key}")
                if key in ["roc_auc", "pr_auc", "mean_score"]:
                    if "mean" in evaluated[key]:
                        evaluated_value = evaluated[key]["mean"]
                    else:
                        pytest.fail(f"Missing 'mean' in evaluated metric: {prefix}{key}")
                else:
                    evaluated_value = evaluated[key]

                assert pytest.approx(value, rel=1e-5, abs=1e-8) == evaluated_value, f"Mismatch in {prefix}{key}"

    compare_metrics(expected_metrics[""], result)
    for dimension in ["algorithm", "final"]:
        compare_metrics(expected_metrics[dimension], result["online"][dimension], f"{dimension}.")

    os.unlink(temp_file.name)


def test_mean_score():
    np.random.seed(42)
    test_scores = np.random.uniform(0, 1, 10000)
    test_rewards = np.random.randint(0, 2, 10000)
    df = pd.DataFrame(
        {
            "score": test_scores,
            "reward": test_rewards,
        }
    )
    expected_mean_score = np.mean(test_scores)
    results = mlutils._calculate_score_metric(df)
    calculated_mean_score = results["mean_score"]["mean"]
    assert pytest.approx(calculated_mean_score, rel=1e-5) == expected_mean_score

    assert "lower_bound" in results["mean_score"]
    assert "upper_bound" in results["mean_score"]

    lower_bound = results["mean_score"]["lower_bound"]
    upper_bound = results["mean_score"]["upper_bound"]

    assert lower_bound <= calculated_mean_score <= upper_bound
