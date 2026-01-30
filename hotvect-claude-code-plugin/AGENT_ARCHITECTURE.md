# HotVect Claude Plugin Architecture

## Overview

All agents, commands, and skills in the HotVect Claude plugin now follow a **mandatory discovery-first pattern** that eliminates unnecessary user questions by leveraging central configuration and intelligent context discovery.

## The Config-First Pattern

### 1. Read Config (MANDATORY FIRST STEP)

**Every agent/command/skill starts by reading:**
```bash
cat ~/.hotvect/config.json
```

This provides:
- `directories.data_base_dir` - Where training/test data lives
- `directories.output_base_dir` - Where training outputs go
- `directories.scratch_dir` - Temporary backtest artifacts
- `hotvect_source_dir` - Location of hotvect source (for venv)
- `aws.credential_helper` - Command for AWS credential refresh

### 2. Activate Virtual Environment

```bash
source ${hotvect_source_dir}/python/.venv/bin/activate
```

Required before running any `hv` or `hv-ext` commands.

### 3. Discover Context (BEFORE ASKING)

Use config values + intelligent discovery to gather context:

**Algorithm Discovery:**
- Check current directory for existing audit files → extract algorithm name
- Search `~/.m2/repository/` for recently installed JARs
- Inspect JAR contents to extract algorithm definitions
- Parse directory names for version hints (e.g., "v74-v81-regression")

**Test Data Discovery:**
- Look in `${data_base_dir}/` for data prefixes
- Find date-partitioned directories (`dt=YYYY-MM-DD`)
- Match algorithm requirements from definitions
- Select most recent available date

**Configuration Files:**
- Check for existing `renamings.json`, override files, etc.
- Don't ask if file exists in current directory

### 4. Only Ask When Genuinely Ambiguous

**Ask the user when:**
- Config doesn't exist (tell them to run hotvect-setup)
- Multiple equally valid options exist
- Something truly cannot be determined programmatically

**DO NOT ask when:**
- Information is in `~/.hotvect/config.json`
- Information can be discovered by searching
- Information is in existing files
- Information can be inferred from context

## File Structure

```
hotvect-claude-code-plugin/
├── COMMON_PREAMBLE.md          # Shared discovery strategies
├── AGENT_ARCHITECTURE.md        # This file
├── commands/                    # Slash commands (/hv:audit, etc.)
│   ├── audit.md
│   ├── audit-compare.md
│   ├── backtest.md
│   ├── train.md
│   └── ...
└── skills/                      # Autonomous skills
    ├── training-setup/SKILL.md
    ├── data-dependency-download/SKILL.md
    └── ...
```

## How It Works in Practice

### Example: `/hv:audit-compare v1 v2 --samples 2000`

**Old behavior (BAD):**
```
❌ Agent asks: "What is the algorithm name?"
❌ Agent asks: "What is the path to your test data?"
```

**New behavior (GOOD):**
```
✅ Read ~/.hotvect/config.json
✅ Activate venv from config.hotvect_source_dir
✅ Check for existing v1-audit.jsonl → extract algorithm name
✅ Search ~/.m2/repository/ for v1 and v2 JARs
✅ Look in config.directories.data_base_dir for test data
✅ Proceed with audit comparison
```

Agent only asks if something is genuinely ambiguous (e.g., multiple test dates available and user didn't specify).

## Implementation Details

### Common Preamble

Every command/skill/agent file now starts with:

```markdown
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
```

### Discovery Strategies Reference

See `COMMON_PREAMBLE.md` for comprehensive strategies:
- Algorithm JAR discovery
- Test data discovery
- Extracting metadata from existing files
- Inferring context from directory structures
- When to fail fast vs ask questions

## Error Handling

### Fail Fast with Actionable Messages

When something is missing, tell the user EXACTLY how to fix it:

**❌ Bad:**
```
Error: Could not find algorithm JAR
```

**✅ Good:**
```
Algorithm JAR not found at ~/.m2/repository/com/myorg/my-algorithm/1.0.0/my-algorithm-1.0.0.jar

Build and install with:
  cd /path/to/my-algorithm
  git checkout v1.0.0
  mvn clean install -DskipTests
```

### Config Missing

If `~/.hotvect/config.json` doesn't exist:

```
Configuration not found at ~/.hotvect/config.json

Run the hotvect setup to create it:
  /agent hotvect-setup-agent
```

## Benefits

1. **Dramatically fewer questions** - Most context discovered automatically
2. **Faster operations** - No waiting for user input on discoverable info
3. **Better UX** - Agent feels smarter and more helpful
4. **Consistent behavior** - All commands follow same pattern
5. **Easy debugging** - Always starts from known config state

## Maintenance

### Adding New Commands/Skills

1. Start with the config-first preamble (copy from existing files)
2. Implement discovery strategies from `COMMON_PREAMBLE.md`
3. Only add question prompts for genuinely ambiguous situations
4. Provide actionable error messages

### Updating Discovery Logic

Update `COMMON_PREAMBLE.md` with new strategies - all commands reference it.

## Related Files

- `COMMON_PREAMBLE.md` - Shared discovery strategies
- `~/.hotvect/config.json` - User's central configuration
- Individual command/skill files - Implementation details
