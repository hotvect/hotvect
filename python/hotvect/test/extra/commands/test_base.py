"""Tests for base command class."""

import argparse
import unittest

from hotvect.extra.commands.base import BaseCommand


class TestBaseCommand(unittest.TestCase):
    """Test cases for BaseCommand abstract class."""

    def test_base_command_is_abstract(self):
        """Test that BaseCommand cannot be instantiated directly."""
        with self.assertRaises(TypeError):
            BaseCommand()

    def test_subclass_must_implement_register_parser(self):
        """Test that subclasses must implement register_parser method."""

        class IncompleteCommand(BaseCommand):
            def execute(self, args):
                pass

        with self.assertRaises(TypeError):
            IncompleteCommand()

    def test_subclass_must_implement_execute(self):
        """Test that subclasses must implement execute method."""

        class IncompleteCommand(BaseCommand):
            @classmethod
            def register_parser(cls, subparsers):
                pass

        with self.assertRaises(TypeError):
            IncompleteCommand()

    def test_complete_subclass_can_be_instantiated(self):
        """Test that complete subclasses can be instantiated."""

        class CompleteCommand(BaseCommand):
            @classmethod
            def register_parser(cls, subparsers):
                return subparsers.add_parser("test")

            def execute(self, args):
                return "executed"

        # Should not raise any exception
        command = CompleteCommand()
        self.assertIsInstance(command, BaseCommand)
        self.assertEqual(command.execute(None), "executed")

    def test_register_parser_integration(self):
        """Test that register_parser works with argparse."""

        class TestCommand(BaseCommand):
            @classmethod
            def register_parser(cls, subparsers):
                parser = subparsers.add_parser("test", help="Test command")
                parser.add_argument("--flag", action="store_true")
                return parser

            def execute(self, args):
                return args.flag

        # Create parser and register command
        main_parser = argparse.ArgumentParser()
        subparsers = main_parser.add_subparsers(dest="command")
        TestCommand.register_parser(subparsers)

        # Test parsing
        args = main_parser.parse_args(["test", "--flag"])
        self.assertEqual(args.command, "test")
        self.assertTrue(args.flag)

        # Test command execution
        command = TestCommand()
        result = command.execute(args)
        self.assertTrue(result)


if __name__ == "__main__":
    unittest.main()
