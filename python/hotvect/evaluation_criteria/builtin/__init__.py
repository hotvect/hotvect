"""Built-in evaluation criteria policies shipped with Hotvect."""

from __future__ import annotations

from importlib import import_module

from hotvect.evaluation_criteria.base import CriteriaPolicy

_BUILTIN_MODULES = {
    "exact": "hotvect.evaluation_criteria.builtin.exact",
    "noninferiority": "hotvect.evaluation_criteria.builtin.noninferiority",
    "superiority": "hotvect.evaluation_criteria.builtin.superiority",
}


def list_builtin_criteria_ids() -> list[str]:
    return sorted(_BUILTIN_MODULES)


def load_builtin_policy(criteria_id: str) -> CriteriaPolicy:
    try:
        module_name = _BUILTIN_MODULES[criteria_id]
    except KeyError as exc:
        raise KeyError(
            f"Unknown built-in criteria '{criteria_id}'. Available built-ins: {sorted(_BUILTIN_MODULES)}"
        ) from exc
    module = import_module(module_name)
    return module.POLICY
