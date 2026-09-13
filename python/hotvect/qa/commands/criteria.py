"""Evaluation criteria subcommands for hv-qa CLI."""

from __future__ import annotations

from hotvect.evaluation_criteria.commands import CriteriaCommandMixin

from .base import BaseCommand


class CriteriaCommand(CriteriaCommandMixin, BaseCommand):
    """Top-level criteria command wrapper."""

    prog_name = "hv-qa"


class CanonicalCriteriaCommand(CriteriaCommandMixin, BaseCommand):
    """Criteria discovery commands in the unified hv QA namespace."""

    prog_name = "hv qa"
