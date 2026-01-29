# Anti-Hallucination Changes Summary

## Problem

The Claude plugin agents were **hallucinating command options and algorithm definition fields** that don't exist:

**Hallucinated `hv backtest` options:**
- ❌ `--algorithm my-algorithm` - DOES NOT EXIST
- ❌ `--baseline v74` - DOES NOT EXIST
- ❌ `--candidate v81` - DOES NOT EXIST

**Hallucinated algorithm override structure:**
```json
❌ WRONG - Missing dependencies wrapper
{
  "my-algorithm-model": {
    "number_of_training_days": 2
  }
}
```

## Solution

Created authoritative reference documents that agents MUST check before constructing commands or JSON.

### 1. HV Command Reference (`HV_COMMAND_REFERENCE.md`)

**Contains:**
- Actual command signatures for ALL `hv` and `hv-ext` commands
- Required vs optional arguments
- Explicit list of hallucinated options to avoid
- Correct examples vs wrong examples
- Directive to run `--help` when in doubt

**Example for `hv backtest`:**
```
ACTUAL OPTIONS: --git-reference, --algo-repo-url, --data-base-dir, etc.

HALLUCINATED OPTIONS THAT DO NOT EXIST:
- ❌ --algorithm
- ❌ --baseline
- ❌ --candidate
```

### 2. Algorithm Definition Schema (`ALGORITHM_DEFINITION_SCHEMA.md`)

**Contains:**
- Complete JSON schema for parent algorithm definitions
- Complete JSON schema for child algorithm definitions
- Algorithm override JSON structure with examples
- Fields that exist vs fields that don't exist
- How to discover actual structure from JARs
- Parent-child relationship explanation

**Example override structure:**
```json
✅ CORRECT
{
  "hyperparameter_version": "2day",
  "hotvect_execution_parameters": {...},
  "dependencies": {
    "child-algorithm-name": {
      "number_of_training_days": 2,
      "hotvect_execution_parameters": {...}
    }
  }
}
```

### 3. Updated Common Preamble

Added **Step 2** and **Step 3** to mandatory workflow:

```markdown
## Step 2: Check Command Reference (ANTI-HALLUCINATION)

Before constructing ANY `hv` or `hv-ext` command, read:
cat ~/workspace/hotvect/hotvect-claude-code-plugin/HV_COMMAND_REFERENCE.md

## Step 3: Check Algorithm Schema (If Working With Overrides)

Before creating/reading algorithm overrides or definitions, read:
cat ~/workspace/hotvect/hotvect-claude-code-plugin/ALGORITHM_DEFINITION_SCHEMA.md
```

### 4. Updated Backtest Command

Added explicit anti-hallucination section at the top:

```markdown
## 🌶️ MANDATORY: Check Command Reference First

DO NOT HALLUCINATE OPTIONS. Common hallucinations to avoid:
- ❌ --algorithm - DOES NOT EXIST
- ❌ --baseline - DOES NOT EXIST
- ❌ --candidate - DOES NOT EXIST

ACTUAL OPTIONS: --git-reference, --algo-repo-url, etc.
```

Added algorithm override JSON examples with correct structure.

## Files Changed

### New Files
```
hotvect-claude-code-plugin/
├── HV_COMMAND_REFERENCE.md              [NEW] Authoritative command signatures
├── ALGORITHM_DEFINITION_SCHEMA.md        [NEW] Authoritative JSON schema
└── ANTI_HALLUCINATION_CHANGES.md         [NEW] This file
```

### Updated Files
```
hotvect-claude-code-plugin/
├── COMMON_PREAMBLE.md                    [UPDATED] Added Step 2 & 3
└── commands/
    └── backtest.md                       [UPDATED] Added anti-hallucination section
```

## How It Works

**Before (HALLUCINATING):**
```
User: backtest v74 and v81
Agent: hv backtest --algorithm my-algo --baseline v74 --candidate v81
       ❌ These options don't exist!
```

**After (CHECKING DOCS):**
```
User: backtest v74 and v81
Agent:
  1. cat ~/.hotvect/config.json
  2. cat HV_COMMAND_REFERENCE.md  ← NEW STEP
  3. Construct: hv backtest \
       --git-reference v74.0.0 \
       --git-reference v81.0.0 \
       --algo-repo-url ... \
       --data-base-dir ... \
       --output-base-dir ... \
       --scratch-dir ... \
       --last-test-time 2025-11-15
```

## Benefits

1. **No more hallucinated command options** - Agent reads actual signatures
2. **No more malformed JSON** - Agent reads actual schema
3. **Self-correcting** - Agent can look up commands when unsure
4. **Explicit anti-patterns** - Common mistakes are called out
5. **Authoritative source** - One place for command truth

## Verification

To test the improvements:
```bash
cd /path/to/work-directory
claude
```

Then try:
```
backtest v1 and v2 (my-algorithm)
```

Agent should:
1. Read config
2. **Read HV_COMMAND_REFERENCE.md**
3. Construct command with ACTUAL options: `--git-reference v1`, `--git-reference v2`, etc.
4. **NOT use**: `--algorithm`, `--baseline`, `--candidate`

## Pattern for Future Commands

When adding new commands/skills:
1. Document actual command signature in `HV_COMMAND_REFERENCE.md`
2. Add to preamble check in command file
3. Call out common hallucinations explicitly
4. Provide correct vs wrong examples

## Maintenance

**When `hv` commands change:**
1. Update `HV_COMMAND_REFERENCE.md`
2. All agents automatically get correct info (they read this file)

**When algorithm schema changes:**
1. Update `ALGORITHM_DEFINITION_SCHEMA.md`
2. All agents automatically get correct schema

## Related Improvements

This builds on the config-first pattern changes:
- Step 1: Read config (existing)
- **Step 2: Check command reference (NEW)**
- **Step 3: Check algorithm schema (NEW)**

Together these form a complete discovery strategy that eliminates both:
- Asking unnecessary questions (config-first)
- Hallucinating non-existent options (reference checking)
