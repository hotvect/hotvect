---
description: Generate model predictions on test data using trained parameters
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


You are executing the predict command to generate predictions from a trained model.

## Purpose

Apply trained model to test/validation data to generate predictions for evaluation or serving.

## STEP 0: Understanding Algorithm Structure (CRITICAL)

**Composite Algorithm Architecture:**

Many algorithms have **parent-child (outer-inner) relationships**:
- **Parent (Outer)**: Orchestrates evaluation and testing (e.g., `my-ranker`)
- **Child (Inner)**: Implements ML model training (e.g., `my-ranker-model`)

**For predict command:**
- Use the algorithm name that **has trained model parameters** (commonly the inner/child algorithm)
- This is the algorithm that was actually trained (has encoder and model)
- Parent algorithms that only orchestrate don't have trained parameters

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
- `--source-path`: Input test data file or directory (use test_data_prefix from algorithm definition)
- `--dest-path`: Output predictions file path (use current working directory, not source repository directory)
- `--parameter-path`: Path to trained model parameters ZIP file (from training output)

## Optional Arguments

- `--samples`: Limit number of records to predict
- `--metadata-path`: Path where operation metadata will be saved (auto-generated if not specified)
- `--dest-schema-path`: Path for feature schema description

## Example Execution

```bash
hv predict \
  --algorithm-jar ~/.m2/repository/.../algorithm-1.0.0.jar \
  --algorithm-name my-algorithm \
  --source-path /path/to/test-data/ \
  --dest-path /path/to/predictions.jsonl \
  --parameter-path /path/to/trained-model.parameters.zip

# With sampling for testing
hv predict \
  --algorithm-jar ~/.m2/repository/.../algorithm-1.0.0.jar \
  --algorithm-name my-algorithm \
  --source-path /path/to/test-data/ \
  --dest-path /path/to/predictions.jsonl \
  --parameter-path /path/to/trained-model.parameters.zip \
  --samples 1000
```

## What It Does

1. Loads algorithm and trained parameters
2. Reads test data
3. Executes feature transformations
4. Applies trained model to generate predictions
5. Writes predictions (scores, rankings, classifications)

## Output Format

JSONL file with predictions:
- Original input fields
- Model predictions (scores, probabilities, rankings)
- Additional metadata

## Use Cases

- Evaluate model on test set
- Generate predictions for offline analysis
- Debug why model produces certain predictions
- Validate model behavior before deployment

## Next Steps

After prediction:
- Evaluate predictions using `/evaluate`
- Compare predictions between models
- Analyze prediction quality

## Tips

- Predictions require trained parameters from `/train`
- Test data should match training data format
- For feature debugging, use `/audit` command separately (see below)

## Feature Debugging in v9

The `--log-features` flag is not available in hotvect v9. To debug features:

1. **Run audit separately:**
   ```bash
   hv audit \
     --algorithm-jar algorithm.jar \
     --algorithm-name my-algorithm \
     --source-path test-data.jsonl \
     --dest-path audit.jsonl
   ```

2. **Inspect audit output:**
   ```bash
   cat audit.jsonl | jq 'select(.id == "record_123")' | jq '.features'
   ```

3. **Run prediction:**
   ```bash
   hv predict \
     --algorithm-jar algorithm.jar \
     --algorithm-name my-algorithm \
     --parameter-path parameters.zip \
     --source-path test-data.jsonl \
     --dest-path predictions.jsonl
   ```

**Note:** Feature logging during prediction is available in hotvect v10+.
