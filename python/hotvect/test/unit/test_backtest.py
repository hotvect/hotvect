import datetime
import json
import os.path
from pathlib import Path

from hotvect.backtest import list_output_dirs
from hotvect.mlutils import extract_evaluation

example_result_base_dir = Path(os.path.dirname(os.path.realpath(__file__))) / "testfiles" / "meta"


def test_read_results():
    expected = {
        "algorithm_id": "example-algorithm@1.1.1",
        "algorithm.pr_auc": 0.005562758750636963,
        "algorithm.roc_auc": 0.7954481367249178,
        "diversity@10": 0.5389605324569872,
        "diversity@30": 0.31078265891765283,
        "diversity@5": 0.7129776800271087,
        "impression.map_at_10": 0.005073945326479579,
        "impression.map_at_50": 0.005326563142979179,
        "impression.map_at_all": 0.0053234890023868536,
        "impression.ndcg_at_10": 0.006412982533851097,
        "impression.ndcg_at_50": 0.0085194559010677,
        "impression.ndcg_at_all": 0.008574699913360101,
        "map_at_10": 0.005358904300327959,
        "map_at_50": 0.00556907716885868,
        "map_at_all": 0.00556959610794158,
        "max_memory_usage": 12341234,
        "mean_throughput": 1234,
        "ndcg_at_10": 0.006818835796431752,
        "ndcg_at_50": 0.008758071563963004,
        "ndcg_at_all": 0.008805847221075294,
        "p50": 626158.0,
        "p75": 1401592.6,
        "p95": 4464158.4,
        "p99": 11725205.4,
        "p999": 35302789.4,
        "pr_auc": 0.0060868409998212965,
        "roc_auc": 0.8196221521258884,
        "test_date": datetime.datetime(2022, 9, 3, 1, 2, 3, 123456, tzinfo=datetime.timezone.utc),
    }

    jsons = list_output_dirs(
        str(example_result_base_dir),
        algorithm_name_pattern="example*",
        algorithm_version_pattern="1.*",
        from_including_test_date=datetime.date.fromisoformat("2022-09-03"),
        to_including_test_date=datetime.date.fromisoformat("2022-09-03"),
    )
    for result in jsons:
        with open(os.path.join(result, "result.json")) as f:
            parsed = json.load(f)
            assert extract_evaluation(parsed) == expected


def test_read_results_of_range():
    jsons = list_output_dirs(
        str(example_result_base_dir),
        from_including_test_date=datetime.date(2022, 9, 3),
        to_including_test_date=datetime.date(2022, 9, 3),
    )
    assert len(jsons) == 1
    assert str(jsons[0]).endswith("example-algorithm@1.1.1/last_train_date_2022-09-02-last_test_date_2022-09-03")
