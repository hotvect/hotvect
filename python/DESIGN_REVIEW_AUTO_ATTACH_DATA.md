# Design Review: Auto-Attach Data Implementation

## Summary of Design Evolution

The SageMaker InputDataConfig population feature has gone through three iterations:

1. **Initial Design**: Pure CLI approach with `hv-ext show-data-dependency` and `hv-ext populate-sagemaker-config`
2. **Intermediate Design**: Plugin-based approach using CLI commands
3. **Current Design**: Framework-integrated approach with `--auto-attach-data` flag in `hv backtest`

## Current Implementation (Framework-Integrated)

### Architecture

**Location**: Core backtest framework (`hotvect/backtest.py`)

**Entry Point**: New CLI flags on `hv backtest`:
- `--auto-attach-data` (boolean flag)
- `--auto-attach-data-default-s3-base` (optional S3 base URI)
- `--auto-attach-data-environment` (choices: production/test, default: production)

**Flow**:
```
hv backtest --auto-attach-data ...
    ↓
BacktestPipeline.__init__() (stores flags)
    ↓
BacktestPipeline.run_all()
    ↓
BacktestPipeline._execute_on_sagemaker()
    ↓
BacktestPipeline._attach_input_data_config()
    ↓ (for each dependency)
BacktestPipeline._resolve_dependency_s3_uri()
```

### Key Components

#### 1. CLI Arguments (`bin/hv`)
```python
parser.add_argument("--auto-attach-data", action='store_true',
    help="Automatically populate SageMaker InputDataConfig based on algorithm dependencies")
parser.add_argument("--auto-attach-data-default-s3-base",
    help="Default S3 base URI when dependencies don't specify explicit s3_uri")
parser.add_argument("--auto-attach-data-environment",
    choices=['production', 'test'], default='production',
    help="Environment key for dependency s3_uri maps")
```

#### 2. BacktestPipeline Integration
- Stores auto-attach flags in `__init__`
- Reads default S3 base from `~/.hotvect/config.json` if not provided
- Calls `_attach_input_data_config()` during SageMaker execution
- Uses `AlgorithmPipeline.data_dependencies()` to extract dependencies

#### 3. S3 URI Resolution (`_resolve_dependency_s3_uri()`)

Priority order:
1. **Explicit string**: `dependency.additional_properties['s3_uri']` = `"s3://bucket/path/"`
2. **Environment map**: `dependency.additional_properties['s3_uri']` = `{"production": "...", "test": "..."}`
   - Tries requested environment first
   - Falls back to: production → prod → test → staging → any key
3. **Default base**: `--auto-attach-data-default-s3-base` + `dependency.data_prefix`
4. **Fail**: Raises ValueError

#### 4. Channel Construction
```python
channel = {
    "ChannelName": dependency.data_prefix,
    "DataSource": {
        "S3DataSource": {
            "S3DataType": "S3Prefix",
            "S3Uri": resolved_s3_uri,
        }
    },
    "InputMode": "FastFile"  # From job definition or default
}
```

#### 5. Deduplication
- Uses `existing_channels = {channel.get("ChannelName") for channel in input_data_config}`
- Skips dependencies if channel already exists
- Preserves manually-specified channels in template

## Comparison with Previous Design

### What Changed

| Aspect | Previous Design | Current Design |
|--------|----------------|----------------|
| **Location** | CLI commands (`hv-ext`) | Framework (`backtest.py`) |
| **Invocation** | Two-step: analyze → populate | One flag: `--auto-attach-data` |
| **Dependency analysis** | Separate temp repo clone | Reuses existing JAR preparation |
| **Config modification** | External tool modifies file | Internal framework augments in-memory |
| **User workflow** | Plugin runs commands manually | User passes flag, framework handles it |
| **JAR builds** | Potentially builds twice | Builds once (reuses existing) |
| **Visibility** | Explicit JSON intermediate output | Transparent (logged only) |

### What Was Removed

1. **`hv-ext show-data-dependency`**: Command that analyzed dependencies and output JSON
2. **`hv-ext populate-sagemaker-config`**: Command that populated InputDataConfig from dependencies
3. **Plugin workflow in backtest.md section 4.5**: Multi-step manual process
4. **Separate temp directory cloning**: For dependency analysis

### What Remains

1. **CLI commands still exist** in codebase:
   - `hotvect/extra/commands/show_data_dependency.py`
   - `hotvect/extra/commands/populate_sagemaker_config.py`
   - Still registered in `hotvect/extra/cli.py`
   - **Status**: Orphaned - not used by plugin anymore

2. **Documentation references**:
   - `agents/backtest.md` still references `hv-ext populate-sagemaker-config`
   - `skills/debug-command-storage/SKILL.md` mentions `show-data-dependency` in examples
   - `SAGEMAKER_INPUTDATACONFIG_SOLUTION.md` documents the old approach

3. **Configuration Protection Policy**: Still valid and unchanged

## Design Analysis

### Advantages of Current Design

1. **Simpler User Experience**
   - Single flag instead of multi-step workflow
   - No intermediate JSON files to manage
   - Consistent with other `hv backtest` options

2. **Efficiency**
   - No double JAR building
   - Reuses existing algorithm preparation
   - No separate repo cloning

3. **Consistency**
   - Same code path whether auto-attach is enabled or not
   - Per-job config deep copy works the same way
   - Job name randomization unchanged

4. **Maintainability**
   - Logic in one place (backtest.py)
   - No cross-process coordination needed
   - Easier to test (unit tests can mock dependencies)

5. **Safety**
   - Modifies in-memory copy only (per job)
   - Original template never touched
   - Existing channels preserved

### Disadvantages of Current Design

1. **Less Visibility**
   - No intermediate JSON to inspect
   - Harder to debug what dependencies were found
   - Can't preview InputDataConfig before execution

2. **Less Composability**
   - Can't use dependency analysis for other tools
   - Tight coupling to backtest framework
   - No standalone dependency inspection

3. **Plugin Complexity**
   - Plugin (backtest agent) now needs to understand framework behavior
   - Can't provide step-by-step guidance
   - Loses educational value of explicit steps

4. **Testing Difficulty**
   - Harder to test dependency resolution in isolation
   - Requires full backtest setup for testing
   - No standalone command for quick checks

### Inconsistencies and Issues

1. **Orphaned CLI Commands**
   - `show_data_dependency.py` and `populate_sagemaker_config.py` still exist
   - Still registered in CLI but not used by plugin
   - **Decision needed**: Remove or keep for debugging?

2. **Documentation Mismatch**
   - `agents/backtest.md` section 4.5 references removed workflow
   - **Needs update**: Rewrite section 4.5 to use `--auto-attach-data` flag
   - Examples show old `hv-ext` commands
   - **Needs update**: Show new flag-based approach

3. **Solution Document Outdated**
   - `SAGEMAKER_INPUTDATACONFIG_SOLUTION.md` describes old design
   - **Needs update**: Document current framework approach
   - References CLI commands that are now secondary

4. **Default S3 Base Fallback**
   - Framework reads `~/.hotvect/config.json` in BacktestPipeline
   - **Pro**: User doesn't need to pass flag if config exists
   - **Con**: Silent fallback, unclear where value comes from
   - **Current behavior**: Best-effort read with debug log on failure

5. **Error Messages**
   - ValueError when S3 URI can't be resolved
   - **Good**: Fails loudly (no defensive programming)
   - **Question**: Should we pre-check dependencies before starting backtest?
   - **Current**: Fails during SageMaker job submission (late)

## Recommendations

### Critical Updates Needed

1. **Update `agents/backtest.md` Section 4.5**
   - Remove references to `hv-ext populate-sagemaker-config`
   - Document `--auto-attach-data` flag usage
   - Show how to pass default S3 base
   - Explain when to use production vs test environment
   - Include example with all flags

2. **Update `HV_COMMAND_REFERENCE.md`**
   - Already done - shows the three new flags
   - ✅ Complete

3. **Update or Remove `SAGEMAKER_INPUTDATACONFIG_SOLUTION.md`**
   - Option A: Rewrite to document current approach
   - Option B: Remove (now obsolete)
   - Option C: Add "Historical" section and append current design

### Decision Points

1. **Keep or Remove Orphaned CLI Commands?**

   **Option A: Remove Entirely**
   - Clean codebase
   - No confusion about which approach to use
   - **Con**: Loses debugging capability

   **Option B: Keep for Debugging**
   - Useful for inspecting dependencies without running backtest
   - Helpful for developing new algorithms
   - **Con**: Maintenance burden, documentation confusion

   **Option C: Mark as Deprecated but Keep**
   - Add deprecation warnings
   - Document as "advanced debugging tools"
   - **Con**: Still confusing

   **Recommendation**: **Option B** - Keep for debugging but document clearly as "advanced inspection tools, not for normal workflow"

2. **Pre-check Dependencies Before Backtest?**

   **Current**: Dependencies checked during SageMaker execution (late failure)

   **Alternative**: Add validation step in `run_all()` before scheduling
   ```python
   if auto_attach_data and sagemaker_training_job_definition:
       # Dry-run dependency resolution to catch errors early
       self._validate_auto_attach_dependencies()
   ```

   **Recommendation**: **Add pre-check** - Fail fast before expensive JAR building

3. **Make Default S3 Base Required or Optional?**

   **Current**: Optional, reads from config as fallback

   **Considerations**:
   - Users with explicit s3_uri in algorithms don't need default
   - Users without config.json will get confusing late failures
   - Silent fallback makes debugging harder

   **Recommendation**: **Keep current behavior** but improve logging - always log where default came from

### Nice-to-Have Improvements

1. **Dry-Run Mode**
   - Add `--auto-attach-data-dry-run` flag
   - Prints what channels would be attached without executing
   - Helps users verify before expensive backtest

2. **Verbose Logging**
   - Add debug logs showing:
     - Each dependency found
     - S3 URI resolution for each
     - Which channels added vs skipped
   - Controlled by existing `--verbose` or similar flag

3. **Config Validation**
   - Warn if `--auto-attach-data` used without default S3 base
   - Warn if algorithm has no dependencies
   - Suggest checking algorithm definition if empty

## Migration Path

### For Plugin (backtest agent)

**Current section 4.5** references obsolete workflow. Needs rewrite:

**New Section 4.5 Content**:
```markdown
### 4.5. Prepare SageMaker Configuration (REQUIRED by default)

**CRITICAL:** When using `--sagemaker-config`, you must populate InputDataConfig.

Use `--auto-attach-data` flag to automatically populate InputDataConfig from algorithm dependencies:

```bash
# Read default S3 base from config (optional if in ~/.hotvect/config.json)
DEFAULT_S3_BASE=$(jq -r '.sagemaker.default_s3_data_base_dir' ~/.hotvect/config.json)

# Copy template to scratch dir
TEMPLATE_PATH=$(jq -r '.sagemaker.sagemaker_config_template' ~/.hotvect/config.json)
TEMPLATE_PATH="${TEMPLATE_PATH/#\~/$HOME}"
cp "${TEMPLATE_PATH}" ${scratch_dir}/sagemaker-config.json

# Run backtest with auto-attach
hv backtest \
  --git-reference ${baseline_ref} \
  --git-reference ${treatment_ref} \
  --algo-repo-url ${repo_url} \
  --output-base-dir ${output_dir} \
  --scratch-dir ${scratch_dir} \
  --last-test-time ${test_date} \
  --sagemaker-config ${scratch_dir}/sagemaker-config.json \
  --auto-attach-data \
  --auto-attach-data-default-s3-base "${DEFAULT_S3_BASE}" \
  --auto-attach-data-environment production
```

**What this does**:
- Framework analyzes algorithm dependencies during JAR preparation
- Resolves S3 URIs (explicit s3_uri > default base)
- Populates InputDataConfig channels automatically
- Preserves any manually-specified channels in template

**When to use test environment**:
```bash
--auto-attach-data-environment test
```
Use this when algorithm dependencies have environment-specific s3_uri maps.
```

### For Users

**Before (Old Workflow)**:
```bash
# Step 1: Analyze dependencies
hv-ext show-data-dependency --repo-url ... --git-reference ... -o deps.json

# Step 2: Populate config
hv-ext populate-sagemaker-config --dependencies-json deps.json ...

# Step 3: Run backtest
hv backtest --sagemaker-config ./sagemaker-config.json ...
```

**After (New Workflow)**:
```bash
# Single command with flag
hv backtest \
  --sagemaker-config ./sagemaker-config.json \
  --auto-attach-data \
  --auto-attach-data-default-s3-base s3://bucket/tables/ \
  ...
```

### For CLI Commands

Only `hv-ext show-data-dependency` remains, and purely as an **advanced debugging** tool (inspect dependencies without running a backtest). The former `hv-ext populate-sagemaker-config` command has been removed entirely to avoid confusion.

## Conclusion

The framework-integrated design is **architecturally superior** for the primary use case (running backtests), but loses some **visibility and composability** of the CLI approach.

**Critical work needed**:
1. ✅ **DONE**: CLI arguments added
2. ✅ **DONE**: Framework integration implemented
3. ✅ **DONE**: `HV_COMMAND_REFERENCE.md` updated
4. ✅ **DONE**: Update `agents/backtest.md` section 4.5
5. ✅ **DONE**: Update `SAGEMAKER_INPUTDATACONFIG_SOLUTION.md`
6. ✅ **DONE**: Remove orphaned CLI commands
7. ❌ **TODO**: Add early validation (recommended)
8. ✅ **DONE**: Improve logging

**Overall assessment**: The design change is **sound and improves the primary workflow**, but documentation and cleanup are incomplete.
