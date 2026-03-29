---
name: training-setup
description: Automatically configures and validates hotvect training runs. Use when user is setting up local training or mentions training an algorithm.
allowed-tools: Bash, Read, Grep, Glob
---

# Training Setup Skill

## Purpose

Automatically validate configuration and guide users through setting up local hotvect algorithm training runs, ensuring data availability, version compatibility, and proper parameters.

**Hotvect Version:** Always use the currently installed hotvect version. Hotvect is backward compatible - never switch versions to match algorithms.

## ⚠️ Configuration Protection Policy

**CRITICAL: Never modify `~/.hotvect/` configuration files.** See `CONFIG_PROTECTION_POLICY.md` for full policy.

This skill reads `~/.hotvect/config.json` for directory paths but never modifies it. Work only in user-specified output directories.

## Configuration

**CRITICAL:** You (Claude) must read `~/.hotvect/config.json` at skill invocation to construct complete `hv train` commands.

**How this works:**
- The `hv train` CLI tool does NOT read config.json
- YOU read config.json and construct the full command line with all arguments
- Config provides defaults that can be overridden by user-specified values

**When this skill activates:**
1. Read config: `cat ~/.hotvect/config.json`
2. Parse the JSON to extract values (you can understand JSON directly)
3. Use config values as defaults when user hasn't specified:
   - `directories.data_base_dir` → `--data-base-dir` argument
   - `directories.output_base_dir` → `--output-base-dir` argument
   - `aws.login_command` → Command for AWS credential refresh

4. **Fail fast** if config doesn't exist and user didn't provide required paths
5. Tell user to run `/agent hotvect-setup-agent` if config is missing
6. **Activate virtual environment**: `source ${hotvect_source_dir}/python/.venv/bin/activate`

**Important:** Always construct complete `hv train` commands with ALL required arguments explicitly specified.

## When to Invoke

Invoke this skill when:
- User mentions training an algorithm
- User is setting up a training run
- User wants to run `hv train`
- User asks about training configuration

## Context Requirements Before Activation

**Before activating this skill, ensure you have gathered:**
1. **Algorithm name**: Which algorithm to train (ask user or infer from context)
2. **Algorithm JAR path**: Check `~/.m2/repository/` or ask if not obvious
3. **Last test time**: Date in YYYY-MM-DD format (ask user if not mentioned)
4. **Data directory**: From config or user-specified
5. **Output directory**: From config or user-specified
6. **Algorithm override** (optional): If user wants custom config (e.g., "2-day training")

**If critical information is missing:**
- Ask user before proceeding
- Don't activate skill until you have enough information to construct a complete `hv train` command

## Operations

### 1. Detect Training Intent

From user's message, identify:
- Algorithm name to train
- Desired last test time (if mentioned)
- Data location (if mentioned)

### 2. Verify Algorithm JAR

Check if algorithm JAR exists:
```bash
ls -lh ~/.m2/repository/.../algorithm-*.jar
```

If missing, inform user they need to build the algorithm first.

### 3. Check Data Availability

Verify required data exists at expected locations:
- Training data directories
- Test data directories

Calculate required date range based on:
- Last test time
- Training lag days (from algorithm definition)
- Number of training days (from algorithm definition or override)

If data missing, notify user and suggest downloading with `hv-ext download-data-dependency`.

### 4. Verify Parent-Child Structure

**CRITICAL:** Read algorithm definition to check for parent-child relationships.

If algorithm has dependencies (parent-child structure):
- Inform user they must train the PARENT algorithm
- Warn against training child directly with parent override
- Explain this prevents self-dependency bugs

### 5. Check for Algorithm Override

If user mentions custom configuration (e.g., fewer training days), check for override file:
- Look for `2day-override.json` or similar
- If not found, offer to create one

Example override for 2-day training:
```json
{
    "hyperparameter_version": "2day",
    "hotvect_execution_parameters": {
        "performance-test": {
            "enabled": false
        }
    },
    "dependencies": {
        "child-algorithm-name": {
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

### 6. Construct Training Command

Build the complete `hv train` command with validated parameters:
```bash
hv train \
  --algorithm-name ${parent_algorithm_name} \
  --data-base-dir ${data_dir} \
  --output-base-dir ${output_dir} \
  --algorithm-jar ${jar_path} \
  --last-test-time ${test_date} \
  --algorithm-override ${override_json}  # If applicable
```

**Important:**
- Use absolute paths for `--output-base-dir` to avoid path bugs
- Ensure training the PARENT algorithm, not the child

### 7. Present Configuration Summary

Show user:
- Algorithm to train
- Data locations and date ranges
- Output directory
- Override configuration (if used)
- Complete command

Ask user to confirm before execution.

## Expected Output

**Successful configuration:**
```
Training configuration validated:
✓ Algorithm JAR: ~/.m2/repository/.../algo-1.0.0.jar
✓ Training data: 2025-08-07 to 2025-08-08 (2 days)
✓ Test data: 2025-08-09
✓ Override: 2day-override.json

Ready to execute:
hv train --algorithm-name parent-algo --data-base-dir /data ...
```

## Error Handling

**Algorithm JAR not found:**
- Inform user to build the algorithm
- Provide build command: `mvn clean package -DskipTests && mvn install -DskipTests`

**Data not found:**
- Calculate required date ranges
- Suggest downloading with `hv-ext download-data-dependency`
- Provide example download command

**Parent-child confusion:**
- Warn user if they're about to train child directly
- Explain they must train the parent algorithm
- Prevent self-dependency bugs

## Communication

- Present configuration summary clearly
- Highlight any issues or missing requirements
- Provide actionable commands to fix problems
- Confirm before proceeding with training
