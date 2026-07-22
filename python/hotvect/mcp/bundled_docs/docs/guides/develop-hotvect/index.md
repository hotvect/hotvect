---
title: Release and version Hotvect
description: Keep the Hotvect framework version aligned across Maven, Python, delivery, and lock files
tags: [development, versioning]
difficulty: beginner
estimated_time: 5 minutes
prerequisites:
  - hotvect source checkout
  - Python 3.11–3.13 installed
related_docs:
  - ../../reference/version-compatibility/index.md
related_commands:
  - make bump-version
  - python scripts/check_versions.py
---

# Release and version Hotvect


## Versioning

Hotvect uses semantic versioning. For details consult [Hotvect Versions and Compatibility](../../reference/version-compatibility/index.md).

The version is specified and must match across multiple locations:
- `delivery.yaml`
- `pom.xml` files in Java packages
- `pyproject.toml` in Python package and `uv.lock`

To update the version consistently, run from `python/`

```bash
make bump-version VERSION=<major|minor|patch|X.Y.Z>
```

Examples:

```bash
make bump-version VERSION=patch
make bump-version VERSION=<X.Y.Z>
```
