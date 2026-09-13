"""Built-in QA superiority criteria."""

from hotvect.evaluation_criteria.base import CriteriaManifest, CriteriaPolicy
from hotvect.evaluation_criteria.builtin.superiority.policy import get_stage_handlers

MANIFEST = CriteriaManifest(
    name="superiority",
    scenario="qa",
    supported_stages=(
        "audit_parity",
        "encode_parity",
        "system_performance",
        "predict_parity",
        "realistic_single_day",
        "multi_day_backtest",
    ),
    description=(
        "QA superiority validation: treatment must pass safety gates and show a statistically "
        "supported offline quality gain."
    ),
)

POLICY = CriteriaPolicy(MANIFEST, get_stage_handlers())

__all__ = ["MANIFEST", "POLICY"]
