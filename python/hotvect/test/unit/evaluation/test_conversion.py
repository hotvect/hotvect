import pytest

from hotvect.evaluation.conversion import _get_nested_value, extract_evaluation, repack_metrics
from hotvect.evaluation.data_models import MetricEvaluationResult


def test_extract_evaluation():
    scalar_result = extract_evaluation(
        {
            "algorithm_id": "algo@1.0.0",
            "test_data_time": "2026-01-01",
            "evaluate": {
                "roc_auc": {"value": 0.81, "ci95_lower": 0.80, "ci95_upper": 0.82},
                "pr_auc": {"value": 0.23, "ci95_lower": 0.22, "ci95_upper": 0.24},
                "mean_score": {"value": -0.4},
                "online": {
                    "algorithm": {
                        "roc_auc": {"value": 0.77, "ci95_lower": 0.76, "ci95_upper": 0.78},
                        "pr_auc": {"value": 0.19},
                        "mean_score": {"value": -0.2},
                    }
                },
            },
        }
    )

    assert scalar_result is not None
    assert scalar_result["roc_auc"] == {"value": 0.81, "ci95_lower": 0.80, "ci95_upper": 0.82}
    assert scalar_result["pr_auc"] == {"value": 0.23, "ci95_lower": 0.22, "ci95_upper": 0.24}
    assert scalar_result["mean_score"] == {"value": -0.4}
    assert scalar_result["algorithm.roc_auc"] == {"value": 0.77, "ci95_lower": 0.76, "ci95_upper": 0.78}
    assert scalar_result["algorithm.pr_auc"] == {"value": 0.19}
    assert scalar_result["algorithm.mean_score"] == {"value": -0.2}


def test_extract_evaluation_legacy_scalars():
    result = extract_evaluation(
        {
            "algorithm_id": "algo@1.0.0",
            "test_data_time": "2026-01-01",
            "evaluate": {
                "map_at_100": 0.0123,
                "ndcg_at_100": 0.0456,
                "online": {
                    "algorithm": {
                        "map_at_100": 0.0111,
                        "ndcg_at_100": 0.0444,
                    }
                },
            },
        }
    )

    assert result is not None
    assert result["map_at_100"] == {"value": 0.0123}
    assert result["ndcg_at_100"] == {"value": 0.0456}
    assert result["algorithm.map_at_100"] == {"value": 0.0111}
    assert result["algorithm.ndcg_at_100"] == {"value": 0.0444}


def test_extract_evaluation_rejects_contradictory_algorithm_definition_identity():
    with pytest.raises(
        ValueError,
        match=("result.json claims algorithm_id 'algo@1.0.1' but " "algorithm_definition implies 'algo@1.0.0'"),
    ):
        extract_evaluation(
            {
                "algorithm_id": "algo@1.0.1",
                "test_data_time": "2026-01-01",
                "algorithm_definition": {
                    "algorithm_name": "algo",
                    "algorithm_version": "1.0.0",
                },
                "evaluate": {
                    "roc_auc": {"value": 0.81},
                },
            }
        )


def test_repack_metrics():
    metrics = [
        MetricEvaluationResult(
            name="roc_auc",
            is_online=False,
            estimate={"value": 0.81, "ci95_lower": 0.80, "ci95_upper": 0.82},
        ),
        MetricEvaluationResult(
            name="pr_auc",
            is_online=False,
            estimate={"value": 0.23, "ci95_lower": 0.22, "ci95_upper": 0.24},
        ),
        MetricEvaluationResult(
            name="roc_auc",
            is_online=True,
            dimension="algorithm",
            estimate={"value": 0.77, "ci95_lower": 0.76, "ci95_upper": 0.78},
        ),
        # The confidence interval does not have to exist. Use this metrics as an example.
        MetricEvaluationResult(
            name="pr_auc",
            is_online=True,
            dimension="algorithm",
            estimate={"value": 0.19},
        ),
    ]

    repacked = repack_metrics(metrics)

    assert repacked["roc_auc"] == {"value": 0.81, "ci95_lower": 0.80, "ci95_upper": 0.82}
    assert repacked["pr_auc"] == {"value": 0.23, "ci95_lower": 0.22, "ci95_upper": 0.24}
    assert repacked["online"]["algorithm"]["roc_auc"] == {"value": 0.77, "ci95_lower": 0.76, "ci95_upper": 0.78}
    assert repacked["online"]["algorithm"]["pr_auc"] == {"value": 0.19}


@pytest.mark.parametrize(
    ("data, keys, expected_value"),
    [
        pytest.param({"a": 42}, "a", 42, id="simple_key"),
        pytest.param({"a": {"b": {"c": 42}}}, "a.b.c", 42, id="existing_key"),
        pytest.param({"a": {"b": {"c": 42}}}, "a.b.x", "not found", id="non_existing_key"),
        pytest.param({"a": {"b": {"c": 42}}}, "a.x.c", "not found", id="partial_existing_keys"),
        pytest.param({"a": {"b": {"c": 42}}}, "", "not found", id="empty_keys"),
        pytest.param({"a": {"b": 100}}, "a.b.c", "not found", id="non_dict_intermediate"),
    ],
)
def test_get_nested_value(data: dict, keys: str, expected_value: int | str):
    assert _get_nested_value(data, keys, default="not found") == expected_value
