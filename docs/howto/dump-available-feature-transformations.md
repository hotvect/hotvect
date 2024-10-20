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
            "viewed",
            "category_code",
            "match_count"
        ],
        # Type of the feature. Possible values vary across ML libraries.
        # For CatBoost, possible values are CATEGORICAL, NUMERICAL, TEXT, EMBEDDING and GROUP_ID
        "feature_value_type": "NUMERICAL",
        # Name of the feature (concatenation of its components)
        "name": "viewed_category_code_match_count"
    },
    # List of namespace components (enums) (repeated for convenience)
    "namespace_components": [
        "viewed",
        "category_code",
        "match_count"
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
            "add2cart",
            "sku_id"
        ],
        "name": "add2cart_sku_id"
    },
    "namespace_components": [
        "add2cart",
        "sku_id"
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
hv list-available-transformations --algorithm-jar <your algorithm jar path> --algorithm-name <the name of the algorithm to dump the features for>
```

This will create the following files:

 - `<algorithm-name>.list-available-transformations.metadata.json`
 - `<algorithm-name>.list-available-transformations.output`

The actual metadata is stored in the `output` (the `metadata.json` file contains the metadata of the operation). You can also specify the `--metadata-path` and `--dest-path` to specify the path to write the metadata and the output to.

The metadata json is not designed to be read by a human - use commands like `jq` to filter and format data. For example, to list all transformations that can be used as features, and start with "add2cart", run the following script on the output:
`jq '[.[] | select(.can_be_feature == true and .namespace_components[0] == "add2cart")]'`