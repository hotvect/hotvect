# Hotvect example product algorithms

This Maven example family is a small, runnable product-search-and-ranking system. Its `product-ranker` module contains
the baseline implementation; `product-ranker-exploration` builds a separately loadable policy version for comparison in
the Demo UI. Together they contain one trainable CatBoost scorer, a catalogue-state generator, and three public
algorithm contracts:

```text
example-product-search-topk@1.2.3
  -> example-product-search-index@1.2.3
  -> example-product-ranker@1.2.3
       -> example-product-scorer@1.2.3

example-product-search-topk@1.3.0
  -> example-product-search-index@1.2.3
  -> example-product-ranker@1.3.0
       -> example-product-scorer@1.2.3
```

The search TopK receives a candidate-free request with query context and `k`. Its generated catalogue state retrieves
at least eight lexical candidates when the catalogue and requested `k` allow it, the ranker reranks them with Hotvect's
`BulkScoreGreedyRanker`, and TopK returns the requested decisions. Ranker version
`1.3.0` applies up to ±18% deterministic example/action-ID hash jitter to each model score, producing stable but
visibly different ordering for close candidates. Both ranker versions deliberately use the same algorithm name because
they implement the same `Ranker` input contract; the version identifies the policy implementation.

The committed fixtures contain synthetic product interactions. Product thumbnails are a curated, unchanged subset of
Google Scanned Objects and are licensed under CC BY 4.0; see `THIRD_PARTY_NOTICES.md` and the per-file manifest.

## Verify generated data and licensed assets

```bash
python3 examples/product-search-and-ranking/scripts/generate-example-data.py --check
python3 examples/product-search-and-ranking/scripts/fetch-gso-images.py --check
```

Running `fetch-gso-images.py` without `--check` re-downloads the exact files, validates the public source metadata and
license, and verifies every SHA-256 hash.

The lifecycle examples use the deliberately synthetic `2000-01-03` as the last test date. The two preceding partitions
are training data.

## Train, evaluate, and open the Demo UI

From the Hotvect repository root, after `python/.venv` has been initialized and activated:

```bash
PROJECT_VERSION="$(mvn -q -f examples/product-search-and-ranking/product-ranker/pom.xml \
  help:evaluate -Dexpression=project.version -DforceStdout)"
ALGORITHM_VERSION="$(mvn -q -f examples/product-search-and-ranking/product-ranker/pom.xml \
  help:evaluate -Dexpression=algorithm.version -DforceStdout)"
ALGORITHM_JAR="examples/product-search-and-ranking/product-ranker/target/hotvect-example-product-ranker-${PROJECT_VERSION}-shaded.jar"
test -f "$ALGORITHM_JAR"
OUTPUT=output/example-product-tutorial

hv train \
  --algorithm-name example-product-search-topk \
  --algorithm-jar "$ALGORITHM_JAR" \
  --data-base-dir examples/product-search-and-ranking/example-data \
  --output-base-dir "$OUTPUT" \
  --last-test-time 2000-01-03 \
  --target evaluate
```

The outer TopK run generates the catalogue index, trains the scorer, and packages the complete dependency graph. Start
the browser demo with the outer ZIP:

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

Open `http://127.0.0.1:8080`. Type directly into **Query** and press Enter; the request contains no candidate array
because candidates come from the packaged search index. Its shared context also contains preferred category and budget.
Advanced JSON editing remains available for those fields and `.k`. Click a result card to inspect its licensed image
metadata.
