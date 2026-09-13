"""Prediction-equivalence utility for the Hotvect QA CLI."""

from __future__ import annotations

import json
import math
import os
import sys
from collections.abc import Iterable
from dataclasses import dataclass
from itertools import zip_longest
from pathlib import Path
from typing import Any


class PredictionComparisonInputError(ValueError):
    """Raised when the input files do not follow the expected predict JSONL schema."""


@dataclass(frozen=True)
class ParsedRecord:
    example_id: str
    order: list[str]
    score_by_action_id: dict[str, float]


def _extract_action_id(item: dict[str, Any], line_number: int, side: str) -> str:
    action_id: Any = item.get("action_id")

    if action_id is None:
        additional_properties = item.get("additional_properties")
        if isinstance(additional_properties, dict) and additional_properties.get("action_id") is not None:
            action_id = additional_properties["action_id"]
        else:
            raise PredictionComparisonInputError(f"line {line_number}: missing action_id in {side}.result item")
    if isinstance(action_id, str):
        return action_id
    if isinstance(action_id, (int, float)) and not isinstance(action_id, bool):
        return str(action_id)
    raise PredictionComparisonInputError(
        f"line {line_number}: action_id must be string/number in {side}.result item, got {type(action_id).__name__}"
    )


def _extract_rank(item: dict[str, Any], line_number: int, side: str) -> int:
    rank = item.get("rank")
    if not isinstance(rank, int) or isinstance(rank, bool):
        raise PredictionComparisonInputError(
            f"line {line_number}: {side}.result item must contain integer rank, got {rank!r}"
        )
    return rank


def _extract_score(item: dict[str, Any], item_index: int, line_number: int, side: str) -> float:
    score = item.get("score")
    if not isinstance(score, (int, float)) or isinstance(score, bool):
        raise PredictionComparisonInputError(
            f"line {line_number}: {side}.result[{item_index}].score must be a finite number, got {score!r}"
        )
    try:
        numeric_score = float(score)
    except OverflowError as exc:
        raise PredictionComparisonInputError(
            f"line {line_number}: {side}.result[{item_index}].score must be a finite number, got {score!r}"
        ) from exc
    if not math.isfinite(numeric_score):
        raise PredictionComparisonInputError(
            f"line {line_number}: {side}.result[{item_index}].score must be a finite number, got {score!r}"
        )
    return numeric_score


def _parse_record(payload: Any, line_number: int, side: str) -> ParsedRecord:
    if not isinstance(payload, dict):
        raise PredictionComparisonInputError(f"line {line_number}: {side} root JSON must be an object")

    example_id = payload.get("example_id")
    if example_id is None:
        raise PredictionComparisonInputError(f"line {line_number}: missing example_id in {side} record")
    if not isinstance(example_id, str):
        raise PredictionComparisonInputError(f"line {line_number}: {side}.example_id must be a string")

    result = payload.get("result")
    if not isinstance(result, list):
        raise PredictionComparisonInputError(f"line {line_number}: {side}.result must be an array")

    order: list[str] = []
    score_by_action_id: dict[str, float] = {}
    action_id_by_rank: dict[int, str] = {}
    for item_index, item in enumerate(result):
        if not isinstance(item, dict):
            raise PredictionComparisonInputError(
                f"line {line_number}: {side}.result[{item_index}] must be an object, got {type(item).__name__}"
            )
        action_id = _extract_action_id(item, line_number, side)
        if action_id in score_by_action_id:
            raise PredictionComparisonInputError(
                f"line {line_number}: duplicate action_id in {side} result: {action_id}"
            )
        rank = _extract_rank(item, line_number, side)
        if rank in action_id_by_rank:
            raise PredictionComparisonInputError(f"line {line_number}: duplicate rank in {side} result: {rank}")

        score_by_action_id[action_id] = _extract_score(item, item_index, line_number, side)
        action_id_by_rank[rank] = action_id

    order = [action_id for _, action_id in sorted(action_id_by_rank.items())]
    return ParsedRecord(example_id=example_id, order=order, score_by_action_id=score_by_action_id)


def _build_tie_groups(order: Iterable[str], score_by_action_id: dict[str, float], eps: float) -> list[list[str]]:
    grouped: list[list[str]] = []
    current_group: list[str] = []
    anchor_score: float | None = None
    for action_id in order:
        score = score_by_action_id[action_id]
        if not current_group:
            current_group = [action_id]
            anchor_score = score
            continue
        if anchor_score is not None and abs(score - anchor_score) <= eps:
            current_group.append(action_id)
        else:
            grouped.append(current_group)
            current_group = [action_id]
            anchor_score = score
    if current_group:
        grouped.append(current_group)
    return grouped


def _rank_equivalent_with_tie_breaking(control: ParsedRecord, treatment: ParsedRecord, eps: float) -> bool:
    if len(control.order) != len(treatment.order):
        return False
    if set(control.order) != set(treatment.order):
        return False

    control_groups = _build_tie_groups(control.order, control.score_by_action_id, eps)
    start_index = 0
    for group in control_groups:
        group_len = len(group)
        treatment_slice = treatment.order[start_index : start_index + group_len]
        if set(group) != set(treatment_slice):
            return False
        start_index += group_len
    return True


def compare_predict_jsonl(
    baseline_file: str, treatment_file: str, score_eps: float, allow_non_deterministic_tie_breaking: bool
) -> dict[str, Any]:
    if score_eps < 0:
        raise PredictionComparisonInputError("score_eps must be >= 0")

    mismatches: list[dict[str, Any]] = []
    processed_lines = 0
    line_count_mismatch = False
    score_mismatch_count = 0
    rank_mismatch_count = 0
    max_abs_delta = 0.0

    with open(baseline_file) as baseline, open(treatment_file) as treatment:
        for line_number, (baseline_line, treatment_line) in enumerate(zip_longest(baseline, treatment), start=1):
            if baseline_line is None or treatment_line is None:
                line_count_mismatch = True
                mismatches.append(
                    {
                        "line": line_number,
                        "type": "line_count_mismatch",
                        "baseline_missing": baseline_line is None,
                        "treatment_missing": treatment_line is None,
                    }
                )
                continue

            processed_lines += 1

            try:
                baseline_payload = json.loads(baseline_line)
            except json.JSONDecodeError as err:
                raise PredictionComparisonInputError(
                    f"line {line_number}: invalid JSON in baseline: {err.msg}"
                ) from err
            try:
                treatment_payload = json.loads(treatment_line)
            except json.JSONDecodeError as err:
                raise PredictionComparisonInputError(
                    f"line {line_number}: invalid JSON in treatment: {err.msg}"
                ) from err

            baseline_record = _parse_record(baseline_payload, line_number, "baseline")
            treatment_record = _parse_record(treatment_payload, line_number, "treatment")

            if baseline_record.example_id != treatment_record.example_id:
                mismatches.append(
                    {
                        "line": line_number,
                        "type": "example_id_mismatch",
                        "control_example_id": baseline_record.example_id,
                        "treatment_example_id": treatment_record.example_id,
                    }
                )
                continue

            baseline_action_ids = set(baseline_record.score_by_action_id)
            treatment_action_ids = set(treatment_record.score_by_action_id)
            if baseline_action_ids != treatment_action_ids:
                score_mismatch_count += 1
                rank_mismatch_count += 1
                mismatches.append(
                    {
                        "line": line_number,
                        "type": "action_id_set_mismatch",
                        "example_id": baseline_record.example_id,
                        "missing_in_treatment": sorted(baseline_action_ids - treatment_action_ids),
                        "missing_in_control": sorted(treatment_action_ids - baseline_action_ids),
                    }
                )
                continue

            for action_id in sorted(baseline_action_ids):
                baseline_score = baseline_record.score_by_action_id[action_id]
                treatment_score = treatment_record.score_by_action_id[action_id]
                abs_delta = abs(baseline_score - treatment_score)
                max_abs_delta = max(max_abs_delta, abs_delta)
                if abs_delta > score_eps:
                    score_mismatch_count += 1
                    mismatches.append(
                        {
                            "line": line_number,
                            "type": "score_mismatch",
                            "example_id": baseline_record.example_id,
                            "action_id": action_id,
                            "control_score": baseline_score,
                            "treatment_score": treatment_score,
                            "abs_delta": abs_delta,
                            "eps": score_eps,
                        }
                    )

            if allow_non_deterministic_tie_breaking:
                rank_passed = _rank_equivalent_with_tie_breaking(baseline_record, treatment_record, score_eps)
                if not rank_passed:
                    rank_mismatch_count += 1
                    mismatches.append(
                        {
                            "line": line_number,
                            "type": "rank_order_mismatch",
                            "example_id": baseline_record.example_id,
                            "control_order": baseline_record.order,
                            "treatment_order": treatment_record.order,
                            "mode": "allow_non_deterministic_tie_breaking",
                        }
                    )
            else:
                if baseline_record.order != treatment_record.order:
                    rank_mismatch_count += 1
                    mismatches.append(
                        {
                            "line": line_number,
                            "type": "rank_order_mismatch",
                            "example_id": baseline_record.example_id,
                            "control_order": baseline_record.order,
                            "treatment_order": treatment_record.order,
                            "mode": "strict",
                        }
                    )

    status = "passed"
    if line_count_mismatch or score_mismatch_count > 0 or rank_mismatch_count > 0 or len(mismatches) > 0:
        status = "failed"

    return {
        "status": status,
        "processed_lines": processed_lines,
        "line_count_mismatch": line_count_mismatch,
        "score": {
            "passed": score_mismatch_count == 0,
            "eps": score_eps,
            "mismatch_count": score_mismatch_count,
            "max_abs_delta": max_abs_delta,
        },
        "rank": {
            "passed": rank_mismatch_count == 0,
            "allow_non_deterministic_tie_breaking": allow_non_deterministic_tie_breaking,
            "mismatch_count": rank_mismatch_count,
        },
        "mismatches": mismatches,
    }


def _error_payload(
    message: str, error_code: str = "invalid_input", processed_lines: int | None = None
) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "status": "error",
        "error_code": error_code,
        "message": message,
    }
    if processed_lines is not None:
        payload["processed_lines"] = processed_lines
    return payload


class ComparePredictionsCommand:
    """Compare two predict JSONL files for score and rank equivalence."""

    @staticmethod
    def add_arguments(parser):
        parser.add_argument("baseline_file", help="Path to baseline predict JSONL file")
        parser.add_argument("treatment_file", help="Path to treatment predict JSONL file")
        parser.add_argument(
            "--score-eps",
            type=float,
            default=1e-6,
            help="Absolute tolerance for score equivalence checks (default: 1e-6)",
        )
        parser.add_argument(
            "--allow-non-deterministic-tie-breaking",
            action="store_true",
            help="Allow order permutations within tied score groups (tie detection uses --score-eps)",
        )
        parser.add_argument(
            "--output",
            help="Optional output directory for artifacts (writes comparison.json)",
        )

    def execute(self, args):
        try:
            if not os.path.exists(args.baseline_file):
                print(json.dumps(_error_payload(f"File not found: {args.baseline_file}"), indent=2))
                sys.exit(2)
            if not os.path.exists(args.treatment_file):
                print(json.dumps(_error_payload(f"File not found: {args.treatment_file}"), indent=2))
                sys.exit(2)
            if args.score_eps < 0 or not math.isfinite(args.score_eps):
                print(json.dumps(_error_payload("--score-eps must be a finite number >= 0"), indent=2))
                sys.exit(2)

            result = compare_predict_jsonl(
                baseline_file=args.baseline_file,
                treatment_file=args.treatment_file,
                score_eps=args.score_eps,
                allow_non_deterministic_tie_breaking=args.allow_non_deterministic_tie_breaking,
            )

            if args.output:
                output_path = Path(args.output)
                output_path.mkdir(parents=True, exist_ok=True)
                (output_path / "comparison.json").write_text(json.dumps(result, indent=2, sort_keys=True))

            print(json.dumps(result, indent=2))

            if result["status"] == "passed":
                return
            sys.exit(1)
        except PredictionComparisonInputError as err:
            print(json.dumps(_error_payload(str(err)), indent=2))
            sys.exit(2)
