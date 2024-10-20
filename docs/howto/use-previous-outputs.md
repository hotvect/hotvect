# How to: Reuse existing outputs like prediction parameters

## Quick guide for reusing existing prediction parameters (model parameters)
The most common use case for reusing existing outputs is to reuse prediction parameters (model parameters). For example, if you want to investigate an offline-online discrepancy, you might want to re-run the prediction offline, using the exact prediction parameter that was used in the online system and compare the scores.

Another common use case is evaluating an algorithm in a different context. For example, you might want to evaluate an algorithm on a different test dataset. While you could generate the prediction parameters from scratch, reusing existing prediction parameters saves time.

The easiest way to do this is to use the `hotvect_execution_parameters.with_parameter` option in the algorithm definition. Here is an example:

```json
{
  "dependencies": {
    "my-algorithm-ctr-model": {
      "hotvect_execution_parameters": {
        "with_parameter": "s3://mybucket/my-algorithm-ctr-model@my-algorithm-1.2.3-abc/last_train_date_2024-06-01/predict-parameters.zip"
      }
    }
  }
}
```
The value of `with_parameter` must either be a s3 URI, or a local file path. When this option is set, hotvect will use the prediction parameters from the specified location. If the prediction parameters are not found, hotvect will raise an error.

When `with_parameter` is specified, all steps are skipped for that algorithm. Therefore, this option only makes sense to use for a dependency algorithm. If you use it for an top-level algorithm, the pipeline will simply not do anything.

## Reusing existing prediction parameters (model parameters) without a dependency algorithm
What if you don't have a dependency algorithm, but you still want to reuse existing prediction parameters? In this case, you can use the more low level parameter `cache` like so:
```json
{
  "hotvect_execution_parameters": {
    "train": {
      "cache": "s3://mybucket/my-algorithm-ctr-model@my-algorithm-1.2.3-abc/last_train_date_2024-06-01/predict-parameters.zip"
    }
  }
}
```

Similar to `with_parameter`, the value of `cache` can be a s3 URI or a local file path. When this option is set, hotvect will use the prediction parameters from the specified location. Unlike `with_parameter`, if the specified parameters are not found, hotvect will generate the prediction parameters and upload it to the specified location.

## What outputs can be reused in hotvect?
The `with_parameter` option only works with prediction parameters, but the `cache` option can be used for the following outputs:

 - `generate-state`: States that are needed for encoding of training data, like feature states or available action states
 - `encode`: The encoded training data
 - `train`: The prediction parameters (model parameters)

The `cache` option should be specified under `hotvect_execution_parameters.<step>` where `<step>` is one of `generate-state`, `encode`, or `train` (see example below). It takes either a s3 URI, a local file path, or `true`. If the value is `true`, hotvect will cache the output in the default location using the algorithm name, algorithm version and hyperparameter version. For this to work, the option `cache_base_dir` must be set, which can be a s3 URI or a local file path.

Since it's a caching mechanism, if the output is not available in the specified location, hotvect will generate the output and upload it to the specified location. The mechanism can be used to save time when a single algorithm needs to be evaluated in different ways (for example, on different datasets).

```python
{
    "hotvect_execution_parameters": {
        # This location will be used to derive the cache location if `cache` is set to `true`. If none of your cache locations are set to `true`, you can omit this.
        "cache_base_dir": "s3://mybucket/cache/",
        "generate-state": {
            # This will store the cache to the default location
            "cache": True
        },
    },
    "dependencies": {
        "impression2add2cart-model": {
            "hotvect_execution_parameters": {
                "train": {
                    # This will store the cache to the specified location
                    "cache": "s3://mybucket/my-algorithm-ctr-model@my-algorithm-1.2.3-abc/last_train_date_2024-06-01/predict-parameters.zip"
                },
                "predict": {
                    # Unlike "with_parameter", even if cache is specified, the pipeline will still execute prediction and performance-test by default
                    # If you want to skip these steps, you can set "enabled" to false
                    "enabled": False
                },
                "predict": {
                    # Unlike "with_parameter", even if cache is specified, the pipeline will still execute prediction and performance-test by default
                    # If you want to skip these steps, you can set "enabled" to false
                    "enabled": False
                },

                "performance-test": {
                    "enabled": False
                }
            }
        }
    }
}
```
Unlike "with_parameter", even if cache is specified, the pipeline will still execute subsequent steps (`prediction`, and `performance-test`), step by default. If you want to skip these steps, you can set "enabled" to false for these steps. Or, you can use `with_parameter` to achieve the same behavior.

