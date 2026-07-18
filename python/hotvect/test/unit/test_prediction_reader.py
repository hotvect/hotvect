import gzip
import json
import logging
from operator import attrgetter
from pathlib import Path

import pytest

from hotvect.prediction_reader import MissingRewardPolicy, PredictionReader, PredictionRow, ReadStats

TEST_FILES_DIR = Path(__file__).parent / "testfiles"
EXAMPLE_PREDICTION_1 = PredictionRow(
    example_id="example-1",
    rewards=[1.0],
    ranks=[0],
    scores=[0.5],
    online_ranks={},
    online_scores={},
    categories=None,
)
EXAMPLE_PREDICTION_1_SERIALISED = {
    "example_id": "example-1",
    "result": [{"action_id": "sku1", "reward": 1.0, "rank": 0, "score": 0.5}],
}
EXAMPLE_PREDICTION_2 = PredictionRow(
    example_id="example-2",
    rewards=[0.0],
    ranks=[0],
    scores=[0.1],
    online_ranks={},
    online_scores={},
    categories=None,
)
EXAMPLE_PREDICTION_2_SERIALISED = {
    "example_id": "example-2",
    "result": [{"action_id": "sku2", "reward": 0.0, "rank": 0, "score": 0.1}],
}


def _write_prediction_rows(path: Path, rows: list[dict]) -> None:
    opener = gzip.open if path.name.lower().endswith(".gz") else open
    with opener(path, "wt") as prediction_file:
        for row in rows:
            prediction_file.write(json.dumps(row) + "\n")


def test_read_single_prediction_file(tmp_path: Path) -> None:
    prediction_file = tmp_path / "prediction.jsonl"
    rows = [EXAMPLE_PREDICTION_1_SERIALISED, EXAMPLE_PREDICTION_2_SERIALISED]
    _write_prediction_rows(prediction_file, rows)

    predictions = list(PredictionReader(prediction_file).iter_rows())
    assert predictions == [
        EXAMPLE_PREDICTION_1,
        EXAMPLE_PREDICTION_2,
    ]


def test_readsdirectory_with_single_non_part_prediction_file(tmp_path: Path) -> None:
    prediction_file = tmp_path / "manual_results.json"
    rows = [EXAMPLE_PREDICTION_1_SERIALISED]
    _write_prediction_rows(prediction_file, rows)

    predictions = list(PredictionReader(tmp_path).iter_rows())
    assert predictions == [EXAMPLE_PREDICTION_1]


def test_readssupported_prediction_files_and_warns_for_ignored_files(
    tmp_path: Path, caplog: pytest.LogCaptureFixture
) -> None:
    rows = [
        {"example_id": f"example-{i}", "result": [{"action_id": "sku1", "reward": 0.0, "rank": 0, "score": 0.5}]}
        for i in range(1, 6)
    ]
    prediction_paths = [
        tmp_path / "2026-03-25-results.json.gz",
        tmp_path / "manual_results.json",
        tmp_path / "part-00000",
        tmp_path / "part-00001.gz",
        tmp_path / "prediction.jsonl",
        tmp_path / "prediction.ndjson",
    ]
    for path, row in zip(prediction_paths, rows):
        _write_prediction_rows(path, [row])

    (tmp_path / ".DS_Store").write_text("not predictions\n")
    (tmp_path / "_SUCCESS").write_text("\n")
    (tmp_path / "_metadata.json").write_text("{}\n", encoding="utf-8")
    (tmp_path / "notes.txt").write_text("not predictions\n")
    (tmp_path / "part-").write_text("not predictions\n")
    (tmp_path / "part-.gz").write_bytes(gzip.compress(b"not predictions\n"))
    (tmp_path / "part-00002.parquet").write_text("not predictions\n")
    (tmp_path / "part-00003.parquet.gz").write_bytes(gzip.compress(b"not predictions\n"))
    (tmp_path / "nested.json").mkdir()

    with caplog.at_level(logging.WARNING):
        predictions = list(PredictionReader(tmp_path).iter_rows())

    assert len(predictions) == len(rows)
    warning_messages = [record.getMessage() for record in caplog.records]
    assert any("Ignoring 8 non-prediction files" in message for message in warning_messages)
    for ignored_name in [
        ".DS_Store",
        "_SUCCESS",
        "_metadata.json",
        "notes.txt",
        "part-",
        "part-.gz",
        "part-00002.parquet",
        "part-00003.parquet.gz",
    ]:
        assert any(ignored_name in message for message in warning_messages)


def test_reject_corrupt_part_files_in_directory(tmp_path: Path) -> None:
    (tmp_path / "part-00000.jsonl").write_text('{"example_id": "example-1", "result": []}\n', encoding="utf-8")
    (tmp_path / "part-00001.jsonl").write_text('{"example_id": "example-2", "result": [', encoding="utf-8")

    with pytest.raises(json.JSONDecodeError):
        list(PredictionReader(tmp_path).iter_rows())


def test_reject_empty_part_files_in_directory(tmp_path: Path) -> None:
    _write_prediction_rows(tmp_path / "part-00000.jsonl", [{"example_id": "example-1", "result": []}])
    (tmp_path / "part-00001.jsonl").write_text("", encoding="utf-8")

    with pytest.raises(ValueError, match="Prediction file is empty"):
        list(PredictionReader(tmp_path)._iter_rows())


def test_reject_empty_prediction_file(tmp_path: Path) -> None:
    prediction_file = tmp_path / "prediction.jsonl"
    prediction_file.write_text("", encoding="utf-8")

    with pytest.raises(ValueError, match="Prediction file is empty"):
        list(PredictionReader(prediction_file).iter_rows())


def test_reject_directories_without_supported_prediction_files(tmp_path: Path) -> None:
    (tmp_path / "_metadata.json").write_text("{}\n", encoding="utf-8")

    with pytest.raises(FileNotFoundError, match="No supported prediction files found"):
        list(PredictionReader(tmp_path).iter_rows())


def test_read_realistic_predictions() -> None:
    reader = PredictionReader(TEST_FILES_DIR / "prediction.jsonl")

    predictions = list(reader.iter_rows())

    expected_rewards = [
        [1.18, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
        [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
        [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
    ]
    expected_binary_rewards = [
        [1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
        [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
        [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
    ]
    expected_ranks = [
        [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
        [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
        [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
    ]
    expected_scores = [
        pytest.approx(
            [
                0.74622701,
                0.2986824,
                0.03820074,
                0.03763735,
                0.03516864,
                0.03500169,
                0.03368857,
                0.03201348,
                0.03103863,
                0.01497738,
                0.01255696,
            ]
        ),
        pytest.approx(
            [
                0.4988013,
                0.07937842,
                0.06769503,
                0.05736812,
                0.03462971,
                0.03193682,
                0.03069153,
                0.03066427,
                0.02196615,
                0.01609506,
                0.01272679,
            ]
        ),
        pytest.approx(
            [
                0.09749082,
                0.07198991,
                0.07191197,
                0.0451667,
                0.0443118,
                0.04185321,
                0.03976128,
                0.02228146,
                0.02206082,
                0.01902447,
                0.01685245,
            ]
        ),
    ]
    expected_categories = [
        ["21C", "11A", "11A", "21C", "31K", "21S", "81H", "21C", "11A", "3J1", "21N"],
        ["11A", "3ES", "11A", "11A", "21D", "43A", "320", "3X8", "15O", "3J1", "11B"],
        ["41E", "11A", "11A", "21C", "21E", "15O", "41E", "21U", "41E", "41I", "41E"],
    ]
    expected_online_ranks = [
        {"algorithm": [0, 1, 5, 2, 7, 3, 6, 4, 9, 8, 10]},
        {"algorithm": [0, 2, 1, 3, 9, 4, 8, 7, 5, 6, 10]},
        {"algorithm": [6, 3, 1, 4, 2, 9, 0, 8, 7, 10, 5]},
    ]
    expected_online_scores = [
        {
            "algorithm": pytest.approx(
                [
                    0.72076638,
                    0.17468651,
                    0.03066843,
                    0.04939424,
                    0.02840742,
                    0.0365695,
                    0.03004375,
                    0.03221694,
                    0.02591061,
                    0.0276242,
                    0.02574239,
                ]
            )
        },
        {
            "algorithm": pytest.approx(
                [
                    0.15859621,
                    0.04802561,
                    0.09161196,
                    0.03330885,
                    0.0196179,
                    0.02653464,
                    0.02186459,
                    0.02247656,
                    0.02262845,
                    0.02247841,
                    0.01954294,
                ]
            )
        },
        {
            "algorithm": pytest.approx(
                [
                    0.03620478,
                    0.05092265,
                    0.08878261,
                    0.04351145,
                    0.05628039,
                    0.03401866,
                    0.10883825,
                    0.03484593,
                    0.03498806,
                    0.03382683,
                    0.04244453,
                ]
            )
        },
    ]
    expected_missing_reward_stats = ReadStats(
        total_examples=3,
        examples_with_missing_reward=0,
        total_results=33,
        missing_reward_count=0,
    )

    assert list(map(attrgetter("rewards"), predictions)) == expected_rewards
    assert list(map(attrgetter("binary_rewards"), predictions)) == expected_binary_rewards
    assert list(map(attrgetter("ranks"), predictions)) == expected_ranks
    assert list(map(attrgetter("scores"), predictions)) == expected_scores
    assert list(map(attrgetter("categories"), predictions)) == expected_categories
    assert list(map(attrgetter("online_ranks"), predictions)) == expected_online_ranks
    assert list(map(attrgetter("online_scores"), predictions)) == expected_online_scores
    assert reader.get_stats() == expected_missing_reward_stats


def test_missing_reward_error(tmp_path: Path) -> None:
    prediction_file = tmp_path / "prediction.jsonl"
    rows = [
        {
            "example_id": "example-1",
            "result": [
                {"action_id": "sku1", "reward": 1.0, "rank": 0, "score": 0.5},
                {"action_id": "sku2", "rank": 1, "score": 0.1},
            ],
        }
    ]
    _write_prediction_rows(prediction_file, rows)

    with pytest.raises(ValueError, match="missing reward data"):
        list(PredictionReader(prediction_file).iter_rows())


def test_missing_reward_zero(tmp_path: Path) -> None:
    prediction_file = tmp_path / "prediction.jsonl"
    rows = [
        {
            "example_id": "example-1",
            "result": [
                {"action_id": "sku1", "reward": 1.0, "rank": 0, "score": 0.5},
                {"action_id": "sku2", "rank": 1, "score": 0.1},
            ],
        }
    ]
    _write_prediction_rows(prediction_file, rows)

    reader = PredictionReader(prediction_file, missing_reward_policy=MissingRewardPolicy.ZERO)
    predictions = list(reader.iter_rows())

    assert predictions == [
        PredictionRow(
            example_id="example-1",
            rewards=[1.0, 0.0],
            ranks=[0, 1],
            scores=[0.5, 0.1],
            online_ranks={},
            online_scores={},
            categories=None,
        )
    ]


def test_incomplete_online_dimensions(tmp_path: Path) -> None:
    prediction_file = tmp_path / "prediction.jsonl"
    rows = [
        {
            "example_id": "example-1",
            "result": [
                {
                    "action_id": "sku1",
                    "reward": 1.0,
                    "rank": 0,
                    "score": 0.5,
                    "additional_properties": {
                        "online": {
                            "dim1": {"rank": 0},
                            "dim2": {"rank": 0, "score": 0.6},
                            "dim3": {"rank": 1, "score": 0.2},
                        },
                    },
                },
                {
                    "action_id": "sku2",
                    "reward": 0.0,
                    "rank": 1,
                    "score": 0.1,
                    "additional_properties": {
                        "online": {
                            "dim1": {"rank": 1},
                            "dim2": {"rank": 1, "score": 0.2},
                            "dim3": {"rank": 0, "score": 0.4},
                        },
                    },
                },
            ],
        },
        {
            "example_id": "example-2",
            "result": [
                {
                    "action_id": "sku3",
                    "reward": 0.0,
                    "rank": 0,
                    "score": 0.5,
                    "additional_properties": {
                        "online": {
                            "dim2": {"rank": 0, "score": 0.6},
                            "dim3": {"rank": 1, "score": 0.2},
                        },
                    },
                },
                {
                    "action_id": "sku2",
                    "reward": 0.0,
                    "rank": 1,
                    "score": 0.1,
                    "additional_properties": {
                        "online": {
                            "dim2": {"rank": 0, "score": 0.2},
                        },
                    },
                },
            ],
        },
    ]
    _write_prediction_rows(prediction_file, rows)

    reader = PredictionReader(prediction_file)
    predictions = list(reader.iter_rows())

    expected_predictions = [
        PredictionRow(
            example_id="example-1",
            rewards=[1.0, 0.0],
            ranks=[0, 1],
            scores=[0.5, 0.1],
            online_ranks={"dim2": [0, 1], "dim3": [1, 0]},
            online_scores={"dim2": [0.6, 0.2], "dim3": [0.2, 0.4]},
            categories=None,
        ),
        PredictionRow(
            example_id="example-2",
            rewards=[0.0, 0.0],
            ranks=[0, 1],
            scores=[0.5, 0.1],
            online_ranks={"dim2": [0, 0]},
            online_scores={"dim2": [0.6, 0.2]},
            categories=None,
        ),
    ]

    assert predictions == expected_predictions
    assert reader.get_complete_online_dimensions() == {"dim2"}
