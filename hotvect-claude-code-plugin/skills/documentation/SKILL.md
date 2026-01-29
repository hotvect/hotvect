---
name: documentation
description: Answers questions about hotvect by searching documentation and source code. Use when user asks about hotvect concepts, APIs, features, or usage.
allowed-tools: Read, Grep, Glob, Bash
---

# Hotvect Documentation Skill

## Purpose

## ⚠️ Configuration Protection Policy

**CRITICAL: Never modify `~/.hotvect/` configuration files.** See `CONFIG_PROTECTION_POLICY.md` for full policy.

This skill reads `~/.hotvect/config.json` for configuration but never modifies it. Work only in user-specified directories.

Automatically search and read hotvect documentation and source code to answer user questions about hotvect concepts, APIs, features, usage patterns, and troubleshooting.

## When to Invoke

Invoke this skill when:
- User asks "how does X work in hotvect?"
- User mentions hotvect concepts (namespaces, transformations, vectorization, etc.)
- User wants to know about hotvect APIs or interfaces
- User asks about CLI commands (`hv`, `hv-ext`)
- User needs help understanding hotvect patterns or best practices
- User encounters hotvect errors and needs troubleshooting
- User asks about migration between hotvect versions

**Do NOT invoke when:**
- User is asking about algorithm-specific code (not hotvect framework)
- Question is about running specific commands (use other skills/commands instead)
- Question is about AWS, S3, or infrastructure setup

## Configuration

**CRITICAL:** Read hotvect source location from `~/.hotvect/config.json` at skill invocation.

**When this skill activates:**
1. Read config: `cat ~/.hotvect/config.json`
2. Extract `hotvect_source_dir` field (e.g., `/path/to/hotvect`)
3. Use this path as base for all documentation and source code searches
4. **Fail fast** if config doesn't exist or `hotvect_source_dir` is not set
5. Tell user to run `/agent hotvect-setup-agent` if config is missing

## Documentation Locations

**All paths are relative to `hotvect_source_dir` from config.**

**Primary documentation (MkDocs site):**
- Base path: `${hotvect_source_dir}/docs/`
- Structure:
  - `index.md` - Home page
  - `quickstart.md` - Quick start guide
  - `highlevel/` - Concepts, motivation, tutorials, namespaces
  - `howto/` - Development guides, debugging, operations
  - `patterns/` - Parent-child algorithms, override files, data dependencies
  - `cli/usage.md` - CLI reference
  - `troubleshooting.md` - Common issues
  - `faq.md` - Frequently asked questions
  - `migration-guides/` - Version migration guides
  - `blog/` - What is hotvect, why Java

**Source code:**
- Java API: `${hotvect_source_dir}/hotvect-api/src/main/java/com/hotvect/api/`
- Core implementations: `${hotvect_source_dir}/hotvect-core/src/main/java/com/hotvect/core/`
- Python CLI: `${hotvect_source_dir}/python/hotvect/`
- CLAUDE.md: `${hotvect_source_dir}/CLAUDE.md` - Project-specific guidance

## Version Compatibility

**Important:** This skill works with hotvect v9 and v10, but some documentation files may not exist in v9:

**Files that MAY be missing in v9 (before backport):**
- `quickstart.md` - May not exist or be minimal
- `patterns/data-dependencies.md` - May not exist
- `patterns/override-files.md` - May not exist
- `patterns/parent-child-algorithms.md` - May not exist
- `troubleshooting.md` - May not exist
- `howto/predict-with-feature-logging.md` - Definitely doesn't exist (v10 only)

**When searching:**
1. Always check if file exists before reading
2. If file doesn't exist, inform user and suggest alternatives
3. Fall back to source code search if docs are missing

**Example check:**
```bash
# Check if file exists before reading
if [ -f "${HOTVECT_DIR}/docs/quickstart.md" ]; then
  cat "${HOTVECT_DIR}/docs/quickstart.md"
else
  echo "quickstart.md not available in this version"
fi
```

## Operations

### 1. Understand the Question

Parse user's question to identify:
- Topic area (concepts, CLI, API, troubleshooting, etc.)
- Specific keywords (namespace, transformation, vectorization, etc.)
- Question type (how-to, what-is, why, error help)

### 2. Load Configuration and Set Base Path

**FIRST:** Extract hotvect source directory from config:

```bash
# Read config and extract hotvect_source_dir
HOTVECT_DIR=$(jq -r '.hotvect_source_dir' ~/.hotvect/config.json)

# Verify it exists
if [ ! -d "$HOTVECT_DIR" ]; then
  echo "ERROR: hotvect_source_dir not found: $HOTVECT_DIR"
  exit 1
fi
```

All subsequent operations use `$HOTVECT_DIR` as the base path.

### 3. Search Documentation

**Step 1: Check relevant sections first**

Based on question topic, prioritize:
- Concepts → `${HOTVECT_DIR}/docs/highlevel/*.md`
- How-to → `${HOTVECT_DIR}/docs/howto/*.md`
- CLI commands → `${HOTVECT_DIR}/docs/cli/usage.md`
- Patterns → `${HOTVECT_DIR}/docs/patterns/*.md`
- Errors → `${HOTVECT_DIR}/docs/troubleshooting.md`
- API → Source code in `${HOTVECT_DIR}/hotvect-api/`

**Step 2: Keyword search across all docs**

```bash
# Search for keyword in all markdown files
grep -r "keyword" ${HOTVECT_DIR}/docs/ \
  --include="*.md" -i -n

# Search for class/interface in Java source
grep -r "class .*Keyword" ${HOTVECT_DIR}/hotvect-api/ \
  --include="*.java" -n
```

**Step 3: Read relevant files**

Read the most relevant markdown files and source files to extract information.

### 4. Search Source Code (if needed)

If documentation doesn't fully answer the question:

```bash
# Find Java interfaces/classes
find ${HOTVECT_DIR}/hotvect-api -name "*.java" | \
  xargs grep -l "interface.*Keyword"

# Find Python implementations
grep -r "class.*Keyword" ${HOTVECT_DIR}/python/hotvect/ \
  --include="*.py" -n
```

### 5. Synthesize Answer

Combine information from:
- Documentation content
- Code examples from docs
- Source code interfaces/implementations
- CLAUDE.md guidance

**Format answer with:**
- Direct answer to the question
- Code examples (if applicable)
- Links to relevant doc files (with line numbers)
- Links to source code (with file paths)
- Related concepts or follow-up reading

### 6. Verify Answer Quality

Before responding, ensure:
- Answer is based on actual documentation/code (not assumptions)
- Code examples are accurate and runnable
- File paths are correct
- Answer addresses the specific question asked

## Search Strategy

### For "What is" questions:
1. Check `highlevel/concepts.md`
2. Check `index.md` and `quickstart.md`
3. Search blog posts in `blog/`

### For "How to" questions:
1. Check `howto/` directory **IF IT EXISTS**
2. Check `patterns/` directory **IF IT EXISTS**
3. Fall back to `highlevel/concepts.md` or source code
4. If not found, inform user: "This topic is not documented in this version. I can search the source code instead."

### For CLI questions:
1. Check `cli/usage.md`
2. Check Python CLI source in `python/hotvect/`
3. Check CLAUDE.md for CLI examples

### For API questions:
1. Check Java interfaces in `hotvect-api/`
2. Check Javadoc comments
3. Check `highlevel/tutorials.md` for usage

### For error/troubleshooting:
1. Check `troubleshooting.md`
2. Search error message in docs
3. Check migration guides if version-related

## Expected Output

**Format:**

```
[Direct answer to question]

[Code example if applicable]

**References:**
- docs/path/to/file.md:123 - Description
- hotvect-api/src/.../File.java:45 - Interface definition

**Related topics:**
- [Topic 1] (see docs/path/to/file.md)
- [Topic 2] (see docs/path/to/file.md)
```

**Example:**

```
Namespaces in hotvect are used to organize features into logical groups and
control their scope during feature transformation.

Each namespace represents a distinct data source or computation stage. Features
computed within a namespace are isolated and can only interact with other
namespaces through explicit declarations.

**Example:**
```java
@Namespace("user_features")
public class UserFeatureComputing implements Computing<UserData> {
    // Feature computations here
}
```

**References:**
- docs/highlevel/namespaces.md:15 - Namespace identity concept
- hotvect-api/src/main/java/com/hotvect/api/data/Namespace.java:23 - Annotation definition

**Related topics:**
- Feature transformations (see docs/highlevel/concepts.md)
- Computing interface (see docs/howto/develop-a-re-ranker-with-hotvect.md)
```

## Error Handling

**No relevant documentation found:**
- Inform user that topic is not documented
- Offer to search source code instead
- Suggest user may need to check algorithm-specific docs

**Ambiguous question:**
- Ask clarifying questions
- List possible interpretations
- Show available topics in that area

**Multiple relevant sources:**
- Synthesize information from all sources
- Cite all relevant references
- Highlight if sources have different perspectives

## Communication

- Be precise and technical (user is likely a developer)
- Cite specific files and line numbers for credibility
- Include code examples when helpful
- Suggest related reading for deeper understanding
- Use dry, factual tone (no excessive positivity)
