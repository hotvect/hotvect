"""Tests for catboost-convert command."""

import argparse
import json
import os
import tempfile
import unittest
from unittest.mock import MagicMock, patch

from hotvect.extra.commands.catboost_convert import CatBoostConvertCommand


class TestCatBoostConvertCommand(unittest.TestCase):
    """Test cases for CatBoostConvertCommand."""

    def setUp(self):
        """Set up test fixtures."""
        self.command = CatBoostConvertCommand()

        # Sample schema content
        self.schema_content = """0	Num	feature_0
1	Categ	category_feature
2	Text	text_feature
3	NumVector	vector_feature
4	Auxiliary	auxiliary_data"""

        # Sample encoded data
        self.encoded_content = """1.5	category_a	hello world	1.0;2.0;3.0	aux_data_1
2.0	category_b	foo bar baz	4.0;5.0	aux_data_2
0.5	category_a	single	NaN	aux_data_3"""

    def test_register_parser(self):
        """Test that register_parser creates proper argument parser."""
        main_parser = argparse.ArgumentParser()
        subparsers = main_parser.add_subparsers(dest="command")

        CatBoostConvertCommand.register_parser(subparsers)

        # Test required arguments
        args = main_parser.parse_args(
            [
                "catboost-convert",
                "--schema-file",
                "schema.txt",
                "--encoded-file",
                "data.tsv",
                "--output",
                "output.jsonl",
            ]
        )
        self.assertEqual(args.command, "catboost-convert")
        self.assertEqual(args.schema_file, "schema.txt")
        self.assertEqual(args.encoded_file, "data.tsv")
        self.assertEqual(args.output, "output.jsonl")

        # Test short arguments
        args = main_parser.parse_args(["catboost-convert", "-s", "schema.txt", "-e", "data.tsv", "-o", "output.jsonl"])
        self.assertEqual(args.schema_file, "schema.txt")
        self.assertEqual(args.encoded_file, "data.tsv")
        self.assertEqual(args.output, "output.jsonl")

    def test_parse_schema_valid(self):
        """Test parsing of valid schema file."""
        with tempfile.NamedTemporaryFile(mode="w", suffix=".txt", delete=False) as schema_file:
            schema_file.write(self.schema_content)
            schema_path = schema_file.name

        try:
            schema = self.command._parse_schema(schema_path)

            expected_schema = [
                {"column_index": "0", "dtype": "Num", "name": "feature_0"},
                {"column_index": "1", "dtype": "Categ", "name": "category_feature"},
                {"column_index": "2", "dtype": "Text", "name": "text_feature"},
                {"column_index": "3", "dtype": "NumVector", "name": "vector_feature"}
                # Note: Auxiliary type should be filtered out
            ]

            self.assertEqual(schema, expected_schema)

        finally:
            os.unlink(schema_path)

    def test_parse_schema_with_default_names(self):
        """Test parsing schema with default feature names."""
        schema_content = """0	Num
1	Categ
2	Text"""

        with tempfile.NamedTemporaryFile(mode="w", suffix=".txt", delete=False) as schema_file:
            schema_file.write(schema_content)
            schema_path = schema_file.name

        try:
            schema = self.command._parse_schema(schema_path)

            expected_schema = [
                {"column_index": "0", "dtype": "Num", "name": "feature_0"},
                {"column_index": "1", "dtype": "Categ", "name": "feature_1"},
                {"column_index": "2", "dtype": "Text", "name": "feature_2"},
            ]

            self.assertEqual(schema, expected_schema)

        finally:
            os.unlink(schema_path)

    def test_parse_schema_empty_file(self):
        """Test parsing empty schema file raises error."""
        with tempfile.NamedTemporaryFile(mode="w", suffix=".txt", delete=False) as schema_file:
            schema_file.write("# Only comments\n")
            schema_path = schema_file.name

        try:
            with self.assertRaises(ValueError) as cm:
                self.command._parse_schema(schema_path)

            self.assertIn("No valid schema entries found", str(cm.exception))

        finally:
            os.unlink(schema_path)

    def test_convert_to_json_all_types(self):
        """Test conversion of all supported data types."""
        schema = [
            {"column_index": "0", "dtype": "Num", "name": "numeric_feature"},
            {"column_index": "1", "dtype": "Categ", "name": "category_feature"},
            {"column_index": "2", "dtype": "Text", "name": "text_feature"},
            {"column_index": "3", "dtype": "NumVector", "name": "vector_feature"},
        ]

        with tempfile.NamedTemporaryFile(mode="w", suffix=".tsv", delete=False) as encoded_file:
            encoded_file.write(self.encoded_content)
            encoded_path = encoded_file.name

        try:
            data = self.command._convert_to_json(schema, encoded_path)

            expected_data = [
                {
                    "numeric_feature": 1.5,
                    "category_feature": "category_a",
                    "text_feature": ["hello", "world"],
                    "vector_feature": [1.0, 2.0, 3.0],
                },
                {
                    "numeric_feature": 2.0,
                    "category_feature": "category_b",
                    "text_feature": ["foo", "bar", "baz"],
                    "vector_feature": [4.0, 5.0],
                },
                {
                    "numeric_feature": 0.5,
                    "category_feature": "category_a",
                    "text_feature": ["single"],
                    "vector_feature": None,  # NaN should become None
                },
            ]

            self.assertEqual(data, expected_data)

        finally:
            os.unlink(encoded_path)

    def test_convert_to_json_invalid_numeric(self):
        """Test conversion with invalid numeric values."""
        schema = [{"column_index": "0", "dtype": "Num", "name": "numeric_feature"}]

        encoded_content = "invalid_number"

        with tempfile.NamedTemporaryFile(mode="w", suffix=".tsv", delete=False) as encoded_file:
            encoded_file.write(encoded_content)
            encoded_path = encoded_file.name

        try:
            data = self.command._convert_to_json(schema, encoded_path)

            # Invalid numeric should become None
            self.assertEqual(data[0]["numeric_feature"], None)

        finally:
            os.unlink(encoded_path)

    def test_convert_to_json_column_index_error(self):
        """Test conversion with invalid column index."""
        schema = [{"column_index": "5", "dtype": "Num", "name": "numeric_feature"}]  # Index out of range

        with tempfile.NamedTemporaryFile(mode="w", suffix=".tsv", delete=False) as encoded_file:
            encoded_file.write("1.0\t2.0")  # Only 2 columns, but schema expects index 5
            encoded_path = encoded_file.name

        try:
            with self.assertRaises(ValueError) as cm:
                self.command._convert_to_json(schema, encoded_path)

            self.assertIn("Column index 5 not found", str(cm.exception))
            self.assertIn("Error processing line 1", str(cm.exception))

        finally:
            os.unlink(encoded_path)

    def test_write_output_jsonl_format(self):
        """Test writing output in JSONL format."""
        data = [{"key": "value1"}, {"key": "value2"}]

        with tempfile.NamedTemporaryFile(mode="w", suffix=".jsonl", delete=False) as output_file:
            output_path = output_file.name

        try:
            self.command._write_output(data, output_path)

            with open(output_path, "r") as f:
                lines = f.readlines()

            self.assertEqual(len(lines), 2)
            self.assertEqual(json.loads(lines[0].strip()), {"key": "value1"})
            self.assertEqual(json.loads(lines[1].strip()), {"key": "value2"})

        finally:
            os.unlink(output_path)

    @patch("builtins.print")
    def test_execute_success_jsonl(self, mock_print):
        """Test successful execution with JSONL output."""
        with tempfile.NamedTemporaryFile(mode="w", suffix=".txt", delete=False) as schema_file:
            schema_file.write(self.schema_content)
            schema_path = schema_file.name

        with tempfile.NamedTemporaryFile(mode="w", suffix=".tsv", delete=False) as encoded_file:
            encoded_file.write(self.encoded_content)
            encoded_path = encoded_file.name

        with tempfile.NamedTemporaryFile(mode="w", suffix=".jsonl", delete=False) as output_file:
            output_path = output_file.name

        try:
            # Create mock args
            args = MagicMock()
            args.schema_file = schema_path
            args.encoded_file = encoded_path
            args.output = output_path

            # Execute command
            self.command.execute(args)

            # Check that output file was created with JSONL format
            with open(output_path, "r") as f:
                lines = f.readlines()

            self.assertEqual(len(lines), 3)  # 3 data rows
            # Each line should be valid JSON
            for line in lines:
                data = json.loads(line.strip())
                self.assertIn("feature_0", data)

            # Check success message
            mock_print.assert_called_once()
            success_message = mock_print.call_args[0][0]
            self.assertIn("Successfully converted 3 records", success_message)

        finally:
            # Clean up temp files
            for path in [schema_path, encoded_path, output_path]:
                if os.path.exists(path):
                    os.unlink(path)

    @patch("sys.exit")
    @patch("builtins.print")
    def test_execute_schema_file_not_found(self, mock_print, mock_exit):
        """Test execution with non-existent schema file."""
        args = MagicMock()
        args.schema_file = "nonexistent_schema.txt"
        args.encoded_file = "nonexistent_data.tsv"
        args.output = "output.jsonl"

        self.command.execute(args)

        # Check that error was printed and exit was called
        mock_print.assert_called_once()
        error_message = mock_print.call_args[0][0]
        self.assertIn("Error: File not found", error_message)
        mock_exit.assert_called_once_with(1)

    @patch("sys.exit")
    @patch("builtins.print")
    def test_execute_empty_schema(self, mock_print, mock_exit):
        """Test execution with empty schema file."""
        with tempfile.NamedTemporaryFile(mode="w", suffix=".txt", delete=False) as schema_file:
            schema_file.write("# Empty schema file\n")
            schema_path = schema_file.name

        try:
            args = MagicMock()
            args.schema_file = schema_path
            args.encoded_file = "data.tsv"
            args.output = "output.jsonl"

            self.command.execute(args)

            # Check that error was printed and exit was called
            mock_print.assert_called_once()
            error_message = mock_print.call_args[0][0]
            self.assertIn("Error:", error_message)
            self.assertIn("No valid schema entries found", error_message)
            mock_exit.assert_called_once_with(1)

        finally:
            os.unlink(schema_path)


if __name__ == "__main__":
    unittest.main()
