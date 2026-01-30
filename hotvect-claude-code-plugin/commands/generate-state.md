---
description: Generate state files for algorithms requiring external data (TopK, lookup tables, embeddings)
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


You are executing the generate-state command to create state files required by certain algorithms.

## Purpose

Generate state files (e.g., available items, embeddings, lookup tables) for algorithms that require external data beyond training examples.

## STEP 0: Understanding Algorithm Structure (CRITICAL)

**Composite Algorithm Architecture:**

Many algorithms have **parent-child (outer-inner) relationships**:
- **Parent (Outer)**: Orchestrates evaluation and testing (e.g., `my-ranker`)
- **Child (Inner)**: Actual ML model that trains (e.g., `my-ranker-model`)

**For generate-state command:**
- State generation can be at parent OR child level depending on algorithm
- Check algorithm definition for `generate_state_factory_classname` to identify which algorithm has state generation
- Use the algorithm name that has the generator defined

**Finding the correct algorithm name:**
```bash
# List all algorithm definition JSONs in JAR
unzip -l /path/to/algorithm.jar | grep 'algorithm-definition.json'

# Read each to find which has state generation
unzip -p /path/to/algorithm.jar path/to/some-algorithm-definition.json | jq '{algorithm_name, generate_state_factory_classname}'

# Algorithm with generate_state_factory_classname has state generation capability
```

## When Needed

Algorithms that use:
- TopK retrieval (need available items list)
- Embeddings (need vector representations)
- Lookup tables (need reference data)
- Dynamic inventories (need current inventory)

## Required Arguments

Parse from user or ask:
- `--algorithm-jar`: Path to algorithm JAR file (prefer `~/.m2/repository/` or `target/`)
- `--algorithm-name`: Name of the algorithm with state generation capability
- `--source-path`: JSON with source data paths (e.g., `{"available_configs":["file.jsonl"]}`)

## Optional Arguments

- `--dest-path`: Output state file path (auto-generated if not specified)
- `--parameter-path`: Trained parameters (if algorithm needs them)
- `--samples`: Limit number of items to process

## Example Execution

**Article TopK (with embeddings):**
```bash
hv generate-state \
  --algorithm-jar ~/.m2/repository/.../article-topk-1.0.0.jar \
  --algorithm-name article-search-topk \
  --source-path '{"zmclip_embedding":["/path/to/embeddings/dt=2025-10-16"],"available_skus":["/path/to/configs/dt=2025-10-17"]}' \
  --dest-path ./available_configs.jsonl
```

**Item Ranking (with available items):**
```bash
hv generate-state \
  --algorithm-jar algorithm.jar \
  --algorithm-name my-algo \
  --source-path '{"available_items":["items.jsonl"]}' \
  --dest-path ./state.jsonl
```

## What It Does

1. Loads algorithm and state generator
2. Reads source data (embeddings, item lists, etc.)
3. Processes and filters data as needed
4. Generates state file in algorithm-specific format
5. Packages state for use in training/serving

## Source Path Format

JSON object mapping data types to file lists:
```json
{
  "data_type_1": ["/path/to/file1.jsonl"],
  "data_type_2": ["/path/to/file2.jsonl", "/path/to/file3.jsonl"]
}
```

Data types depend on algorithm requirements (check algorithm definition).

## Use in Training

State generation is often part of the training pipeline (`hv train`) but can be run standalone for:
- Testing state generation logic
- Updating state without retraining
- Debugging state-related issues

## Next Steps

After state generation:
- Verify state file format
- Use in training with `/train`
- Package with model for deployment

## Tips

- Check algorithm definition for required data types
- Ensure source data matches expected format
- State files can be large (embeddings especially)
- Use `--samples` to test with smaller dataset
