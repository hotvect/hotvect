---
description: Train ML model using complete hotvect pipeline (encode, train, predict, evaluate)
---

You are executing the train command to run the complete ML training pipeline.

## Purpose

Execute end-to-end training pipeline: data encoding, model training, prediction generation, and evaluation - all in one command.

## Configuration

**How config works with this command:**

When a user invokes `/train`, you (Claude) should:
1. Read `~/.hotvect/config.json` if it exists: `cat ~/.hotvect/config.json`
2. Parse the JSON to extract default values (you can understand JSON directly)
3. Use config values as defaults for constructing the `hv train` command:
   - `directories.data_base_dir` → `--data-base-dir` argument
   - `directories.output_base_dir` → `--output-base-dir` argument
4. User-provided arguments override config defaults
5. Construct the complete `hv train` command with ALL arguments explicitly specified

**Important:** The `hv train` CLI tool does NOT read config.json. YOU read it and build the full command line.

If config doesn't exist and user didn't provide required arguments, tell them to run `/agent hotvect-setup-agent` first.

## Required Arguments

Parse from user or ask:
- `--algorithm-name`: Name of the algorithm to train
- `--algorithm-jar`: Path to algorithm JAR file
- `--data-base-dir`: Base directory containing training and test data
- `--output-base-dir`: Base directory for training outputs
- `--last-test-time`: Test date in YYYY-MM-DD format (e.g., 2025-08-09)

## Optional Arguments

- `--algorithm-override`: JSON file with custom configuration
- `--extra-jvm-args`: Additional JVM arguments (e.g., "-Xmx64g")

**CRITICAL: When using --algorithm-override:**
1. Read the override file: `cat /path/to/override.json`
2. Display the full contents to the user
3. Explain what will be overridden (e.g., "This reduces training days from 7 to 2 and disables performance tests")
4. Ask: "Proceed with this override? [y/N]"
5. Only execute the command after user confirms

Never run training with an override without showing the user what will be changed.

## Example Execution

**Standard training:**
```bash
hv train \
  --algorithm-name my-algorithm \
  --algorithm-jar ~/.m2/repository/org/myorg/myalgo/my-algorithm/1.0.0/my-algorithm-1.0.0.jar \
  --data-base-dir /path/to/data \
  --output-base-dir /path/to/output \
  --last-test-time 2025-10-15
```

**With custom override:**
```bash
hv train \
  --algorithm-name my-algorithm \
  --algorithm-jar ~/.m2/repository/.../my-algorithm-1.0.0.jar \
  --data-base-dir /path/to/data \
  --output-base-dir /path/to/output \
  --last-test-time 2025-10-15 \
  --algorithm-override custom-override.json
```

## What It Does

Complete pipeline automatically:

1. **Encode**: Transform training data through feature engineering
2. **Train**: Train ML model (VW or CatBoost) on encoded data
3. **Predict**: Generate predictions on test data
4. **Evaluate**: Calculate performance metrics
5. **Package**: Bundle parameters and metadata

## Output Structure

```
output-base-dir/
└── algorithm-name@version/
    └── last_test_date_YYYY-MM-DD/
        ├── algorithm-name.parameters.zip    # Trained model
        ├── prediction.jsonl                 # Test predictions
        ├── evaluation.json                  # Performance metrics
        └── metadata/                        # Pipeline metadata
```

## Algorithm Override

JSON file for custom configuration:
```json
{
    "hyperparameter_version": "custom",
    "hotvect_execution_parameters": {
        "performance-test": {
            "enabled": false
        }
    },
    "dependencies": {
        "my-algorithm-model": {
            "number_of_training_days": 2,
            "hotvect_execution_parameters": {
                "predict": {"enabled": true},
                "evaluate": {"enabled": true}
            }
        }
    }
}
```

## Parent-Child Algorithms (CRITICAL)

**Composite Algorithm Architecture:**

Many algorithms have **parent-child (outer-inner) relationships**:
- **Parent (Outer)**: Orchestrates evaluation (e.g., `my-algorithm`)
- **Child (Inner)**: ML model that trains (e.g., `my-algorithm-model`)

**For train command:**
- **ALWAYS train the PARENT algorithm** - it automatically trains dependencies
- Override's `dependencies` section configures child algorithms
- **NEVER train child directly** with parent override (causes self-dependency bugs)

**Example:**
- Parent algorithm: `my-algorithm` (orchestrator)
- Child algorithm: `my-algorithm-model` (actual ML model)
- Train command: `hv train --algorithm-name my-algorithm ...`
- Override specifies settings for `my-algorithm-model` in `dependencies` section

## Training Data Requirements

Algorithm automatically finds training data based on:
- `data-base-dir`: Where to look for data
- `last-test-time`: Test date
- Algorithm definition: Data prefixes, number of days, lag days

Ensure data is downloaded before training (use `/download-data-dependency` or `data-dependency-download` skill).

## Next Steps

After training:
- Review evaluation metrics in `evaluation.json`
- Compare with baseline using `/compare-evaluations`
- Use parameters for prediction or deployment

## Tips

- Use absolute paths for `--output-base-dir` to avoid path bugs
- Training can take hours depending on data size
- Use 2-day override for faster testing (fewer training days)
- Check logs if training fails (typically data issues or insufficient memory)
- **Version compatibility**: hotvect is backward compatible - algorithm and CLI versions don't need to match
