import gzip
import json
import logging
from datetime import datetime
from typing import Any, Dict, Optional, Union

import numpy as np
from sklearn.metrics import average_precision_score, ndcg_score, roc_auc_score

logger = logging.getLogger(__name__)


def ndcg_for_each_k(reward_arrays, rank_arrays, ks):
    n = len(reward_arrays)
    max_k = max(ks)
    # Set the padding for any ranking requests that have less than k items
    rewards = np.zeros((n, max_k))
    ranks = 10**8 * np.ones((n, max_k))
    for i, (reward, rank) in enumerate(zip(reward_arrays, rank_arrays)):
        # Sort so that the first k are always used
        ranking_indices = np.argsort(rank)
        rank = rank[ranking_indices]
        reward = reward[ranking_indices]
        copy_length = min(max_k, reward.shape[0])
        rewards[i, :copy_length] = reward[:copy_length]
        ranks[i, :copy_length] = rank[:copy_length]

    return [ndcg_score(rewards[:, :k], -ranks[:, :k]) for k in ks]


def map_for_each_k(binary_reward_arrays, rank_arrays, k_values):
    total_average_precisions = [0.0] * len(k_values)
    n = 0
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
        for i, k in enumerate(k_values):
            if k is None:
                k = rewards.shape[0]
            total_ones = np.sum(rewards[:k])
            if total_ones == 0:
                ap_score_at_k = 0.0
            else:
                total_precision_for_ones = np.sum(precisions_for_ones[:k])
                ap_score_at_k = total_precision_for_ones / total_ones
            total_average_precisions[i] += ap_score_at_k
        n += 1

    if n == 0:
        return np.nan
    return [total_ap / n for total_ap in total_average_precisions]


def diversity(rank_arrays, category_arrays, k_list):
    total_unique_k = np.zeros(len(k_list))

    n = 0
    for ranks, categories in zip(rank_arrays, category_arrays):
        for i, k in enumerate(k_list):
            ranking_indices = np.argsort(ranks)
            categories = categories[ranking_indices]
            unique = np.unique(categories[:k])
            total_unique_k[i] += unique.shape[0]
        n += 1

    if n == 0:
        raise ValueError("No Categories specified, cannot calculate diversity")
    mean_unique_k = total_unique_k / np.array(k_list) / n
    return mean_unique_k.tolist()


def calculate_metrics(ks, reward_arrays, binary_reward_arrays, rank_arrays, score_arrays):
    evaluate_dict = {}

    ndcgs = ndcg_for_each_k(reward_arrays, rank_arrays, ks)
    # For each k calculate the NDCG.
    for ndcg_at_k, k in zip(ndcgs, ks):
        evaluate_dict[f"ndcg_at_{k}"] = ndcg_at_k

    # Compute the MAP10, MAP50, and MAP_ALL all at once for performance gains
    maps = map_for_each_k(binary_reward_arrays, rank_arrays, ks)
    for mean_ap, k in zip(maps, ks):
        evaluate_dict[f"map_at_{k}"] = mean_ap

    # Concatenate Arrays to calculate the Area under the ROC and PR curves
    all_rewards = np.concatenate(binary_reward_arrays)
    all_scores = np.concatenate(score_arrays)
    # Have eliminated unused confidence intervals
    evaluate_dict["roc_auc"] = {"mean": roc_auc_score(all_rewards, all_scores)}
    evaluate_dict["pr_auc"] = {"mean": average_precision_score(all_rewards, all_scores)}
    evaluate_dict["mean_score"] = {"mean": np.mean(all_scores)}
    return evaluate_dict


def calculate_all_metrics(
    ks, diversity_ks, reward_arrays, binary_reward_arrays, rank_arrays, score_arrays, categories, dimensions
):
    if len(rank_arrays) == 0 and len(reward_arrays) == 0 and len(score_arrays) == 0:
        return {"skipped": "This algorithm always returns empty results. No metrics can be calculated."}
    eval_dict = calculate_metrics(ks, reward_arrays, binary_reward_arrays, rank_arrays, score_arrays)
    if categories:
        mean_diversity_k = diversity(rank_arrays, categories, diversity_ks)
        for k, diversity_at_k in zip(diversity_ks, mean_diversity_k):
            eval_dict[f"diversity@{k}"] = diversity_at_k
    if dimensions:
        eval_dict["online"] = {}
        for dim_name, dim_dict in dimensions.items():
            ranks = dim_dict["ranks"]
            scores = dim_dict["scores"]
            eval_dict["online"][dim_name] = calculate_metrics(ks, reward_arrays, binary_reward_arrays, ranks, scores)
    return eval_dict


def _process_row(data, dimensions, rewards, binary_rewards, ranks, scores, categories):
    current_rewards = []
    current_ranks = []
    current_scores = []
    current_categories = []
    # Append the current ranks and scores for each dimension too
    for dim in dimensions.values():
        dim["ranks"].append([])
        dim["scores"].append([])
    for item in data["result"]:
        current_rewards.append(item["reward"])
        current_ranks.append(item["rank"])
        current_scores.append(item["score"])
        if "additional_properties" in item:
            additional_properties = item["additional_properties"]
            if "action_category" in additional_properties:
                current_categories.append(additional_properties["action_category"])
            if "online" in additional_properties:
                online_data = additional_properties["online"]
                for dim_name, dim_data in online_data.items():
                    if "rank" in dim_data and "score" in dim_data:
                        if dim_name not in dimensions:
                            dimensions[dim_name] = {"ranks": [[]], "scores": [[]]}
                        dimensions[dim_name]["ranks"][-1].append(dim_data["rank"])
                        dimensions[dim_name]["scores"][-1].append(dim_data["score"])

    # Only keep the current ranks and scores if they are non-empty
    if current_ranks and current_scores and current_rewards:
        rewards.append(np.array(current_rewards))
        binary_rewards.append(np.where(rewards[-1] > 0, 1.0, 0.0))
        ranks.append(np.array(current_ranks))
        scores.append(np.array(current_scores))
    for dim in dimensions.values():
        if len(dim["ranks"][-1]) == 0 or len(dim["scores"][-1]) == 0:
            dim["ranks"].pop()
            dim["scores"].pop()
        else:
            dim["ranks"][-1] = np.array(dim["ranks"][-1])
            dim["scores"][-1] = np.array(dim["scores"][-1])
    if current_categories:
        categories.append(np.array(current_categories))


def standard_evaluation(path, ks=None, diversity_ks=None):
    rewards = []
    binary_rewards = []
    ranks = []
    scores = []
    categories = []
    dimensions = {}

    if not ks:
        ks = [10, 50, 100]
    if not diversity_ks:
        diversity_ks = [5, 10, 30]

    opener = gzip.open if path.lower().endswith(".gz") else open
    with opener(path, "rt") as f:
        for line in f:
            _process_row(json.loads(line), dimensions, rewards, binary_rewards, ranks, scores, categories)

    return calculate_all_metrics(ks, diversity_ks, rewards, binary_rewards, ranks, scores, categories, dimensions)


def dcg_adapted(reward):
    """
    Calculate the adapted DCG score with penalty for negative rewards.

    Parameters:
    - reward (list of float): List of reward values.

    Returns:
    - float: Adapted DCG score.
    """
    dcg_gain = sum(g / np.log2(i + 1) for i, g in enumerate(reward, start=1))
    dcg_penalty = sum(np.abs(p) / np.log2(j + 1) for j, p in enumerate(reward, start=1) if p < 0)
    return dcg_gain - dcg_penalty


def ndcg_with_worst_dcg(scores, k):
    """
    Calculate the normalized discounted cumulative gain (NDCG) with worst-case scenario normalization.

    Parameters:
    - scores (list of float): List of scores containing positive and negative values.
    - k (int): The cutoff rank position.

    Returns:
    - float: NDCG score bounded between 0 and 1.
    """
    ideal_scores = sorted(scores, reverse=True)
    worst_scores = sorted(scores, reverse=False)

    dcg_current = dcg_adapted(scores[:k])
    dcg_worst = dcg_adapted(worst_scores[:k])
    dcg_ideal = dcg_adapted(ideal_scores[:k])

    return (dcg_current - dcg_worst) / (dcg_ideal - dcg_worst) if dcg_ideal - dcg_worst != 0 else 0


def real_numbers_reward_evaluation(path, ks=None):
    """
    Evaluate the NDCG metric for the rewards in range [-inf, inf].

    Parameters:
    - path (str): Path to the input data file (JSON or GZIP format).
    - ks (list of int, optional): List of k values for NDCG calculation. Defaults to [10, 50, 100].

    Returns:
    - dict: Dictionary containing NDCG scores for each k value.
    """
    rewards = []
    ranks = []

    if not ks:
        ks = [10, 50, 100]

    # Determine file opener based on extension
    opener = gzip.open if path.lower().endswith(".gz") else open
    with opener(path, "rt") as f:
        for line in f:
            data = json.loads(line)
            current_rewards = [item["reward"] for item in data["result"]]
            current_ranks = [item["rank"] for item in data["result"]]

            # Sort rewards based on their ranks
            sorted_indices = np.argsort(current_ranks)
            sorted_rewards = np.array(current_rewards)[sorted_indices].tolist()

            rewards.append(sorted_rewards)
            ranks.append(current_ranks)

    # Compute NDCG scores for each k value
    evaluation_results = {}
    for k in ks:
        ndcg_scores = [ndcg_with_worst_dcg(r, k) for r in rewards]
        evaluation_results[f"ndcg_at_{k}"] = np.mean(ndcg_scores)

    return evaluation_results


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
