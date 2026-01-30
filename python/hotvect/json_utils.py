import argparse
import json
import math
import os
from collections import Counter
from pathlib import Path
from typing import Any, Dict, List, Tuple

TYPE_CONSTRUCTORS = {
    "int": int,
    "str": str,
    "float": float,
    "bool": bool,
}


def is_complex_json_array(json_array):
    return any(isinstance(item, (dict, list)) for item in json_array)


def compare_complex_lists(input1: Tuple[str, List], input2: Tuple[str, List], config: Dict[str, Any] = None):
    name1, list1 = input1
    name2, list2 = input2
    differences = []
    for item1, item2 in zip(list1, list2):
        element_diff = compare_json((name1, item1), (name2, item2), config)
        if element_diff:
            differences.append(element_diff)
    return differences


def rename_keys(data, rename_map):
    if isinstance(data, dict):
        new_data = {}
        for key, value in data.items():
            new_key = rename_map.get(key, key)
            if isinstance(value, dict):
                new_data[new_key] = rename_keys(value, rename_map)
            elif isinstance(value, list):
                new_data[new_key] = [
                    rename_keys(item, rename_map) if isinstance(item, dict) else item for item in value
                ]
            else:
                new_data[new_key] = value
        return new_data
    elif isinstance(data, list):
        return [rename_keys(item, rename_map) if isinstance(item, dict) else item for item in data]
    return data


def attempt_type_coercion(value1, value2, allowed_coercions):
    type1 = type(value1).__name__
    type2 = type(value2).__name__
    allowed_pairs = set()
    for from_type, to_type in allowed_coercions.items():
        allowed_pairs.add((from_type, to_type))
        allowed_pairs.add((to_type, from_type))

    if (type1, type2) in allowed_pairs:
        if type2 in TYPE_CONSTRUCTORS:
            try:
                target_type = TYPE_CONSTRUCTORS[type2]
                coerced_value1 = target_type(value1)
                if coerced_value1 == value2:
                    return True
            except (ValueError, TypeError):
                pass
        if type1 in TYPE_CONSTRUCTORS:
            try:
                target_type = TYPE_CONSTRUCTORS[type1]
                coerced_value2 = target_type(value2)
                if value1 == coerced_value2:
                    return True
            except (ValueError, TypeError):
                pass
    return False


def compare_json(input1: Tuple[str, Any], input2: Tuple[str, Any], config: Dict[str, Any] = None):
    if config is None:
        config = {}
    name1, json1 = input1
    name2, json2 = input2

    if "rename" in config and isinstance(config["rename"], dict):
        json1 = rename_keys(json1, config["rename"])
        json2 = rename_keys(json2, config["rename"])

    if isinstance(json1, dict) and isinstance(json2, dict):
        differences = {}
        keys = set(json1.keys()).union(json2.keys())
        for key in keys:
            if key in json1 and key in json2:
                sub_diff = compare_json((name1, json1[key]), (name2, json2[key]), config)
                if sub_diff:
                    differences[key] = sub_diff
            else:
                differences[key] = {name1: json1.get(key), name2: json2.get(key)}
        return differences if differences else {}

    elif isinstance(json1, list) and isinstance(json2, list):
        if is_complex_json_array(json1) or is_complex_json_array(json2):
            complex_diff = compare_complex_lists((name1, json1), (name2, json2), config)
            if complex_diff:
                return complex_diff
            else:
                return {}
        else:
            counter1 = Counter(json1)
            counter2 = Counter(json2)
            diff_counter1 = counter1 - counter2
            diff_counter2 = counter2 - counter1
            if diff_counter1 or diff_counter2:
                return {
                    name1: [item for item, count in diff_counter1.items() for _ in range(count)],
                    name2: [item for item, count in diff_counter2.items() for _ in range(count)],
                }
            else:
                return {}

    else:
        if json1 != json2:
            if "allow_type_coercion" in config and isinstance(config["allow_type_coercion"], dict):
                allowed_coercions = config["allow_type_coercion"]
                if attempt_type_coercion(json1, json2, allowed_coercions):
                    return {}
            if isinstance(json1, (int, float)) and isinstance(json2, (int, float)):
                if math.isclose(float(json1), float(json2), rel_tol=1e-06, abs_tol=1e-06):
                    return {}
            return {name1: json1, name2: json2}
        return {}


def gather_difference_fields(differences, fields_set):
    if isinstance(differences, dict):
        for key, val in differences.items():
            fields_set.add(key)
            gather_difference_fields(val, fields_set)
    elif isinstance(differences, list):
        for item in differences:
            gather_difference_fields(item, fields_set)


def find_difference_in_files(file1, file2, output_dir, config_file):
    if not os.path.exists(output_dir):
        os.makedirs(output_dir)
    file1_name = Path(file1).stem
    file2_name = Path(file2).stem

    if config_file and os.path.isfile(config_file):
        with open(config_file, "r") as file:
            config_json = json.load(file)
    else:
        config_json = None

    differences_found = False
    fields_with_diff = set()
    processed_lines = 0
    identical_lines = 0
    different_lines = 0

    with open(file1, "r") as f1, open(file2, "r") as f2:
        for line_number, (line1, line2) in enumerate(zip(f1, f2), start=1):
            processed_lines += 1
            json1 = json.loads(line1)
            json2 = json.loads(line2)
            differences = compare_json((file1_name, json1), (file2_name, json2), config_json)
            if differences:
                different_lines += 1
                if not differences_found:
                    differences_found = True
                    output_suffix = f"line={line_number}"
                    with open(os.path.join(output_dir, f"{file1_name}.{output_suffix}.json"), "w") as out1:
                        json.dump(json1, out1, indent=2, sort_keys=True)
                    with open(os.path.join(output_dir, f"{file2_name}.{output_suffix}.json"), "w") as out2:
                        json.dump(json2, out2, indent=2, sort_keys=True)
                    with open(
                        os.path.join(output_dir, f"diff.{file1_name}-{file2_name}.{output_suffix}.json"), "w"
                    ) as diff_file:
                        json.dump(differences, diff_file, indent=2, sort_keys=True)
                gather_difference_fields(differences, fields_with_diff)
            else:
                identical_lines += 1

    if differences_found:
        stdout_output = {
            "message": "Differences found",
            "processed_lines": processed_lines,
            "identical_lines": identical_lines,
            "different_lines": different_lines,
            "fields_with_difference": sorted(fields_with_diff),
        }
        return json.dumps(stdout_output, indent=2)
    else:
        return '{ "message": "The two files are identical" }'


def main():
    parser = argparse.ArgumentParser(description="Compare two JSONL files.")
    parser.add_argument("file1", help="Path to the first JSONL file")
    parser.add_argument("file2", help="Path to the second JSONL file")
    parser.add_argument("-o", "--output", default=".", help="Output directory for the result files")
    parser.add_argument("-c", "--config", required=False, help="Path to the comparison configuration file")
    args = parser.parse_args()
    find_difference_in_files(args.file1, args.file2, args.output, args.config)


if __name__ == "__main__":
    main()
