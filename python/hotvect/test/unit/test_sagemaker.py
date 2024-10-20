import json

import pytest

from hotvect.sagemaker import _parse_key, _ParsedKey, flatten_dict, unflatten_dict


def _try_to_parse_as_json(value):
    """Imitates the process done on SageMaker when the hyperparameters are read.

    Useful to test that the operations are inverses after the transformations of SageMaker.
    """
    try:
        return json.loads(value)
    except (ValueError, TypeError):
        return value


def test_flatten_dict():
    input_dict = {
        "key_1": "value_1",
        "key_2": {
            "sub_key_1": "sub_value_1",
            "sub_key_2": ["sub_value_list_1", "sub_value_list_2", "sub_value_list_3"],
            "sub_key_3": [1, 2, 3],
        },
    }
    expected_dict = {
        "prefix_key_1": '"value_1"',
        "prefix_key_2.sub_key_1": '"sub_value_1"',
        "prefix_key_2.sub_key_2[0]": '"sub_value_list_1"',
        "prefix_key_2.sub_key_2[1]": '"sub_value_list_2"',
        "prefix_key_2.sub_key_2[2]": '"sub_value_list_3"',
        "prefix_key_2.sub_key_3[0]": "1",
        "prefix_key_2.sub_key_3[1]": "2",
        "prefix_key_2.sub_key_3[2]": "3",
    }
    assert flatten_dict(input_dict, prefix="prefix_") == expected_dict


def test_unflatten_dict():
    input_dict = {
        "string_key": "value_1",
        "nested_object.sub_key_1": "sub_value_1",
        "nested_object.sub_key_2": "sub_value_2",
        "simple_array[1]": "second_element",
        "simple_array[0]": "first_element",
        "complex_array[1].sub_key_1": "second_element_sub_value_1",
        "complex_array[1].sub_key_2": "second_element_sub_value_2",
        "complex_array[0].sub_key_1": "first_element_sub_value_1",
        "complex_array[0].sub_key_2": "first_element_sub_value_2",
        "very_nested_array.sub_key_1.sub_key_2.sub_key_3": "very_nested_value",
        "nested_array[0][0]": "nested_array_value",
    }
    expected = {
        "string_key": "value_1",
        "nested_object": {"sub_key_1": "sub_value_1", "sub_key_2": "sub_value_2"},
        "simple_array": ["first_element", "second_element"],
        "complex_array": [
            {"sub_key_1": "first_element_sub_value_1", "sub_key_2": "first_element_sub_value_2"},
            {"sub_key_1": "second_element_sub_value_1", "sub_key_2": "second_element_sub_value_2"},
        ],
        "very_nested_array": {"sub_key_1": {"sub_key_2": {"sub_key_3": "very_nested_value"}}},
        "nested_array": [["nested_array_value"]],
    }
    assert unflatten_dict(input_dict, prefix="") == expected


def test_flatten_and_unflatten_should_be_inverses():
    dictionary = {
        "simple_key": "simple_value",
        "object_key": {
            "inner_key_1": "inner_value_1",
            "inner_key_2": "inner_value_2",
        },
        "nested_object": {
            "inner_object": {
                "key_1": "value_1",
                "key_2": "value_2",
            }
        },
        "simple_list": ["1", "2", "3"],
        "simple_list_of_numbers": [1, 2, 3],
        "list_of_lists": [["sublist_1", "sublist_2"], ["sublist_3", "sublist_4"]],
        "list_of_objects": [
            {"list_object_1": "list_object_value_1"},
            {"list_object_2": "list_object_value_2"},
        ],
    }
    flattened = flatten_dict(dictionary, prefix="")
    flattened_parsed_as_sagemaker = {k: _try_to_parse_as_json(v) for k, v in flattened.items()}
    assert unflatten_dict(flattened_parsed_as_sagemaker, prefix="") == dictionary


@pytest.mark.parametrize(
    "input_key,expected",
    [
        ("[0][0]", _ParsedKey("0", "[0]", "list")),
        ("key[0]", _ParsedKey("key", "[0]", "list")),
        ("key[0].sub_key", _ParsedKey("key", "[0].sub_key", "list")),
        ("key", _ParsedKey("key", "", "string")),
        ("key.sub_key", _ParsedKey("key", "sub_key", "object")),
        ("[0]", _ParsedKey("0", "", "string")),
        ("[0].sub_key", _ParsedKey("0", "sub_key", "object")),
    ],
)
def test_parse_key(input_key, expected):
    assert _parse_key(input_key, object_separator_char=".") == expected
