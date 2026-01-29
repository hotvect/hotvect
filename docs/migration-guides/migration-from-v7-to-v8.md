# Migrating from Hotvect v7 to Hotvect v8

This guide provides information on migrating from Hotvect v7 to Hotvect v8.

## Backward compatibility
As of v8.8.0, it is possible to train and run algorithms developed with Hotvect v7 using Hotvect v8. However, it is not possible to train or run v8 algorithms with Hotvect v7. Additionally, it is no longer possible to train or run v6 algorithms with Hotvect v8.

## Backward Incompatible Changes

### Removal of the `offline-online-analysis` Module

This module was poorly designed and unused in practice; it has been removed.

### Dropping Support for v6

Support for the v6 API has been removed.


### Upgrade from Java 11 to Java 17
The release target is changed from Java 11 to Java 17, although no new Java 17 features are used.
In addition, various dependencies were upgraded.

### Replacement of `last_training_time` and `test_lag` with `last_test_time`

Before version 8, the `AlgorithmPipeline` class used `last_training_time` and `test_lag` to decide which data to use for model training. However, this approach wasn’t flexible enough because different models within the same algorithm often needed different data lags for training.

In version 8, these fields were replaced with `last_test_time`. Instead of one `test_lag` pipeline wide parameter, each algorithm now defines its own `training_lag_days` in its algorithm definition, allowing each model in a composite algorithm to use a different time period for training.

In addition, `last_training_time` has been removed from default backtesting directory names. Previously, results were stored in directories like `my-algorithm@1.2.3-b1/last_train_date_2024-10-21-last_test_date_2024-10-22/`. Starting from version 8, they are now stored as `my-algorithm@1.2.3-b1/last_test_date_2024-10-22/`. As a result, downloading or extracting test results into dataframes using hotvect v8 will only work with training/test results obtained with v8 or newer, but future updates might address this issue (so that older results can be downloaded/loaded using v8).

### Source data configuration for generating states
Previously, each states' source data was defined as follows:
```json
{
  "states": {
    "my_state_name": {
      "source_data_prefix": {
        "my_data_one": "my_data_location_one",
        "my_data_two": "my_data_location_two"
      },
      "generator_factory_classname": "my.state.generator.FactoryClass"
    }
  }
}
```

From v8 onwards, the source data configuration is as follows:
```json
{
  "states": {
    "my_state_name": {
      "source_data": {
        "my_data_one": {
          "data_prefix": "my_data_location_one",
          "number_of_days": 1,
          "lag_days": 1
        },
        "my_data_two": {
          "data_prefix": "my_data_location_two",
          "number_of_days": 1,
          "lag_days": 1
        }
      },
     "generator_factory_classname": "my.state.generator.FactoryClass"
    }
  }
}
```
### Changes to the data dependency API
Up until v7, the `AlgorithmPipeline.data_dependencies()` only returned the data dependencies of the train and test data of an algorithm (and its dependencies). From v8 on, it will also include any data dependencies of the states used in the algorithm (including those used by its dependencies). To make this easy, the `DataDependency` class was changed as follows:

Before:
```python
@dataclass(frozen=True)
class DataDependency:
    algorithm_name: str
    algorithm_version: str
    train_data_prefix: str
    test_data_prefix: str
    train_data_dates: Set[date]
    test_data_dates: Set[date]
```

After:
```python
@dataclass(frozen=True)
class DataDependency:
    algorithm_name: str
    algorithm_version: str
    data_prefix: str
    data_dates: Set[date]
    data_type: str  # e.g., 'train', 'test', or 'state'
```

### Changes to the `Memoization` interface
The memoization interface was adopted as a standard interface to define feature transformations (whether they are pre-calculated, calculated on-demand, or memoized). To facilitate this, the interfaces were renamed, and also moved to the "api" module of hotvect. In practice, this means you have to rename a bunch of classes and methods in your code. Remember, however that v7 algorithm can be run using hotvect v8, so this is not required right away (but recommended).

| Original Class/Method                                           | New Class/Method                                                | Suggested Replacement                    |
|-----------------------------------------------------------------|-----------------------------------------------------------------|------------------------------------------|
| `com.hotvect.core.transform.memoization.Memoized`               | `com.hotvect.api.transformation.memoization.Computing`          | `Memoized<` to `Computing<`              |
| `com.hotvect.core.transform.memoization.MemoizedTransformation` | `com.hotvect.api.transformation.memoization.Computation`        | `MemoizedTransformation` to `Computation`|
| `com.hotvect.core.transform.memoization.MemoizedInteractionTransformation` | `com.hotvect.api.transformation.memoization.InteractingComputation` | `MemoizedInteractionTransformation` to `InteractingComputation` |
| `MemoizedInteraction`                                           | `com.hotvect.api.transformation.memoization.ComputingCandidate` | `MemoizedInteraction` to `ComputingCandidate` |

### Enabling `cache_base_dir` Activates Caching for All Steps and Dependencies

From v8, setting `hotvect_execution_parameters.cache_base_dir` enables caching for all steps in an algorithm and its dependencies. If a dependency has its own caching setup, that will take priority over the parent algorithm's instructions.

## Backward compatible changes
### Compound namespaces can now be derived from other compound namespaces
You can now create compound namespaces using existing compound namespaces as components. Previously, only non-compound namespaces could be used. Now, elements from an existing compound namespace can be used as a component of a new compound namespace. For example, suppose `customerOrderNamespace` combines `CustomerType.PREMIUM` and `OrderAttribute.PAYMENT_METHOD` (e.g., cash or credit card). You can use it directly to create `orderMetricsNamespace`:

```java
Namespace customerOrderNamespace = CompoundNamespace.declareNamespace(CustomerType.PREMIUM, OrderAttribute.PAYMENT_METHOD);
FeatureNamespace orderMetricsNamespace = CompoundNamespace.declareFeatureNamespace(FeatureType.NUMERICAL, customerOrderNamespace, OrderMetricType.TOTAL_SPENT);
```
This feature simplifies namespace creation, reducing redundancy.


### Improved TopK Support

Algorithm developers can now attach pre-computed ML features to the `AvailableAction`s to be used in a TopK request. I.e. the `AvailableActionState` can now serve as a feature store (for the candidates to be considered in TopK operations). For example, vector representations of the available actions can be stored, which can then be used for vector search etc.

Pre-computed features are seamlessly integrated into the memoization framework, allowing them to be accessed in the same way as other features.

### Addition of ThemedTopK support (beta)
The concept of `ThemedTopK` has been added to allow TopK response to include an additional content (`theme`). For example, this can be used to communicate the name of a carousel to be displayed, in addition to what articles should be included in the carousel and in what order.

### Support for Multiple Data Sources in State Generation

The `GenerateState` interface now supports multiple data sources, providing greater flexibility when generating state.

### Improved State Codec Interface

Previously, the `StateCodec` interface required the same type for both deserialization and serialization. This restriction has been removed; algorithm developers can now use different types for serialization and deserialization. In future versions, we may require developers to implement only the serialization method, while deserialization is left to the algorithm developer (i.e. outside of the framework).

### Better Module Isolation

The initial design intended for the `hotvect-core` module to be specific to algorithms, allowing different algorithms to use different versions of core modules. This isolation was compromised in practice, as the core module was included in the `hotvect-offline` module, creating potential version discrepancies between online and offline environments. In v8, this has been fixed. The `hotvect-core` module is now excluded from both the `hotvect-online` and `hotvect-offline` modules (such that it is only provided by the algorithm jar).

### `PredictStdOut` Command-Line Tool

A new command-line tool, `PredictStdOut`, has been added to the `hotvect-offline` module. This tool allows for predictions to be performed in an interactive manner, aiding in debugging processes.

### File Aggregator with Enhanced Performance

The "Unordered" file aggregator has been introduced. Unordered processing generally offers better performance than ordered processing, though compared to the "Unordered" file mapper, it may be more susceptible to bottlenecks due to the operations on the shared aggregated state.

## Bug Fixes and Other Improvements

## Known Issues

Due to the removal of the `hotvect-core` module from the `hotvect-offline` module, the commandline task for listing available transformations is currently broken (removed).
 This issue will be addressed in future versions.