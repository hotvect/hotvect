# Configuration Protection Policy

## ⚠️ CRITICAL: Never Modify `~/.hotvect/` Configs Directly

**DO NOT** modify any configuration files in `~/.hotvect/` unless explicitly working on updating configuration templates.

### Protected Files
- `~/.hotvect/config.json` - Central hotvect configuration
- `~/.hotvect/sagemaker-template.json` - SageMaker job template
- Any other files in `~/.hotvect/` directory

### Copy-First Pattern

When working with SageMaker configs or any hotvect configs:

1. **Always copy first**: Copy template from `~/.hotvect/` to working directory
2. **Modify the copy**: Make all changes to the copied file in working directory
3. **Never touch original**: Leave `~/.hotvect/` files unmodified

### Example: Creating SageMaker Config

```bash
# CORRECT ✅
cp ~/.hotvect/sagemaker-template.json ./my-backtest-config.json
# Edit ./my-backtest-config.json
# Use ./my-backtest-config.json with --sagemaker-config

# WRONG ❌
# Editing ~/.hotvect/sagemaker-template.json directly
```

### Why This Matters

- `~/.hotvect/` contains user's global configuration
- Modifying these files can break all hotvect operations
- Templates should remain pristine for reuse
- Working files belong in project directories, not global config

### Exceptions

Only modify `~/.hotvect/` configs when:
- Explicitly asked by user to update configuration templates
- Running setup operations that need to initialize user config
- User specifically requests: "update my hotvect config"

When in doubt, copy first and work on the copy.
