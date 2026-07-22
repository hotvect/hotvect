---
title: Explore the example product algorithms
description: Generate a search index, train a CatBoost ranker, and explore candidate-free TopK requests in the Demo UI
tags: [quickstart, example, search, ranking, topk, catboost, demo-ui]
difficulty: beginner
prerequisites:
  - Completed Run the example product algorithms
  - jq for inspecting result JSON
related_docs:
  - ../quickstart/index.md
  - ../develop-algorithms/index.md
  - ../../concepts/dependencies-and-bindings/index.md
  - ../local-algorithm-debugging/index.md
---

# Explore the example product algorithms

The `examples/product-search-and-ranking` Maven example family is a self-contained algorithm tutorial. It includes Java
source, versioned algorithm definitions, deterministic synthetic interactions, a product catalogue fixture, and product
images for the Demo UI. The first run generates catalogue state and trains a small CatBoost model. It needs no private
repository, cloud account, or external training data.

```text
examples/product-search-and-ranking/
├── product-ranker/              # baseline scorer, ranker, search-index, and Search TopK
├── product-ranker-exploration/  # versioned exploration policy and dependent Search TopK
├── example-data/                # deterministic interactions, catalogue, and licensed images
└── scripts/                     # data and image verification/generation
```

The two ranker implementations keep the same name because they accept the same `Ranker` input contract. Their versions
identify the policy:

```text
example-product-search-topk@1.2.3
  ├── example-product-search-index@1.2.3
  └── example-product-ranker@1.2.3
        └── example-product-scorer@1.2.3

example-product-search-topk@1.3.0
  ├── example-product-search-index@1.2.3
  └── example-product-ranker@1.3.0
        └── example-product-scorer@1.2.3
```

| Algorithm version | Public interface | Responsibility |
| --- | --- | --- |
| `example-product-scorer@1.2.3` | `BulkScorer` | Compute six features and predict a score for every product |
| `example-product-ranker@1.2.3` | `Ranker` | Order candidates with `BulkScoreGreedyRanker` |
| `example-product-ranker@1.3.0` | `Ranker` | Apply up to ±18% stable example/action-ID hash jitter to each model score |
| `example-product-search-index@1.2.3` | state | Package the product catalogue and retrieve lexical candidates |
| `example-product-search-topk@1.2.3` | `TopK` | Accept a query, retrieve candidates, rerank them, and return `k` products |
| `example-product-search-topk@1.3.0` | `TopK` | Reuse the index and delegate reranking to the exploration Ranker |

Training the outer TopK algorithm resolves this graph recursively: Hotvect generates the catalogue state, trains the
scorer, packages it into the ranker, then packages both dependencies into the search TopK artifact.

## Run it first

[Run the example product algorithms](../first-run/index.md) is the canonical build, train, evaluate, and browser-debug
sequence. This page explains what that run exercised instead of repeating those commands.

If you open a new terminal for the inspections below, restore the artifact locations from the repository root:

```bash
source python/.venv/bin/activate
OUTPUT=output/example-product-first-run
PROJECT_VERSION="$(mvn -q -f examples/product-search-and-ranking/product-ranker/pom.xml \
  help:evaluate -Dexpression=project.version -DforceStdout)"
ALGORITHM_VERSION="$(mvn -q -f examples/product-search-and-ranking/product-ranker/pom.xml \
  help:evaluate -Dexpression=algorithm.version -DforceStdout)"
EXPLORATION_VERSION="$(mvn -q -f examples/product-search-and-ranking/product-ranker-exploration/pom.xml \
  help:evaluate -Dexpression=exploration.algorithm.version -DforceStdout)"
ALGORITHM_JAR="examples/product-search-and-ranking/product-ranker/target/hotvect-example-product-ranker-${PROJECT_VERSION}-shaded.jar"
test -f "$ALGORITHM_JAR"

EXPLORATION_JAR="examples/product-search-and-ranking/product-ranker-exploration/target/hotvect-example-product-ranker-exploration-${PROJECT_VERSION}-shaded.jar"
test -f "$EXPLORATION_JAR"
```

The first shaded JAR contains the scorer, baseline ranker, search-index, and search-TopK definitions. The second shades
the baseline implementation but excludes and replaces the ranker and TopK definition resources because those algorithms
reuse the same public names at the version reported by `$EXPLORATION_VERSION` (currently `1.3.0`). Keeping the
competing definitions in separate JARs makes both versions loadable side by side. The Maven artifact name describes the
build; the embedded Hotvect names describe the contracts.

## 1. Inspect the data contract

The scorer and ranker use candidate-bearing interaction rows under:

```text
examples/product-search-and-ranking/example-data/example_product_examples/
  dt=2000-01-01/   # training
  dt=2000-01-02/   # training
  dt=2000-01-03/   # test and Demo UI examples
```

The search TopK has a different public contract. Its rows live under
`example-data/example_product_search_examples/` and contain a query plus offline judgments, but no candidate array:

```json
{
  "example_id": "product-search-2000-01-03-000",
  "occurred_at": "2000-01-03T12:00:00Z",
  "shared": {
    "query": "bolt building kit",
    "preferred_category": "building",
    "budget": 34.9
  },
  "outcomes": [
    {"action_id": "construction-kit", "clicked": true},
    {"action_id": "toy-helicopter", "clicked": false}
  ],
  "k": 4
}
```

The product catalogue is a separate dated dataset under `example-data/example_product_catalog/`. During
`generate-state`, Hotvect validates it and packages `catalog.jsonl` into `example-product-search-index`. At runtime the
request supplies only `shared` and `k`; the index retrieves candidates, then the ranker orders them. The `outcomes`
array is evaluation data and is not passed to the algorithm request.

Both decoders reject unknown fields, duplicate IDs, invalid numeric values, and non-positive `k`. The search decoder
also rejects an `actions` field and requires exactly one clicked outcome in an offline example, making both the
retrieval and evaluation contracts explicit.

Verify that the committed rows and image files still match their deterministic generators and hashes:

```bash
python3 examples/product-search-and-ranking/scripts/generate-example-data.py --check
python3 examples/product-search-and-ranking/scripts/fetch-gso-images.py --check
```

These checks are offline. Running `fetch-gso-images.py` without `--check` deliberately contacts the public source,
checks its license metadata, downloads the selected thumbnails, and validates their SHA-256 hashes.

## 2. Inspect the evaluation evidence

The scorer definition declares a two-day training window with a one-day lag. With `2000-01-03` as the test date, the
run uses the two preceding partitions for training and writes separate parameter packages for the scorer, Ranker, and
TopK algorithm.

Locate the outer result and inspect its main ranking metrics:

```bash
RESULT="$OUTPUT/metadata/example-product-search-topk@$ALGORITHM_VERSION/last_test_date_2000-01-03/result.json"
test -f "$RESULT"

jq '.evaluate | {ndcg_at_10, map_at_10, roc_auc, pr_auc}' "$RESULT"
```

The fixture is intentionally tiny and deterministic. These values prove the pipeline and evaluation wiring; they are
not a product-quality benchmark.

## 3. Search the packaged catalogue in the Demo UI

Find the outer parameter ZIP and start the local UI:

```bash
PARAMETERS="$OUTPUT/example-product-search-topk@$ALGORITHM_VERSION/last_test_date_2000-01-03/example-product-search-topk@$ALGORITHM_VERSION@last_test_date_2000-01-03.parameters.zip"
test -f "$PARAMETERS"

hv serve \
  --algorithm-name example-product-search-topk \
  --algorithm-jar "$ALGORITHM_JAR" \
  --parameter-path "$PARAMETERS" \
  --source-path examples/product-search-and-ranking/example-data/example_product_search_examples/dt=2000-01-03 \
  --action-metadata-path examples/product-search-and-ranking/example-data/action-metadata \
  --port 8080 \
  --ui
```

Open `http://127.0.0.1:8080`, then try these interactions:

1. Type `wooden helicopter` into **Query** and press Enter. No JSON-path selection is needed.
2. Try `rainbow stacker` and observe a different candidate set and ordering.
3. Under **Advanced JSON editing**, select `.k`, change `4` to `2`, and run again; the result pane should contain two cards.
4. Change `.shared.preferred_category` or `.shared.budget` and inspect how reranking changes.
5. Click a card to inspect the product image, source, and license metadata.
6. Click `Algo:` to inspect the effective definition, then `Params:` to inspect the packaged state and model metadata.
7. Enter `0` for `.k`; the output pane should show `k must be a positive integer`. Reset the example and run again.

With one available runtime the UI uses a single result pane. To compare two versions of the `Search TopK` contract,
stop the first server and package the exploration version:

```bash
EXPLORATION_OUTPUT=output/example-product-exploration

hv train \
  --algorithm-name example-product-search-topk \
  --algorithm-jar "$EXPLORATION_JAR" \
  --data-base-dir examples/product-search-and-ranking/example-data \
  --output-base-dir "$EXPLORATION_OUTPUT" \
  --last-test-time 2000-01-03 \
  --target parameters
```

Create a local runtime config that points each version at its own JAR and parameter ZIP:

```bash
BASELINE_PARAMETERS="$OUTPUT/example-product-search-topk@$ALGORITHM_VERSION/last_test_date_2000-01-03/example-product-search-topk@$ALGORITHM_VERSION@last_test_date_2000-01-03.parameters.zip"
EXPLORATION_PARAMETERS="$EXPLORATION_OUTPUT/example-product-search-topk@$EXPLORATION_VERSION/last_test_date_2000-01-03/example-product-search-topk@$EXPLORATION_VERSION@last_test_date_2000-01-03.parameters.zip"
test -f "$BASELINE_PARAMETERS"
test -f "$EXPLORATION_PARAMETERS"
RUNTIME_CONFIG=output/example-product-runtimes.json

jq -n \
  --arg baseline_jar "$(realpath "$ALGORITHM_JAR")" \
  --arg baseline_parameters "$(realpath "$BASELINE_PARAMETERS")" \
  --arg exploration_jar "$(realpath "$EXPLORATION_JAR")" \
  --arg exploration_parameters "$(realpath "$EXPLORATION_PARAMETERS")" \
  '{runtimes: [
    {
      algorithm_jar: $baseline_jar,
      algorithm_name: "example-product-search-topk",
      parameter_path: $baseline_parameters
    },
    {
      algorithm_jar: $exploration_jar,
      algorithm_name: "example-product-search-topk",
      parameter_path: $exploration_parameters
    }
  ]}' > "$RUNTIME_CONFIG"

hv serve \
  --local-runtime-config "$RUNTIME_CONFIG" \
  --source-path examples/product-search-and-ranking/example-data/example_product_search_examples/dt=2000-01-03 \
  --action-metadata-path examples/product-search-and-ranking/example-data/action-metadata \
  --port 8080 \
  --ui
```

The selectors now show `example-product-search-topk@1.2.3` and `example-product-search-topk@1.3.0`. Both retrieve
the same candidates; the latter delegates reranking to the exploration policy. It hashes the example ID and action ID,
then uses the result for a bounded relative score adjustment. The same request always produces the same ranking, while
close candidates move enough to make the comparison useful. This is a visual comparison fixture, not a recommended
exploration or ranking policy.

Press `Ctrl+C` in the terminal when finished.

## 4. Inspect scorer features directly

The generated transformer declares these features:

- candidate category
- query/title token overlap
- preferred-category match
- budget fit
- popularity
- novelty

Audit two examples against the scorer parameters created by the outer training run:

```bash
SCORER_PARAMETERS="$OUTPUT/example-product-scorer@$ALGORITHM_VERSION/last_test_date_2000-01-03/example-product-scorer@$ALGORITHM_VERSION@last_test_date_2000-01-03.parameters.zip"
test -f "$SCORER_PARAMETERS"

hv audit \
  --algorithm-name example-product-scorer \
  --algorithm-jar "$ALGORITHM_JAR" \
  --parameter-path "$SCORER_PARAMETERS" \
  --source-path examples/product-search-and-ranking/example-data/example_product_examples/dt=2000-01-03 \
  --dest-path output/example-product-audit \
  --ordered \
  --samples 2

head -n 1 output/example-product-audit/part-00000.jsonl | jq .
```

This is the shortest path from the Java feature methods to their encoded values. Continue with
[feature audits](../feature-audits/index.md) for the general workflow.

## 5. Read the implementation in dependency order

Start with `examples/product-search-and-ranking/product-ranker`:

1. `ProductFeatures.java` and `ProductTransformerFactory.java` — feature computation and generated CatBoost transformer.
2. `example-product-scorer-algorithm-definition.json` — training window, features, and CatBoost options.
3. `ProductRankerFactory.java` — scorer dependency composed through `BulkScoreGreedyRanker`.
4. `ProductExplorationRankerFactory.java` — deterministic hash exploration layered on the same scorer-backed ranker.
5. `ProductCatalogStateGenerator.java` and `ProductCatalogStateFactory.java` — build and load the packaged catalogue.
6. `ProductCatalogState.java` — retrieve a lexical candidate pool with retrieval ranks and scores.
7. `ProductSearchTopKFactory.java` — retrieve candidates, call the ranker, and truncate to `k`.
8. `ProductExampleJson.java` and `ProductSearchExampleJson.java` — the strict ranking and search contracts.
9. The baseline definitions, plus `product-ranker-exploration`'s `1.3.0` ranker and search-TopK definitions —
   dependency edges, public input types, and versioned policies.

This mirrors the separation used by larger search systems: state owns the searchable catalogue, retrieval produces a
candidate pool, the ranker owns learned ordering, and TopK owns selection size and the candidate-free public request
shape.

## Data and image license

Product titles, queries, numeric attributes, and click labels are synthetic Hotvect tutorial data. The twelve unchanged
thumbnails are a curated subset of [Google Scanned Objects](https://research.google/blog/scanned-objects-by-google-research-a-dataset-of-3d-scanned-common-household-items/),
created by Google Research and licensed under
[Creative Commons Attribution 4.0 International](https://creativecommons.org/licenses/by/4.0/).

That license permits sharing and adaptation, including commercial use, provided its attribution and notice terms are
followed. Attribution, the license link, the unchanged-file declaration, per-file source URLs, and SHA-256 hashes are committed in `THIRD_PARTY_NOTICES.md` and
`example-data/action-images/manifest.json`. Image metadata is kept outside the algorithm request and joined by
`action_id` for the Demo UI, matching the serving boundary the UI is intended to demonstrate.
