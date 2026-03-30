from __future__ import annotations

import json
from datetime import date
from types import SimpleNamespace

from hotvect.sagemaker import ALGO_PIPELINE_HYPERPARAMETER_PREFIX, SagemakerTrainingExecutor


def test_sagemaker_pipeline_params_include_encode_and_audit_flags() -> None:
    def _eval(_: str) -> dict:
        return {"ok": True}

    executor = SagemakerTrainingExecutor.__new__(SagemakerTrainingExecutor)
    executor.training_job_definition = {"HyperParameters": {}}
    executor.algorithm_pipeline = SimpleNamespace(
        last_test_time=date(2026, 1, 1),
        evaluation_function=_eval,
        parameter_version="pv",
        execute_performance_test=False,
        encode_test_data=True,
        execute_audit=True,
    )

    executor._add_algorithm_pipeline_params_as_hyperparameters()

    payload = json.loads(executor.hyperparameters[ALGO_PIPELINE_HYPERPARAMETER_PREFIX])
    assert "evaluation_func" not in payload
    assert payload["execute_performance_test"] is False
    assert payload["encode_test_data"] is True
    assert payload["execute_audit"] is True
