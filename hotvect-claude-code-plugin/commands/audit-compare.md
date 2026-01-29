---
description: Generate and compare feature audits between two algorithm versions to verify behavioral consistency
---

You are executing the audit compare command to generate and compare feature audit files between two algorithm versions.

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

## Understanding Algorithm Structure

**Composite Algorithm Architecture:**

Many algorithms have **parent-child (outer-inner) relationships**. For audit comparison:
- Use the algorithm that **has feature transformations** (commonly `my-ranker-model`, not `my-ranker`)
- Parent algorithms that only orchestrate typically don't have features to audit
- List algorithm definitions in JAR and read them to find which has `training_command`:
```bash
unzip -l /path/to/algorithm.jar | grep 'algorithm-definition.json'
unzip -p /path/to/algorithm.jar path/to/algo-definition.json | jq '{algorithm_name, training_command, transformer_factory_classname, vectorizer_factory_classname}'
# Algorithm that trains has training_command and either transformer_factory_classname or vectorizer_factory_classname
```

## Steps

1. **Read config** (see STEP 0)
2. **Activate virtual environment**: `source ${hotvect_source_dir}/python/.venv/bin/activate`
3. **Discover context before asking**:
   - Parse command arguments for version numbers (e.g., `v74` and `v81`)
   - Check current directory for existing audit files, JARs, renamings.json
   - If existing audit: Extract algorithm name with `head -1 v74-audit.jsonl | jq -r '.algorithm_metadata.algorithm_name'`
   - Search `~/.m2/repository/` for algorithm JARs matching versions
   - Look for test data in `${data_base_dir}/` directories
4. **Only ask if genuinely ambiguous** (not if info can be discovered)
5. Locate algorithm JARs in `~/.m2/repository/` for both versions (or `target/` if working on algorithm code)
6. Generate audit for baseline version using `hv audit` (with `--samples` if specified)
7. Generate audit for treatment version using `hv audit` (with `--samples` if specified)
8. Check for existing `renamings.json` in current directory before asking
9. Run `hv-ext compare-jsonl` with or without renamings configuration
10. Report results (identical or differences found)

## JAR Location

**Prefer `~/.m2/repository/` for installed versions:**
```bash
find ~/.m2/repository -name "*my-algorithm-1.0.0.jar"
```

**Or use `target/` when working on algorithm code:**
```bash
ls /path/to/algorithm-repo/target/my-algorithm-*.jar
```

## Working Directory

Use current working directory for audit outputs. Don't create under `~/` or source repository directories.

## Example Execution

```bash
# Find JARs
find ~/.m2/repository -name "*my-algorithm-1.0.0.jar"
find ~/.m2/repository -name "*my-algorithm-2.0.0.jar"

# Generate audits (note: using INNER algorithm name)
hv audit --algorithm-jar ~/.m2/repository/.../my-algorithm-1.0.0.jar \
  --algorithm-name my-algorithm-model \
  --source-path /path/to/data/test_data/dt=2025-10-15/part-00000.json.gz \
  --dest-path ./v1-audit.jsonl

hv audit --algorithm-jar ~/.m2/repository/.../my-algorithm-2.0.0.jar \
  --algorithm-name my-algorithm-model \
  --source-path /path/to/data/test_data/dt=2025-10-15/part-00000.json.gz \
  --dest-path ./v2-audit.jsonl

# Compare
hv-ext compare-jsonl v1-audit.jsonl v2-audit.jsonl -c renamings.json
```

## Output

Report whether audits are identical or show differences found.
