# Algorithm Definition JSON Schema Reference

**🌶️ CRITICAL: This is the AUTHORITATIVE schema for algorithm definition JSON files.**

**DO NOT INVENT FIELDS.** If a field is not listed here, IT DOES NOT EXIST in algorithm definitions.

## Parent (Outer) Algorithm Definition

Parent algorithms orchestrate testing and evaluation. They typically do NOT have feature transformations.

**ACTUAL FIELDS:**
```json
{
  "hotvect_version": "string (Maven property)",
  "git_describe": "string (Git describe output)",
  "algorithm_name": "string (artifact ID)",
  "algorithm_version": "string (project version)",
  "test_data_prefix": "string (test data directory name)",
  "training_container": "string (Docker image for training)",
  "dependencies": ["array of child algorithm names"],
  "decoder_factory_classname": "string (Java class FQN)",
  "reward_function_factory_classname": "string (Java class FQN)",
  "algorithm_factory_classname": "string (Java class FQN)",
  "sagemaker_execution_parameters": {
    "instance_type": "string (e.g., ml.m5.12xlarge)",
    "max_runtime": "integer (seconds)",
    "volume_size_in_gb": "integer"
  }
}
```

**Key Indicators:**
- Has `dependencies` field → Parent algorithm
- Has `test_data_prefix` → For evaluation
- NO `training_command` → Does not train itself
- NO `transformer_factory_classname` → No feature transformations

## Child (Inner) Algorithm Definition

Child algorithms implement feature transformations and ML training.

**ACTUAL FIELDS:**
```json
{
  "hotvect_version": "string",
  "git_describe": "string",
  "algorithm_name": "string",
  "algorithm_version": "string",
  "decoder_factory_classname": "string (Java class FQN)",
  "transformer_factory_classname": "string (Java class FQN) - HAS FEATURES",
  "reward_function_factory_classname": "string (Java class FQN)",
  "encoder_factory_classname": "string (Java class FQN)",
  "algorithm_factory_classname": "string (Java class FQN)",
  "train_data_prefix": "string (training data directory name)",
  "test_data_prefix": "string (test data directory name)",
  "number_of_training_days": "integer (default: 7)",
  "training_lag_days": "integer (default: 1)",
  "training_command": "string (template for training command) - HAS TRAINING",
  "algorithm_parameters": {
    "catboost_scorer": {
      "nofork_threshold": "integer"
    }
  },
  "transformer_parameters": {
    "features": ["array of feature names"],
    "feature_store": {
      "feature_fetch_timeout_ms": "integer",
      "config_features": {},
      "customer_features": {},
      "campaign_features": {}
    }
  },
  "catboost_training_parameters": {
    "iterations": "integer",
    "learning_rate": "float",
    "depth": "integer",
    "l2_leaf_reg": "float",
    "loss_function": "string"
  }
}
```

**Key Indicators:**
- Has `training_command` → Trains ML model
- Has `transformer_factory_classname` → Has feature transformations
- Has `train_data_prefix` → Training data requirement
- Has `number_of_training_days` → Training window

## Algorithm Override JSON Schema

Override JSON is used to change algorithm behavior for backtests or training.

### hyperparameter_version Field

**What it does:** A string label appended to the algorithm version slug to create separate output directories. This allows you to segregate training outputs when using different configurations without mixing up results.

**How it works:**
- Algorithm slug: `my-algorithm@1.0.0`
- With hyperparameter_version `"2day"`: `my-algorithm@1.0.0-2day`
- Output path becomes: `{output_base_dir}/my-algorithm@1.0.0-2day/last_test_date_2025-08-09/`

**Why this matters:** When you use overrides to change training behavior (fewer training days, different hyperparameters, disabled performance tests), the hyperparameter_version ensures outputs don't overwrite each other. Each configuration gets its own directory.

**Common values:**
- `"1day"` - Standard 1-day training configuration
- `"2day"` - Fast 2-day training for quick iteration
- `"fast"` - Minimal configuration for rapid testing
- Custom labels you define (e.g., `"production"`, `"experimental"`)

**Example:** Without hyperparameter_version, training with default config and with 2-day override would both write to `my-algorithm@1.0.0/`, mixing results. With hyperparameter_version, they write to separate directories and you can easily compare outputs.

**CORRECT STRUCTURE:**
```json
{
  "hyperparameter_version": "string (e.g., '2day', 'fast', '1day')",
  "hotvect_execution_parameters": {
    "performance-test": {
      "enabled": true|false
    }
  },
  "dependencies": {
    "child-algorithm-name": {
      "number_of_training_days": "integer",
      "training_lag_days": "integer",
      "hotvect_execution_parameters": {
        "performance-test": {"enabled": true|false},
        "generate-state": {"enabled": true|false},
        "predict": {"enabled": true|false},
        "evaluate": {"enabled": true|false}
      },
      "transformer_parameters": {
        "features": ["override feature list"]
      },
      "catboost_training_parameters": {
        "iterations": "integer"
      }
    }
  }
}
```

**IMPORTANT RULES:**
1. **Top-level overrides apply to parent algorithm**
2. **`dependencies` section overrides child algorithms**
3. **Key must exactly match child algorithm name from parent's `dependencies` array**
4. **Can override any field from child algorithm definition**

**EXAMPLE - 2-Day Training Override:**
```json
{
  "hyperparameter_version": "2day",
  "hotvect_execution_parameters": {
    "performance-test": {
      "enabled": false
    }
  },
  "dependencies": {
    "my-ranker-model": {
      "number_of_training_days": 2,
      "hotvect_execution_parameters": {
        "performance-test": {
          "enabled": false
        }
      }
    }
  }
}
```

**WRONG STRUCTURE (COMMON MISTAKES):**

❌ **Using parent algorithm name as key:**
```json
{
  "my-ranker": {
    "number_of_training_days": 2
  }
}
```
This is WRONG. The override key should be the CHILD algorithm name, not parent.

❌ **Missing `dependencies` wrapper:**
```json
{
  "my-ranker-model": {
    "number_of_training_days": 2
  }
}
```
This is WRONG. Child overrides must be under `dependencies` key.

## Standard Fields in Algorithm Definitions

**Data-related:**
- `train_data_prefix` - Directory name for training data (child only)
- `test_data_prefix` - Directory name for test data
- `number_of_training_days` - Training window size (child only)
- `training_lag_days` - Days between last training and test date (child only)

**Java Class References (all are FQN strings):**
- `decoder_factory_classname` - How to parse input JSON
- `transformer_factory_classname` - Feature transformations (child only)
- `encoder_factory_classname` - ML library encoder (child only)
- `reward_function_factory_classname` - Learning objective
- `algorithm_factory_classname` - Algorithm implementation
- `generate_state_factory_classname` - State generation (optional, for TopK algorithms)

**Training-related (child only):**
- `training_command` - Template for training invocation
- `catboost_training_parameters` - CatBoost hyperparameters
- `algorithm_parameters` - Algorithm-specific config
- `transformer_parameters` - Feature configuration

**Execution-related:**
- `sagemaker_execution_parameters` - SageMaker instance config (parent only)
- `dependencies` - List of child algorithm names (parent only)

## Fields That DO NOT EXIST

**NEVER use these invented fields:**
- ❌ `algorithm_type` - Does not exist
- ❌ `model_type` - Does not exist
- ❌ `baseline_version` - Does not exist
- ❌ `candidate_version` - Does not exist
- ❌ `comparison_config` - Does not exist
- ❌ `training_config` - Does not exist (use `transformer_parameters` or `catboost_training_parameters`)
- ❌ `evaluation_config` - Does not exist
- ❌ `backtest_config` - Does not exist (backtest config is separate, not in algo def)

## How to Discover Actual Algorithm Structure

**1. List algorithm definitions in JAR:**
```bash
unzip -l /path/to/algorithm.jar | grep 'algorithm-definition.json'
```

**2. Extract and examine algorithm definition:**
```bash
unzip -p /path/to/algorithm.jar path/to/algo-definition.json | jq '.'
```

**3. Check for parent-child relationships:**
```bash
# Parent has dependencies
unzip -p algorithm.jar parent-algo-definition.json | jq '.dependencies'

# Child has training_command
unzip -p algorithm.jar child-algo-definition.json | jq '.training_command'
```

**4. Find which algorithm has features:**
```bash
# Algorithm with features has transformer_factory_classname
unzip -p algorithm.jar algo-definition.json | jq '{
  algorithm_name,
  training_command,
  transformer_factory_classname
}'
```

## Summary

- **Parent algorithms**: Orchestrate evaluation, have `dependencies`, NO training
- **Child algorithms**: Have features, have `training_command`, do ML training
- **Overrides**: Top-level for parent, `dependencies.{child-name}` for child
- **When in doubt**: Extract and read the actual JSON from the JAR
