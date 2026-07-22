"""CatBoost TSV to JSONL conversion command for hv-ext CLI."""

import json
import sys
from typing import Any

from .base import BaseCommand


class CatBoostConvertCommand(BaseCommand):
    """Convert CatBoost encoded TSV data to JSONL format."""

    @classmethod
    def register_parser(cls, subparsers):
        """Register the catboost-convert command parser."""
        parser = subparsers.add_parser("catboost-convert", help="Convert CatBoost encoded TSV data to JSONL format")
        parser.add_argument("--schema-file", "-s", required=True, help="Path to schema file defining column types")
        parser.add_argument("--encoded-file", "-e", required=True, help="Path to encoded TSV file")
        parser.add_argument("--output", "-o", required=True, help="Output JSONL file path")
        return parser

    def execute(self, args):
        """Execute the CatBoost conversion."""
        try:
            schema = self._parse_schema(args.schema_file)
            data = self._convert_to_json(schema, args.encoded_file)
            self._write_output(data, args.output)
            print(f"Successfully converted {len(data)} records to {args.output}")

        except FileNotFoundError as e:
            print(f"Error: File not found - {e}", file=sys.stderr)
            sys.exit(1)
        except Exception as e:
            print(f"Error: {e}", file=sys.stderr)
            sys.exit(1)

    def _parse_schema(self, schema_file: str) -> list[dict[str, str]]:
        """Parse schema file to extract column definitions."""
        schema = []
        allowed_types = {"Num", "Categ", "Text", "NumVector"}

        with open(schema_file) as f:
            for line in f:
                parts = line.strip().split("\t")
                if len(parts) >= 2 and parts[1] in allowed_types:
                    column_index, dtype = parts[:2]
                    name = parts[2] if len(parts) > 2 else f"feature_{column_index}"
                    schema.append({"column_index": column_index, "dtype": dtype, "name": name})
                # Skip unsupported or auxiliary data types

        if not schema:
            raise ValueError("No valid schema entries found. Check schema file format.")

        return schema

    def _convert_to_json(self, schema: list[dict[str, str]], encoded_file: str) -> list[dict[str, Any]]:
        """Convert encoded TSV data to JSON using the schema."""
        data = []

        with open(encoded_file) as ef:
            for line_num, line in enumerate(ef, 1):
                try:
                    values = line.strip().split("\t")
                    row = {}

                    for feature in schema:
                        idx = int(feature["column_index"])
                        if idx >= len(values):
                            raise ValueError(f"Column index {idx} not found in data row")

                        value = values[idx]

                        if feature["dtype"] == "Num":
                            try:
                                row[feature["name"]] = float(value)
                            except ValueError:
                                row[feature["name"]] = None  # Handle non-numeric values
                        elif feature["dtype"] == "Categ":
                            row[feature["name"]] = value
                        elif feature["dtype"] == "Text":
                            row[feature["name"]] = value.split()
                        elif feature["dtype"] == "NumVector":
                            # Handle NaN and split by ';' into an array of floats
                            if value == "NaN":
                                row[feature["name"]] = None
                            else:
                                try:
                                    row[feature["name"]] = [float(x) for x in value.split(";") if x]
                                except ValueError:
                                    row[feature["name"]] = None
                        else:
                            raise ValueError(f"Unrecognized feature type: {feature['dtype']}")

                    data.append(row)

                except Exception as e:
                    raise ValueError(f"Error processing line {line_num}: {e}")

        return data

    def _write_output(self, data: list[dict[str, Any]], output_file: str):
        """Write data to output file as JSONL."""
        with open(output_file, "w") as f:
            for item in data:
                f.write(json.dumps(item) + "\n")
