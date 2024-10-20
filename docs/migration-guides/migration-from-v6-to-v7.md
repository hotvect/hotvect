# Migrating from Hotvect v6 to Hotvect v7

## Why you should migrate
### API improvements
 - The new "memoization" API introduced in v6 was simplified as follows:
   - Removal of Two-Stage Transformer Builder Pattern: The need for registering enums used as feature names and then declaring the transformations (using the same enums) is no-longer necessary. Instead, it is only necessary to declare the transformations (which are then available as features). 
   - Enums used as features no longer need to implement the "getComputationDependency" method. The dependency is inferred from the corresponding transformation instead.
   - Enums no longer need to implement the "getCachingMode" method. Instead, the caching mode is specified from the builder.

Otherwise the API is similar to v7.

Support for the older "v5" API has been discontinued. The "v6" API, found in `com.hotvect.hotvect.v6`, is now deprecated. Hotvect v7 has taken over the "standard" package, previously occupied by the v5 API. Consequently, algorithms that depend on the v5 API cannot be used with hotvect-v7.

### Performance improvements
 - Overall performance is improved (this depends on the algorithm).

## Backward compatibility and known compatibility issues
 - Algorithms developed using hotvect v4 or v5 cannot be used with hotvect v7
 - Algorithms developed using hotvect v6 and v7 can be trained and run using hotvect v7