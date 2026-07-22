---
title: Run the example product algorithms
description: Generate a product index, train a ranker, and inspect candidate-free TopK requests in the local browser UI
tags: [getting-started, example, search, training, topk, demo-ui, beginner]
difficulty: beginner
prerequisites:
  - Completed the source installation
  - jq available for inspecting JSON
related_docs:
  - ../quickstart/index.md
  - ../example-product-algorithms/index.md
  - ../../concepts/how-hotvect-works/index.md
  - ../pipeline-stages/index.md
---

# Run the example product algorithms

This is the recommended first complete Hotvect run. It uses only files committed in the repository to:

1. load one algorithm package containing the scorer, Ranker, search-index generator, and TopK definitions;
2. generate the product-index state and train the CatBoost scorer from synthetic interactions;
3. package those dependencies through the candidate-free TopK graph;
4. evaluate the selected TopK runtime; and
5. inspect and edit search requests in the local browser UI.

The example needs no private repository, cloud account, or external training data. The product records are synthetic;
the twelve display images are licensed assets with committed attribution and hashes.

## 1. Activate the built checkout

Complete [Install and verify Hotvect](../quickstart/index.md), then return to the repository root:

```bash
cd /path/to/hotvect
source python/.venv/bin/activate

PROJECT_VERSION="$(mvn -q -f examples/product-search-and-ranking/product-ranker/pom.xml \
  help:evaluate -Dexpression=project.version -DforceStdout)"
ALGORITHM_JAR="examples/product-search-and-ranking/product-ranker/target/hotvect-example-product-ranker-${PROJECT_VERSION}-shaded.jar"
test -f "$ALGORITHM_JAR"
```

The shaded JAR is the current algorithm-package format. It contains the implementation and four embedded definitions:

```text
example-product-search-topk
  ├── example-product-search-index
  └── example-product-ranker
        └── example-product-scorer
```

## 2. Train and evaluate the complete graph

The fixture contains two training partitions and one test partition. Train the outer search TopK algorithm; Hotvect
generates and prepares its children recursively:

```bash
OUTPUT=output/example-product-first-run

hv train \
  --algorithm-name example-product-search-topk \
  --algorithm-jar "$ALGORITHM_JAR" \
  --data-base-dir examples/product-search-and-ranking/example-data \
  --output-base-dir "$OUTPUT" \
  --last-test-time 2000-01-03 \
  --target evaluate
```

Select the resulting TopK parameter package by its exact algorithm and parameter identities:

```bash
PARAMETERS="$OUTPUT/example-product-search-topk@$PROJECT_VERSION/last_test_date_2000-01-03/example-product-search-topk@$PROJECT_VERSION@last_test_date_2000-01-03.parameters.zip"
test -f "$PARAMETERS"
```

The run writes generated search-index files plus scorer, Ranker, and TopK parameter packages, predictions, evaluation
metadata, and logs. The outer TopK ZIP embeds the prepared child state and model. Inspect that composition directly:

```bash
unzip -l "$PARAMETERS"
```

These outputs remain separate from the algorithm package because trained or generated state can change without
rebuilding the implementation.

## 3. Open the trained algorithm in the browser

Start the local debugger with the search TopK package and its recorded test examples:

```bash
hv serve \
  --ui \
  --algorithm-name example-product-search-topk \
  --algorithm-jar "$ALGORITHM_JAR" \
  --parameter-path "$PARAMETERS" \
  --source-path examples/product-search-and-ranking/example-data/example_product_search_examples/dt=2000-01-03 \
  --action-metadata-path examples/product-search-and-ranking/example-data/action-metadata \
  --port 12001
```

Open `http://127.0.0.1:12001/`, then:

1. enter a query such as `wooden helicopter` and choose **Run**;
2. change `.k` from `4` to `2` and choose **Run**;
3. edit `.shared.preferred_category` or `.shared.budget` and inspect the changed candidates and scores;
4. click a result card to inspect its image attribution and display metadata; and
5. choose **Reset example** to restore the recorded input.

Stop the server with Ctrl+C when finished.

## Which identity should you see?

The UI loads the outer TopK algorithm, so its algorithm identity begins:

```text
example-product-search-topk@<project-version>
```

The complete runtime identity adds the selected parameter ID:

```text
example-product-search-topk@<project-version>@<parameter-id>
```

The search index, Ranker, and scorer are children of that outer runtime. Their identities begin
`example-product-search-index@<project-version>`, `example-product-ranker@<project-version>`, and
`example-product-scorer@<project-version>`. The definitions take their version from the Maven project. Run
`hv --version` to see the version of the built checkout; after a release bump, all four example identities change with it.

Because this example is trained, its runtime identity includes the selected parameter ID rather than `@NA`.

## What this proves

| Boundary | Evidence from this run |
| --- | --- |
| Algorithm package | One shaded JAR loads four definitions and their implementations |
| Training | The scorer produces a CatBoost model from synthetic interactions |
| Composition | The TopK parameter package contains generated index state and the Ranker/scorer parameter graph |
| Batch execution | Prediction and evaluation run over the fixed test partition |
| Interactive debugging | The browser invokes the local `BATCH/OFFLINE` runtime one edited example at a time |

This proves the local artifact and execution path, not production quality or online/offline parity.

Continue with [Understand the example product algorithms](../example-product-algorithms/index.md) for the data contract,
feature audit, implementation reading order, and license details. Then read
[How Hotvect works](../../concepts/how-hotvect-works/index.md) or
[build your first algorithm](../first-algorithm/index.md).
