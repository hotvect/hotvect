---
title: How to Dump Available Feature Transformations
description: Extract metadata about all feature transformations for analysis and feature selection
tags: [features, transformations, metadata, feature-selection, analysis]
difficulty: beginner
estimated_time: 5 minutes
prerequisites:
  - Algorithm JAR built and available
  - hotvect CLI installed
  - jq installed (for filtering output)
related_docs:
  - ../develop-algorithms/index.md
  - ../debug-feature-engineering/index.md
  - ../../concepts/index.md
related_commands:
  - hv list-transformations
next_steps:
  - Analyze feature dependencies
  - Perform feature selection
  - Optimize caching strategy
---

# How to: Dump available feature transformations metadata

## What do you mean with feature transformations metadata?
When you use hotvect's memoization feature, each feature extraction is defined as a DAG (Directed Acyclic Graph). Each node in the DAG is a "transformation" that can be cached (memoized), and reused in other feature extraction DAGs. The last node (the transformation that yields the final feature) is also a "transformation", although a special one ("feature transformation"). A transformation can yield any arbitrary java object, but a feature transformation must yield a return type that is supported by the ML library in use (in case of CatBoost, it can be `RawValue`, `String`, `int`, `double`, `String[]`, and `double[]`).

Here are examples of transformation metadata:
```python
{
    # The name of the feature (or transformation)
    # A namespace can contain multiple features within (for example if it's an embedding or text feature etc.)
    "namespace": {
        # List of enums that compose the namespace.
        "namespaces": [
            "event_a",
            "attribute_b",
            "metric_count"
        ],
        # Type of the feature. Possible values vary across ML libraries.
        # For CatBoost, possible values are CATEGORICAL, NUMERICAL, TEXT, EMBEDDING and GROUP_ID
        "feature_value_type": "NUMERICAL",
        # Name of the feature (concatenation of its components)
        "name": "event_a_attribute_b_metric_count"
    },
    # List of namespace components (enums) (repeated for convenience)
    "namespace_components": [
        "event_a",
        "attribute_b",
        "metric_count"
    ],
    # List of classes in which the namespace components enums are defined.
    "namespace_component_classes": [
        "com.example.CustomerActionType",
        "com.example.CustomerActionAttribute",
        "com.example.AggregationType"
    ],
    # The input on which this transformation depends.
    # SHARED: Depends only on shared data (like a user's profile)
    # ACTION: Depends only on the action data (like the price of an article)
    # INTERACTION: Depends on both shared and action data
    # The dependency determines the scope of the cache (e.g. if it's shared, it can be cached for all actions)
    "computation_dependency": "INTERACTION",

    # The name of the method used as the transformation.
    # This is retrieved via a hack, and hence is not always available.
    # Also not available if the transformation was defined dynamically (e.g. through function compositions)
    "bound_method_name": "Unknown",

    # The return type of the transformation.
    # This is retrieved via a hack, and hence not always available.
    # In this case it is of type `RawValue`
    "return_type": "com.hotvect.api.data.RawValue",

    # Indicates if the transformation is enabled as a feature according to the effective algorithm definition.
    "enabled_as_feature": False,

    # Indicates if caching is enabled for this transformation.
    # Caching is not always beneficial, especially with ACTION and INTERACTION scoped transformations.
    "cache_enabled": False,

    # Indicates if this transformation can be used as a feature.
    # For a transformation to be usable as a feature, its namespace must be of type `FeatureNamespace`, and
    # the return type must be supported by the ML library used.
    "can_be_feature": True
}
```

Here is another example (this time a non feature transformation):
```python
{
    "namespace": {
        "namespaces": [
            "event_a",
            "item_id"
        ],
        "name": "event_a_item_id"
    },
    "namespace_components": [
        "event_a",
        "item_id"
    ],
    "namespace_component_classes": [
        "com.example.CustomerActionType",
        "com.example.CustomerActionAttribute"
    ],
    "computation_dependency": "SHARED",
    "bound_method_name": "Unknown",
    "return_type": "java.lang.String",
    "enabled_as_feature": False,
    "cache_enabled": True,
    "can_be_feature": False
}
```


## Why do I want to dump feature transformations metadata?
Feature transformations can be declared programmatically, in which case there can be hundreds or even thousands of available transformations. This makes it very difficult to have an overview. Also, the metadata can be used to perform feature selection.

## How to dump feature transformations metadata
Run the following command:
```bash
hv list-transformations --algorithm-jar <your algorithm jar path> --algorithm-name <the name of the algorithm to dump the features for>
```

This will create the following files:

 - `<algorithm-name>.list-transformations.metadata/metadata.json`
 - `<algorithm-name>.list-transformations.metadata/hotvect-offline-utils.log`
 - `<algorithm-name>.list-transformations.metadata/hv.log`
 - `<algorithm-name>.list-transformations.metadata/stdout-stderr.log`
 - `<algorithm-name>.list-transformations.output`

You can also specify `--metadata-path` and `--dest-path` to control where the metadata directory and output directory are written.

## Output formats

By default, `list-transformations` produces a compact format with essential metadata:
```json
{
  "transformations": [
    {
      "name": "feature_numeric_01",
      "return_type_hint": "double",
      "feature_value_type": "NUMERICAL"
    },
    {
      "name": "feature_text_01",
      "return_type_hint": "[Ljava.lang.String;",
      "feature_value_type": "TEXT"
    }
  ]
}
```

For detailed metadata including computation dependencies, caching, and method names, use the `--verbose` flag. The verbose output is a JSON array and includes all fields shown in the examples above.

## Filtering and analyzing the output

The output JSON is not designed to be read by a human - use commands like `jq` to filter and format data.

For example, to list all transformations of NUMERICAL type (default/compact output):
```bash
jq '.transformations[] | select(.feature_value_type == "NUMERICAL")' <algorithm-name>.list-transformations.output
```

For verbose output, you can filter by namespace components and other metadata fields:
```bash
hv list-transformations --algorithm-jar <jar> --algorithm-name <name> --verbose
jq '[.[] | select(.can_be_feature == true and .namespace_components[0] == "event_a")]' <algorithm-name>.list-transformations.output
```
