"""Built-in QA non-inferiority criteria."""

from hotvect.evaluation_criteria.base import CriteriaManifest, CriteriaPolicy
from hotvect.evaluation_criteria.builtin.noninferiority.policy import get_stage_handlers

MANIFEST = CriteriaManifest(
    name="noninferiority",
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
        "QA non-inferiority validation: treatment must not be materially worse than control under paired "
        "statistical quality and system regression checks."
    ),
)

POLICY = CriteriaPolicy(MANIFEST, get_stage_handlers())

__all__ = ["MANIFEST", "POLICY"]
