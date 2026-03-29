# Algorithm Definition Argument Pattern

## Overview

Many hotvect commands accept an `--algorithm-definition` (or `--algorithm-name`) argument that can be specified in **two different ways**:

## Format 1: Algorithm Name (Recommended for Manual Work)

**Usage**: Pass just the algorithm name as a string.

```bash
hv audit \
  --algorithm-jar /path/to/algorithm.jar \
  --algorithm-name example-algorithm-model \
  --source-path data.jsonl \
  --dest-path audit.jsonl
```

**Behavior**:
- Hotvect extracts the algorithm definition JSON from inside the JAR file (it's a ZIP file)
- Uses the bundled definition as-is without modifications
- **Most convenient for manual work** - just type the algorithm name

**When to use**:
- Manual CLI operations
- Interactive debugging
- When you want to use the algorithm exactly as packaged

## Format 2: File Path (Used Internally by Hotvect)

**Usage**: Pass a path to a JSON file containing a complete algorithm definition.

```bash
hv audit \
  --algorithm-jar /path/to/algorithm.jar \
  --algorithm-definition /path/to/custom-algorithm-definition.json \
  --source-path data.jsonl \
  --dest-path audit.jsonl
```

**Behavior**:
- Hotvect reads the algorithm definition from the specified JSON file
- The JSON file **must be fully comprehensive** - it completely replaces the bundled definition
- Cannot be partial - must include all required fields

**When to use**:
- Hotvect internal operations (training, backtest, etc.)
- When you need to override algorithm configuration
- When you want different behavior than the bundled definition

**Requirements**:
- Must be a complete, valid algorithm definition JSON
- Must include all mandatory fields (see `ALGORITHM_DEFINITION_SCHEMA.md`)
- Cannot be a partial override - it's a full replacement

## Common Confusion

❌ **WRONG**: Passing a partial override JSON expecting it to merge with bundled definition
```bash
# This will FAIL - partial overrides don't work
hv audit --algorithm-definition override.json  # Contains only {"number_of_training_days": 2}
```

✅ **CORRECT**: Either use algorithm name OR pass complete definition
```bash
# Option 1: Use algorithm name (gets bundled definition)
hv audit --algorithm-name my-algorithm

# Option 2: Use complete definition file
hv audit --algorithm-definition /full/path/to/complete-definition.json
```

## Internal vs Manual Usage

### Hotvect Internal Operations
When hotvect runs operations automatically (e.g., during backtest), it often:
1. Extracts the bundled algorithm definition from the JAR
2. Applies any overrides to create a modified definition
3. Writes the complete modified definition to a temporary JSON file
4. Passes the **file path** to downstream operations

This is why you'll see file paths in hotvect logs and intermediate operations.

### Manual Operations
When running commands manually, it's more convenient to:
1. Just pass the algorithm name
2. Let hotvect use the bundled definition
3. Avoid managing temporary JSON files

## Example Scenarios

### Scenario 1: Manual Audit (Use Algorithm Name)
```bash
hv audit \
  --algorithm-jar ~/.m2/repository/.../algorithm-77.0.0.jar \
  --algorithm-name example-algorithm-model \
  --source-path test-data/ \
  --dest-path audit.jsonl
```

### Scenario 2: Training with Override (Hotvect Uses File Path Internally)
```bash
# You run this command (with algorithm name):
hv train \
  --algorithm-name example-algorithm \
  --algorithm-jar /path/to/jar \
  --algorithm-override 2day-override.json \
  ...

# Internally, hotvect:
# 1. Extracts bundled definition
# 2. Applies 2day-override.json to it
# 3. Writes complete modified definition to /tmp/algo-def-12345.json
# 4. Passes --algorithm-definition /tmp/algo-def-12345.json to sub-operations
```

### Scenario 3: Debug Command Replay (Prefer Algorithm Name)
When saving commands for debugging, prefer algorithm name over file path:

```bash
# GOOD: Uses algorithm name (portable, replayable)
hv audit \
  --algorithm-jar ~/.m2/repository/.../algorithm-77.0.0.jar \
  --algorithm-name example-algorithm-model \
  --source-path /absolute/path/to/data.jsonl \
  --dest-path /absolute/path/to/audit.jsonl

# AVOID: Uses temporary file path (not replayable)
hv audit \
  --algorithm-jar ~/.m2/repository/.../algorithm-77.0.0.jar \
  --algorithm-definition /tmp/hotvect-12345/algo-def.json \  # File will be deleted!
  --source-path /absolute/path/to/data.jsonl \
  --dest-path /absolute/path/to/audit.jsonl
```

## Key Takeaways

1. **Algorithm name** = convenient for manual work, uses bundled definition
2. **File path** = used internally by hotvect, must be complete definition
3. **Never pass partial JSON** - hotvect doesn't merge overrides in this argument
4. **Prefer algorithm name** when saving debug commands (more portable)
5. **File paths are temporary** - hotvect's internal JSON files get cleaned up

## Related Documentation

- `ALGORITHM_DEFINITION_SCHEMA.md` - Full schema for algorithm definition JSON
- `docs/patterns/override-files.md` - How to use algorithm overrides
- `CONFIG_PROTECTION_POLICY.md` - Config file handling best practices
