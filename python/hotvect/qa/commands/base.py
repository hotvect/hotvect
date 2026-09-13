"""Base command class for hv-qa CLI commands."""

from abc import ABC, abstractmethod


class BaseCommand(ABC):
    """Abstract base class for hv-qa commands."""

    @classmethod
    @abstractmethod
    def register_parser(cls, subparsers):
        """Register command-specific arguments with argparse."""

    @abstractmethod
    def execute(self, args):
        """Execute the command using parsed arguments."""
