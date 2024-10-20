# Migrating from Hotvect v7 to Hotvect v8

This guide provides information on migrating from Hotvect v7 to Hotvect v8.

## Why You Should Migrate

### Improved TopK Support

Algorithm developers can now attach pre-computed ML features to the `AvailableAction`s to be used in a TopK request. I.e. the `AvailableActionState` can now serve as a feature store (for the candidates to be considered in TopK operations). For example, vector representations of the available actions can be stored, which can then be used for vector search etc.

Pre-computed features are seamlessly integrated into the memoization framework, allowing them to be accessed in the same way as other features.

### Enhanced API

The memoization API introduced in v7 has been generalized into a computation API, which covers computation without caching, memoization (caching within the scope of a request), and pre-computation (caching across requests). As a result, the memoization API has been moved from the `hotvect-core` module to the `hotvect-api` module. At the same time, mMethods and classes have been renamed to be more intuitive.

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

### Removal of the `offline-online-analysis` Module

This module was poorly designed and unused in practice; it has been removed.

### Dropping Support for v6

Support for the v6 API has been removed.

## Known Issues

Due to the removal of the `hotvect-core` module from the `hotvect-offline` module, the commandline task for listing available transformations is currently broken (removed).
 This issue will be addressed in future versions.