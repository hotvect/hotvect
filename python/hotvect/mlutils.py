import gzip
import json
import logging
import re
from datetime import datetime
from typing import Any, Dict, Optional, Union

import numpy as np
import pandas as pd
from sklearn.metrics import average_precision_score, ndcg_score, roc_auc_score

logger = logging.getLogger(__name__)


def pr_auc_score(y_true, y_pred):
    return average_precision_score(y_true, y_pred)


def bootstrap_classification_metrics(y_true, y_pred, dict_metric_functions, n_bootstraps=5, rng_seed=42):
    return_dict = {}

    # Check if we have enough samples for bootstrapping
    if len(y_true) < 10000:
        # If not enough samples, calculate only the mean for each metric
        for metric_name, metric_function in dict_metric_functions.items():
            score = metric_function(y_true, y_pred)
            return_dict[metric_name] = {"mean": score}
        return return_dict

    rng = np.random.RandomState(rng_seed)
    dict_metrics_bootstrap = {metric_name: [] for metric_name in dict_metric_functions.keys()}

    for i in range(n_bootstraps):
        # Bootstrap by sampling with replacement on the prediction indices
        indices = rng.randint(0, len(y_pred), len(y_pred))

        # For metrics that need both classes, check if both are present
        classes_in_sample = np.unique(y_true.iloc[indices])
        for metric_name, metric_function in dict_metric_functions.items():
            if metric_name in ["roc_auc", "pr_auc"] and len(classes_in_sample) < 2:
                # Skip metrics that require both classes
                continue
            score = metric_function(y_true.iloc[indices], y_pred.iloc[indices])
            dict_metrics_bootstrap[metric_name].append(score)

    # For each metric calculate the confidence interval and store the result in the dict
    for metric in dict_metrics_bootstrap.keys():
        sorted_scores = np.array(dict_metrics_bootstrap[metric])
        sorted_scores.sort()
        # Computing the lower and upper bound of the 90% confidence interval
        # You can change the bounds percentiles to 0.025 and 0.975 to get
        # a 95% confidence interval instead.
        return_dict[metric] = {
            "lower_bound": sorted_scores[int(0.05 * len(sorted_scores))],
            "mean": dict_metric_functions[metric](y_true, y_pred),
            "upper_bound": sorted_scores[int(0.95 * len(sorted_scores))],
        }

    return return_dict


def standard_evaluation(simulation_output_path: str) -> Dict[str, Any]:
    df = read_predicted_data_from_json(simulation_output_path)
    logger.debug(f"Loaded data to evaluate from: {simulation_output_path}")

    # Evaluate the offline results (plain rank and score) and store at root level
    result = {}
    result.update(_calculate_score_metric(df))
    result.update(calculate_ranking_metric(df))
    # Only calculate diversity if the 'additional_properties.action_category' column exists
    if "additional_properties.action_category" in df.columns:
        result.update(calculate_diversity(df))

    # Check for additional dimensions in columns starting with 'additional_properties.online'
    online_columns = [col for col in df.columns if col.startswith("additional_properties.online.")]
    if online_columns:
        result["online"] = {}
        dimensions = set()

        # Use regex to extract dimension names
        pattern = r"additional_properties\.online\.(\w+)\.(rank|score)$"
        for column in online_columns:
            match = re.match(pattern, column)
            if match:
                dimensions.add(match.group(1))

        for dimension in dimensions:
            dimension_df = df.copy()
            dimension_result = {}

            score_column = f"additional_properties.online.{dimension}.score"
            rank_column = f"additional_properties.online.{dimension}.rank"

            if score_column in df.columns:
                if df[score_column].isna().any():
                    logger.warning(f"Skipping score evaluation for dimension '{dimension}' due to NaN values.")
                else:
                    # If no NaNs in score, proceed with score evaluation
                    dimension_df["score"] = df[score_column]
                    dimension_result.update(_calculate_score_metric(dimension_df))

            if rank_column in df.columns:
                if df[rank_column].isna().any():
                    logger.warning(f"Skipping rank evaluation for dimension '{dimension}' due to NaN values.")
                else:
                    # If no NaNs in rank, proceed with rank evaluation
                    dimension_df["rank"] = df[rank_column]
                    dimension_result.update(calculate_ranking_metric(dimension_df))
                dimension_result.update(calculate_ranking_metric(dimension_df))

            if dimension_result:
                logger.info(f"Evaluated online {dimension} results for: {simulation_output_path}")
                result["online"][dimension] = dimension_result

    return result


def mean_score(y_true, y_pred):
    return np.mean(y_pred)


def _calculate_score_metric(df: pd.DataFrame) -> Dict[str, Any]:
    # Add binary reward
    df.loc[df["reward"] <= 0, "binary_reward"] = 0
    df.loc[df["reward"] > 0, "binary_reward"] = 1

    return bootstrap_classification_metrics(
        y_true=df["binary_reward"],
        y_pred=df["score"],
        dict_metric_functions={
            "roc_auc": roc_auc_score,
            "pr_auc": pr_auc_score,
            "mean_score": mean_score,
        },
    )


def calculate_ranking_metric(df):
    # Add binary reward
    df.loc[df["reward"] <= 0, "binary_reward"] = 0
    df.loc[df["reward"] > 0, "binary_reward"] = 1

    evaluate_dict = {}
    K_LIST = [10, 50, np.nan]
    for k in K_LIST:
        k_string = "all" if pd.isna(k) else str(k)
        for metric, fun in [
            ("map", calculate_mean_average_precision),
            ("ndcg", calculate_ndcg),
        ]:
            evaluate_dict[f"{metric}_at_{k_string}"] = fun(df, k)
    return evaluate_dict


def calculate_diversity(df, k_list=None):
    k_list = k_list if k_list else [5, 10, 30]

    def _calculate_diversity(df, k):
        df = df[df["rank"] <= k]
        df = df.sort_values(["example_id", "rank"])
        return df.groupby("example_id")["additional_properties.action_category"].nunique().mean() / k

    eval_dict = {}
    for k in k_list:
        eval_dict[f"diversity@{k}"] = _calculate_diversity(df, k)

    return eval_dict


def calculate_ndcg(df, k=None):
    def _safe_ndcg_score(y_true, y_score, k=None, sample_weight=None):
        # ndcg doesnt work for lists of length 1,
        # see: https://stackoverflow.com/questions/67631896/getting-error-while-calculating-ndcg-using-sklearn
        if len(y_true) == 1:
            return np.nan
        return ndcg_score([y_true], [y_score], k=k, sample_weight=sample_weight)

    if pd.isna(k):
        k = None

    # calculate ndcg per example
    df = df.sort_values(["example_id", "rank"])
    example_ndcg = df.groupby("example_id")[["reward", "rank"]].apply(
        lambda x: _safe_ndcg_score(x.reward.values, (-1) * x["rank"].values, k)
    )
    if np.all(np.isnan(example_ndcg)):
        return np.nan
    else:
        return np.nanmean(example_ndcg)


def calculate_mean_average_precision(df: pd.DataFrame, k: int = None):
    # for map@k
    if pd.isna(k):
        k = np.inf
    df = df[df["rank"] <= k]
    # just in case
    df = df.sort_values(["example_id", "rank"])

    # calculate ap per example
    df_map = df.groupby("example_id")["binary_reward"].agg([average_precision]).reset_index(drop=False)
    return np.nanmean(df_map.average_precision)


def precision_at_k(r, k):
    """Score is precision @ k
    Relevance is binary (nonzero is relevant).

    Args:
        r: Relevance scores (list or numpy) in rank order
            (first element is the first item)
    Returns:
        Precision @ k
    Raises:
        ValueError: len(r) must be >= k
    """
    assert k >= 1
    r = np.asarray(r)[:k] != 0
    if r.size != k:
        raise ValueError("Relevance score length < k")
    return np.mean(r)


def average_precision(r):
    """Score is average precision
    Relevance is binary (nonzero is relevant).
    Args:
        r: Relevance scores (list or numpy) in rank order
            (first element is the first item)
    Returns:
        Average precision
    """
    if np.sum(r) == 0:
        return 0.0
    r = np.asarray(r) != 0
    out = [precision_at_k(r, k + 1) for k in range(r.size) if r[k]]
    if not out:
        return 0.0
    return np.mean(out)


def read_predicted_data_from_json(path):
    def open_file():
        return gzip.open(path) if path.lower().endswith(".gz") else open(path)

    def data_gen():
        with open_file() as f:
            for line in f:
                yield json.loads(line)

    return pd.json_normalize(data=data_gen(), record_path=["result"], meta=["example_id"])


def _get_nested_value(d: Dict, *keys):
    for key in keys:
        if d is None:
            return None
        d = d.get(key)
    return d


def _none_as_missing(d: Dict):
    return {k: v for k, v in d.items() if v is not None}


def extract_evaluation(result_dict: Dict[str, Any]) -> Optional[Dict[str, Union[str, float]]]:
    performance_test = result_dict.get("performance_test", {})
    response_time_metrics = performance_test.get("response_time_metrics", {})
    evaluation = result_dict.get("evaluate", {})

    response_time_metrics = {
        key: get_nested_value(response_time_metrics, key + ".mean") for key in ["p50", "p75", "p95", "p99", "p999"]
    }
    bootstrapped_metric_keys = [
        "roc_auc",
        "pr_auc",
        "mean_score",
    ]
    non_bootstrapped_metric_keys = ["map_at_10", "map_at_50", "map_at_all", "ndcg_at_10", "ndcg_at_50", "ndcg_at_all"]
    diversity_keys = ["diversity@5", "diversity@10", "diversity@30"]

    evaluation_metrics = {}
    for key in non_bootstrapped_metric_keys + diversity_keys:
        value = get_nested_value(evaluation, key)
        if value is not None:
            evaluation_metrics[key] = value

    for key in bootstrapped_metric_keys:
        value = get_nested_value(evaluation, key + ".mean")
        if value is not None:
            evaluation_metrics[key] = value

    online_evaluation_metrics = {}
    online_evaluation = evaluation.get("online", {})
    for dimension in online_evaluation.keys():
        for key in non_bootstrapped_metric_keys:
            value = get_nested_value(online_evaluation[dimension], key)
            if value is not None:
                online_evaluation_metrics[f"{dimension}.{key}"] = value
        for key in bootstrapped_metric_keys:
            value = get_nested_value(online_evaluation[dimension], key + ".mean")
            if value is not None:
                online_evaluation_metrics[f"{dimension}.{key}"] = value

    if not any([response_time_metrics, evaluation_metrics, online_evaluation_metrics]):
        return None

    result = {
        "algorithm_id": result_dict["algorithm_id"],
        "test_date": datetime.fromisoformat(result_dict["test_data_time"]),
        "max_memory_usage": get_nested_value(performance_test, "max_memory_usage"),
        "mean_throughput": get_nested_value(performance_test, "mean_throughput"),
        **response_time_metrics,
        **evaluation_metrics,
        **online_evaluation_metrics,
    }

    return result


def get_nested_value(d, keys, default=None):
    for key in keys.split("."):
        if isinstance(d, dict):
            d = d.get(key, {})
        else:
            return default
    return d if d != {} else default
