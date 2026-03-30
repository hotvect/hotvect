---
title: Legacy Auto-Attach Data Design (Archived)
description: Historical context for the first and second iterations of SageMaker InputDataConfig auto-attachment
tags: [design, archive, sagemaker]
difficulty: reference
estimated_time: 3 minutes
related_docs:
  - ../../../design/sagemaker-configuration/index.md
  - ../../../design/sagemaker-inputdataconfig/index.md
---

# Legacy Auto-Attach Data Design (Archived)

This page preserves the historical record of how hotvect used to attach SageMaker `InputDataConfig` channels before the fully integrated workflow shipped in 2025. The content is intentionally brief—new work should follow the [Automatic SageMaker Configuration](../../../design/sagemaker-configuration/index.md) and [InputDataConfig Solution](../../../design/sagemaker-inputdataconfig/index.md) documents instead.

## Evolution in Three Iterations

1. **CLI utilities** – `hv-ext show-data-dependency` and `hv-ext populate-sagemaker-config` analyzed dependencies in a temporary clone and rewrote the SageMaker template on disk. This version duplicated build work and was fragile.
2. **Legacy assistant plugin workflow** – A now-removed assistant plugin automated those commands, but still depended on editing JSON files and managing temporary artifacts.
3. **Flag-based framework integration** – The first in-framework attempt added `--auto-attach-data` plus helper flags. Although better than the CLI scripts, it still treated auto-attach as optional and duplicated configuration precedence rules.

## Why Keep the Archive?

- **Traceability** – When reviewing old branches or incident timelines, you may run into references to the CLI commands or the removed `--auto-attach-data` flag. This page documents what those meant.
- **Migration breadcrumbs** – If you are upgrading custom tooling that called the old commands, the comparison table in git history helps explain which behaviors disappeared and why.
- **Deprecation reference** – Product notes or design reviews can link here to justify why the fully automatic behavior is now mandatory.

For implementation details and the current source of truth, see the modern design docs linked above. If you discover lingering mentions of the legacy workflow in other documents, please update them to reference the new architecture and point back to this archived summary if historical context is needed.
