"""Base command class for hv-extra CLI commands."""

from abc import ABC, abstractmethod


class BaseCommand(ABC):
    """Abstract base class for hv-extra commands."""

    @classmethod
    @abstractmethod
    def register_parser(cls, subparsers):
        """
        Register command-specific arguments with the subparser.

        Args:
            subparsers: The subparsers object from argparse

        Returns:
            The created subparser for this command
        """
        pass

    @abstractmethod
    def execute(self, args):
        """
        Execute the command with the given arguments.

        Args:
            args: Parsed command line arguments
        """
        pass
