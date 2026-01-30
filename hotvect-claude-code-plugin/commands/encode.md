---
description: Transform raw data into ML library format for model training (VW or CatBoost)
---


## 🌶️ STEP 0: READ CONFIG FIRST (MANDATORY)

**Before doing ANYTHING else, read the central configuration:**
```bash
cat ~/.hotvect/config.json
```

Extract and use:
- `directories.data_base_dir` - Where test data lives
- `hotvect_source_dir` - Where hotvect Python venv is
- Then activate: `source ${hotvect_source_dir}/python/.venv/bin/activate`

See `../COMMON_PREAMBLE.md` for full discovery strategies.


You are executing the encode command to convert raw data into format required by ML libraries.

## Purpose

Transform raw input data through feature engineering pipeline and encode into format consumable by ML libraries (Vowpal Wabbit or CatBoost).

## STEP 0: Understanding Algorithm Structure (CRITICAL)

**Composite Algorithm Architecture:**

Many algorithms have **parent-child (outer-inner) relationships**:
- **Parent (Outer)**: Orchestrates evaluation and testing (e.g., `my-ranker`)
- **Child (Inner)**: Implements feature encoding for training (e.g., `my-ranker-model`)

**For encode command:**
- Use the algorithm name that **has an encoder** (commonly the inner/child algorithm that trains)
- Encoding transforms raw data into ML library format
- Parent algorithms that only orchestrate typically don't have encoders

**Finding the correct algorithm name:**
```bash
# List all algorithm definition JSONs in JAR
unzip -l /path/to/algorithm.jar | grep 'algorithm-definition.json'

# Read each to find which has training_command (definitive way to check if algo trains)
unzip -p /path/to/algorithm.jar path/to/some-algorithm-definition.json | jq '{algorithm_name, training_command, encoder_factory_classname}'

# Algorithm that trains has:
# - training_command (definitive indicator)
# - encoder_factory_classname (for encoding training data)
```

## Required Arguments

Parse from user or ask:
- `--algorithm-jar`: Path to algorithm JAR file (prefer `~/.m2/repository/` or `target/`)
- `--algorithm-name`: Name of the INNER algorithm transformation
- `--source-path`: Input data file or directory (use train_data_prefix from algorithm definition)
- `--dest-path`: Output encoded data path (use current working directory, not source repository directory)
- `--algorithm-definition`: Algorithm definition JSON file

## Optional Arguments

- `--samples`: Limit number of records to encode
- `--parameter-path`: Path to parameters (for algorithms requiring state)
- `--extra-jvm-args`: Additional JVM arguments

## Example Execution

```bash
hv encode \
  --algorithm-jar ~/.m2/repository/.../algorithm-1.0.0.jar \
  --algorithm-name my-algorithm \
  --algorithm-definition /path/to/algorithm-definition.json \
  --source-path /path/to/training-data/ \
  --dest-path /path/to/encoded-data.tsv
```

## What It Does

1. Loads algorithm and configuration
2. Reads raw input data
3. Executes feature transformations
4. Vectorizes features (hashing, normalization)
5. Encodes in ML library format (VW text or CatBoost TSV)
6. Writes encoded data for training

## Output Formats

**Vowpal Wabbit:**
- Text format with label and feature:value pairs
- Supports namespaces for feature organization

**CatBoost:**
- TSV format with columns
- Includes label and feature values

## Use in Training Pipeline

Encoding is typically part of the full training pipeline (`hv train`), but can be run standalone for:
- Debugging encoding issues
- Pre-encoding large datasets
- Testing feature transformations

## Next Steps

After encoding:
- Inspect encoded data format
- Train model using ML library directly
- Or use `/train` for full pipeline

## Tips

- Encoding can be memory-intensive for large datasets
- Use `--samples` to test on small subset first
- Encoded data can be large - ensure sufficient disk space
