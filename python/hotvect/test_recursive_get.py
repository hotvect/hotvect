from hotvect.pyhotvect import _recursive_get


def test_recursive_get_simple_nested_lookup():
    # Test Case 1: Simple nested dictionary lookup
    d1 = {"a": {"b": {"c": 42}}}
    keys1 = ["a", "b", "c"]
    assert _recursive_get(d1, keys1) == 42


def test_recursive_get_missing_intermediate_key():
    # Test Case 2: Missing intermediate key
    d2 = {"a": {"b": {"c": 42}}}
    keys2 = ["a", "x", "c"]
    assert _recursive_get(d2, keys2, default="Not Found") == "Not Found"


def test_recursive_get_missing_final_key():
    # Test Case 3: Missing final key
    d2 = {"a": {"b": {"c": 42}}}
    keys3 = ["a", "b", "x"]
    assert _recursive_get(d2, keys3, default="Not Found") == "Not Found"


def test_recursive_get_empty_keys_list():
    # Test Case 4: Empty keys list should return the original dictionary
    d2 = {"a": {"b": {"c": 42}}}
    keys4 = []
    assert _recursive_get(d2, keys4) == d2


def test_recursive_get_empty_dict_with_keys():
    # Test Case 5: Empty dictionary with non-empty keys should return default
    d5 = {}
    keys5 = ["a"]
    assert _recursive_get(d5, keys5, default="Not Found") == "Not Found"


def test_recursive_get_none_dictionary():
    # Test Case 6: None dictionary should return default
    d6 = None
    keys6 = ["a"]
    assert _recursive_get(d6, keys6, default="Not Found") == "Not Found"


def test_recursive_get_none_keys():
    # Test Case 7: keys list is None should return the default
    d7 = {"a": {"b": {"c": 42}}}
    keys7 = None
    assert _recursive_get(d7, keys7, default="Not Found") == "Not Found"


def test_recursive_get_none_value_in_path():
    # Test Case 8: Value is None at some point in the path
    d8 = {"a": {"b": None}}
    keys8 = ["a", "b", "c"]
    assert _recursive_get(d8, keys8, default="Not Found") == "Not Found"


def test_recursive_get_intermediate_value_not_dict():
    # Test Case 9: Intermediate value is not a dictionary
    d9 = {"a": {"b": 42}}
    keys9 = ["a", "b", "c"]
    assert _recursive_get(d9, keys9, default="Not Found") == "Not Found"


def test_recursive_get_single_existing_key():
    # Test Case 10: Single key that exists
    d10 = {"a": 1}
    keys10 = ["a"]
    assert _recursive_get(d10, keys10) == 1


def test_recursive_get_single_missing_key():
    # Test Case 11: Single key that does not exist
    d10 = {"a": 1}
    keys11 = ["b"]
    assert _recursive_get(d10, keys11, default="Not Found") == "Not Found"


def test_recursive_get_complex_final_value():
    # Test Case 12: Multiple keys with the final value being a complex object
    d12 = {"a": {"b": {"c": [1, 2, 3]}}}
    keys12 = ["a", "b", "c"]
    assert _recursive_get(d12, keys12) == [1, 2, 3]


def test_recursive_get_value_before_keys_exhausted():
    # Test Case 13: Keys lead to a non-dictionary, non-None value before keys are exhausted
    d13 = {"a": {"b": 42}}
    keys13 = ["a", "b", "c"]
    assert _recursive_get(d13, keys13, default="Not Found") == "Not Found"


def test_recursive_get_false_value():
    # Test Case 14: Dictionary contains False values
    d14 = {"a": {"b": False}}
    keys14 = ["a", "b"]
    # Since False is a valid value and not None, should return False
    assert _recursive_get(d14, keys14) is False


def test_recursive_get_empty_dictionary_in_path():
    # Test Case 15: Dictionary contains empty dictionaries
    d15 = {"a": {"b": {}}}
    keys15 = ["a", "b"]
    assert _recursive_get(d15, keys15) == {}


def test_recursive_get_zero_value():
    # Test Case 16: Dictionary contains zeros (0)
    d16 = {"a": {"b": 0}}
    keys16 = ["a", "b"]
    # Since 0 is a valid value and not None, should return 0
    assert _recursive_get(d16, keys16) == 0


def test_recursive_get_keys_exceeding_depth():
    # Test Case 17: Keys list longer than the depth of the dictionary
    d17 = {"a": {"b": {"c": 42}}}
    keys17 = ["a", "b", "c", "d"]
    assert _recursive_get(d17, keys17, default="Not Found") == "Not Found"


def test_recursive_get_non_string_keys():
    # Test Case 18: Non-string keys
    d18 = {1: {2: {3: "value"}}}
    keys18 = [1, 2, 3]
    assert _recursive_get(d18, keys18) == "value"


def test_recursive_get_mixed_type_keys():
    # Test Case 19: Mixed-type keys
    d19 = {"a": {2: {"c": 42}}}
    keys19 = ["a", 2, "c"]
    assert _recursive_get(d19, keys19) == 42


def test_recursive_get_no_default_provided():
    # Test Case 20: Default parameter is not provided, should return None when key not found
    d20 = {"a": {"b": {"c": 42}}}
    keys20 = ["a", "x", "y"]
    assert _recursive_get(d20, keys20) is None
