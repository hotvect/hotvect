import enum
import gzip
import logging
from collections import defaultdict
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import orjson

from hotvect.text_data_files import list_text_data_files

logger = logging.getLogger(__name__)


@enum.unique
class MissingRewardPolicy(enum.StrEnum):
    ZERO = "zero"
    ERROR = "error"


@dataclass(frozen=True)
class PredictionRow:
    """
    High-level representation of an example with predictions.

    Each example contains an arbitrary number of actions. For each action, we have a real-valued
    reward, rank, score, and, if available, category code.

    Additionally, there could be multiple online evaluation dimensions (recorded in online
    inference). The corresponding ranks and scores are stored.

    All lists (for both online and offline evaluation) have the same length, equal to `num_actions`.
    """

    example_id: str | None

    rewards: list[float]
    ranks: list[int]
    scores: list[float]

    online_ranks: dict[str, list[int]]
    online_scores: dict[str, list[float]]

    categories: list[str] | None

    @property
    def binary_rewards(self) -> list[float]:
        """Binarised rewards."""
        return [1.0 if reward > 0 else 0.0 for reward in self.rewards]

    @property
    def num_actions(self) -> int:
        return len(self.scores)


@dataclass
class ReadStats:
    total_examples: int = 0
    examples_with_missing_reward: int = 0
    total_results: int = 0
    missing_reward_count: int = 0


class PredictionReader:
    """
    Read dataset of predictions.

    It can be stored in a single JSONL file or multiple JSONL shards in a directory.
    """

    def __init__(
        self, path: str | Path, *, missing_reward_policy: MissingRewardPolicy = MissingRewardPolicy.ERROR
    ) -> None:
        """Construct from a file or directory."""

        self._path = Path(path)
        self._missing_reward_policy = missing_reward_policy
        self._stats: ReadStats | None = None
        self._complete_online_dimensions: set[str] | None = None

    def get_complete_online_dimensions(self) -> set[str]:
        """
        Report online dimensions that are present in every non-empty row.

        Only available after all rows have been read.
        """

        if self._complete_online_dimensions is None:
            raise ValueError("Complete online dimensions are only available after iterating rows")
        return self._complete_online_dimensions

    def get_stats(self) -> ReadStats:
        """
        Summary statistics about read rows.

        Only available after all rows have been read.
        """

        if self._stats is None:
            raise ValueError("Stats are only available after iterating rows")
        return self._stats

    def iter_rows(self) -> Iterable[PredictionRow]:
        """
        Read prediction dataset row by row.

        Skip rows containing no actions.
        """

        stats = ReadStats()
        complete_online_dimensions: set[str] | None = None

        for row in self._iter_rows():
            stats.total_examples += 1
            got_missing_reward = False

            example_id = row.get("example_id")
            rewards = []
            ranks = []
            scores = []
            categories = []
            online_ranks = defaultdict(list)
            online_scores = defaultdict(list)

            for action_index, action in enumerate(row["result"]):
                stats.total_results += 1

                reward, is_missing_reward = self._get_reward(action, example_id=example_id, action_index=action_index)
                if is_missing_reward:
                    stats.missing_reward_count += 1
                    got_missing_reward = True

                rewards.append(reward)
                ranks.append(action["rank"])
                scores.append(action["score"])

                if (additional_properties := action.get("additional_properties")) is not None:
                    if (category := additional_properties.get("action_category")) is not None:
                        categories.append(category)
                    if (online_data := additional_properties.get("online")) is not None:
                        for dimension_name, dimension_data in online_data.items():
                            if "rank" not in dimension_data or "score" not in dimension_data:
                                continue
                            online_ranks[dimension_name].append(dimension_data["rank"])
                            online_scores[dimension_name].append(dimension_data["score"])

            if got_missing_reward:
                stats.examples_with_missing_reward += 1

            # Skip rows with no actions
            if not (rewards and ranks and scores):
                continue

            # Drop incomplete online dimensions. These are dimensions for which scores or rewards are missing for some
            # actions in the row
            incomplete_online_dimensions = set()
            for dimension_name in online_ranks:
                if len(online_ranks[dimension_name]) != len(scores) or len(online_scores[dimension_name]) != len(
                    scores
                ):
                    incomplete_online_dimensions.add(dimension_name)

            for dimension_name in incomplete_online_dimensions:
                del online_ranks[dimension_name]
                del online_scores[dimension_name]

            # Keep track of online dimensions that are present in all non-empty rows
            if complete_online_dimensions is None:
                complete_online_dimensions = set(online_ranks)
            complete_online_dimensions &= set(online_ranks)

            yield PredictionRow(
                example_id=example_id,
                rewards=rewards,
                ranks=ranks,
                scores=scores,
                online_ranks=online_ranks,
                online_scores=online_scores,
                categories=categories if categories else None,
            )

        self._stats = stats
        self._complete_online_dimensions = complete_online_dimensions

    def _get_files(self) -> list[Path]:
        """Return prediction files in the order they should be read."""

        if self._path.is_dir():
            prediction_files = list_text_data_files(self._path)
            prediction_file_set = set(prediction_files)
            ignored_files = [
                path.name for path in sorted(self._path.iterdir()) if path.is_file() and path not in prediction_file_set
            ]
            if ignored_files:
                logger.warning(
                    "Ignoring %d non-prediction files in %s: %s",
                    len(ignored_files),
                    self._path,
                    ", ".join(ignored_files),
                )
            if not prediction_files:
                raise FileNotFoundError(f"No supported prediction files found in directory: {self._path}")
            return prediction_files
        return [self._path]

    def _get_reward(
        self,
        action: dict[str, Any],
        *,
        example_id: str | None,
        action_index: int,
    ) -> tuple[float, bool]:
        """Extract reward from an action."""

        if "reward" in action:
            return action["reward"], False
        if self._missing_reward_policy == MissingRewardPolicy.ZERO:
            return 0.0, True
        raise ValueError(
            "Prediction output is missing reward data, so evaluation cannot run. "
            f"Missing reward for example_id={example_id}, result_index={action_index}."
        )

    @staticmethod
    def _iter_file_rows(path: Path) -> Iterable[dict[str, Any]]:
        """Yield decoded JSON rows from one prediction file."""

        opener = gzip.open if path.name.lower().endswith(".gz") else open
        with opener(path, "rt") as prediction_file:
            saw_rows = False
            for line in prediction_file:
                saw_rows = True
                yield orjson.loads(line)
            if not saw_rows:
                raise ValueError(f"Prediction file is empty: {path}")

    def _iter_rows(self) -> Iterable[dict[str, Any]]:
        """Yield decoded prediction rows from all prediction files."""

        for prediction_file in self._get_files():
            yield from self._iter_file_rows(prediction_file)
