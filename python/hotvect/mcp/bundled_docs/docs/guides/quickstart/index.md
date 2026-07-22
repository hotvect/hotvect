---
title: Install and verify Hotvect
description: Build Hotvect from source and verify one coherent Java, Python, CLI, and documentation installation
tags: [installation, getting-started, beginner]
difficulty: beginner
prerequisites:
  - JDK 21
  - Python 3.11–3.13
  - Maven
  - Make
  - uv
related_docs:
  - ../first-run/index.md
  - ../example-product-algorithms/index.md
  - ../local-development-env/index.md
  - ../../concepts/index.md
  - ../../reference/cli/index.md
---

# Install and verify Hotvect

The current supported installation path builds a Hotvect source checkout. When it is complete, the Python CLI and its
bundled Java runtimes come from the same build, and the version-matched documentation is searchable from the command
line.

You need JDK 21, Python 3.11–3.13, Maven, Make, Git, and `uv`. The commands below assume a Unix-like shell on Linux
or macOS; this guide does not describe a native Windows setup. Verify the toolchain before starting the full build:

```bash
java -version
mvn -version
make --version
python3 --version
uv --version
git --version
```

## 1. Build and activate Hotvect

Clone the public repository if you do not already have a checkout:

```bash
git clone https://github.com/zalando/hotvect.git
cd hotvect
```

Then build and activate it:

```bash
cd python
make init
source .venv/bin/activate
```

`make init` synchronizes the Python environment, builds the Java modules, and copies the runtime JARs into the Python
package. Do not run a separate Maven build first.

## 2. Verify one coherent installation

```bash
hv --version
python -c 'import hotvect.hotvectjar; print(hotvect.hotvectjar.HOTVECT_JAR_PATH)'
```

`hv --version` should report the Python package and bundled JAR versions. The second command should print an existing
`hotvect-offline-util-...-jar-with-dependencies.jar` path.

If the JAR is missing after a source change, rebuild and copy it:

```bash
cd /path/to/hotvect/python
make quick
```

## 3. Verify the documentation lookup

```bash
hv docs search "local backtest" --limit 3
hv docs read concepts/how-hotvect-works/index.md
```

These commands emit JSON and do not run an algorithm. Use `hv <command> --help` to verify the flags exposed by the
installed checkout.

## 4. Run an algorithm

Installation is now complete. Continue with [Run the example product algorithms](../first-run/index.md). That guide
trains and evaluates the scorer → Ranker → TopK graph, then opens it in the local browser debugger without requiring
external data.

After the first run:

- [Study the example product algorithms](../example-product-algorithms/index.md) for their data contract, feature
  audit, implementation reading order, and image-license details.
- [Build your first algorithm](../first-algorithm/index.md) when you are starting a new project.
- [Tour an existing algorithm](../first-workflow/index.md) to understand a project before changing it.
- [Prepare local development](../local-development-env/index.md) when you have a repository and data access.
- Use the [CLI reference](../../reference/cli/index.md) only when you need exact command flags and output contracts.
