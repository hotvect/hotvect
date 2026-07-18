#!/usr/bin/env python3
import argparse
import base64
import json
from pathlib import Path

from example_catalog import (
    GSO_CREATOR,
    GSO_DATASET,
    GSO_DATASET_URL,
    GSO_LICENSE_NAME,
    GSO_LICENSE_URL,
    PRODUCTS,
    image_url,
    model_url,
)


MODULE_DIR = Path(__file__).resolve().parents[1]
DATA_DIR = MODULE_DIR / "example-data"
EXAMPLES_DIR = DATA_DIR / "example_product_examples"
SEARCH_EXAMPLES_DIR = DATA_DIR / "example_product_search_examples"
CATALOG_PATH = DATA_DIR / "example_product_catalog" / "dt=2000-01-02" / "part-00000.jsonl"
METADATA_PATH = DATA_DIR / "action-metadata" / "products.jsonl"
MANIFEST_PATH = DATA_DIR / "action-images" / "manifest.json"
TRAINING_DATES = ("2000-01-01", "2000-01-02")
TEST_DATE = "2000-01-03"


def action(product, clicked: bool) -> dict:
    return {
        "action_id": product.action_id,
        "title": product.title,
        "category": product.category,
        "price": product.price,
        "popularity": product.popularity,
        "novelty": product.novelty,
        "clicked": clicked,
    }


def example(date: str, index: int, product_index: int, query: str, rotation: int) -> dict:
    selected = PRODUCTS[product_index]
    ordered_products = PRODUCTS[rotation:] + PRODUCTS[:rotation]
    return {
        "example_id": f"product-{date}-{index:03d}",
        "occurred_at": f"{date}T12:00:00Z",
        "shared": {
            "query": query,
            "preferred_category": selected.category,
            "budget": round(selected.price + 5.0, 2),
        },
        "actions": [
            action(product, product.action_id == selected.action_id)
            for product in ordered_products
        ],
        "k": 4,
    }


def examples_for_date(date: str, training: bool) -> list[dict]:
    examples = []
    queries_per_product = 2 if training else 1
    for product_index, product in enumerate(PRODUCTS):
        queries = product.training_queries if training else (product.test_query,)
        for query_index, query in enumerate(queries):
            index = product_index * queries_per_product + query_index
            rotation = (product_index * 5 + query_index * 3 + int(date[-2:])) % len(PRODUCTS)
            examples.append(example(date, index, product_index, query, rotation))
    return examples


def search_example(ranking_example: dict) -> dict:
    return {
        "example_id": ranking_example["example_id"].replace("product-", "product-search-", 1),
        "occurred_at": ranking_example["occurred_at"],
        "shared": ranking_example["shared"],
        "outcomes": [
            {
                "action_id": action_row["action_id"],
                "clicked": action_row["clicked"],
            }
            for action_row in ranking_example["actions"]
        ],
        "k": ranking_example["k"],
    }


def catalog_row(product) -> dict:
    return {
        "action_id": product.action_id,
        "title": product.title,
        "category": product.category,
        "price": product.price,
        "popularity": product.popularity,
        "novelty": product.novelty,
    }


def jsonl(rows: list[dict]) -> bytes:
    text = "".join(json.dumps(row, separators=(",", ":")) + "\n" for row in rows)
    return text.encode("utf-8")


def generated_files() -> dict[Path, bytes]:
    files = {}
    for date in TRAINING_DATES:
        ranking_examples = examples_for_date(date, training=True)
        files[EXAMPLES_DIR / f"dt={date}" / "part-00000.jsonl"] = jsonl(ranking_examples)
        files[SEARCH_EXAMPLES_DIR / f"dt={date}" / "part-00000.jsonl"] = jsonl(
            [search_example(row) for row in ranking_examples]
        )
    ranking_test_examples = examples_for_date(TEST_DATE, training=False)
    files[EXAMPLES_DIR / f"dt={TEST_DATE}" / "part-00000.jsonl"] = jsonl(ranking_test_examples)
    files[SEARCH_EXAMPLES_DIR / f"dt={TEST_DATE}" / "part-00000.jsonl"] = jsonl(
        [search_example(row) for row in ranking_test_examples]
    )
    files[CATALOG_PATH] = jsonl([catalog_row(product) for product in PRODUCTS])

    metadata = []
    manifest_assets = []
    for product in PRODUCTS:
        image_bytes = (DATA_DIR / "action-images" / product.image_filename).read_bytes()
        image_data_url = "data:image/jpeg;base64," + base64.b64encode(image_bytes).decode("ascii")
        metadata.append(
            {
                "action_id": product.action_id,
                "action_name": product.title,
                "action_image_url": image_data_url,
                "category": product.category,
                "price_eur": product.price,
                "source_dataset": GSO_DATASET,
                "image_creator": GSO_CREATOR,
                "image_source_url": model_url(product),
                "image_license": GSO_LICENSE_NAME,
                "image_license_url": GSO_LICENSE_URL,
            }
        )
        manifest_assets.append(
            {
                "file": product.image_filename,
                "sha256": product.image_sha256,
                "gso_model_id": product.gso_model_id,
                "source_model_url": model_url(product),
                "source_file_url": image_url(product),
            }
        )
    files[METADATA_PATH] = jsonl(metadata)
    manifest = {
        "dataset": GSO_DATASET,
        "dataset_url": GSO_DATASET_URL,
        "creator": GSO_CREATOR,
        "license": GSO_LICENSE_NAME,
        "license_url": GSO_LICENSE_URL,
        "modifications": "None. Files are the source thumbnails as downloaded.",
        "assets": manifest_assets,
    }
    files[MANIFEST_PATH] = (json.dumps(manifest, indent=2) + "\n").encode("utf-8")
    return files


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate deterministic product examples and metadata")
    parser.add_argument("--check", action="store_true", help="Fail if committed generated files differ")
    args = parser.parse_args()
    files = generated_files()
    for path, expected in files.items():
        if args.check:
            if path.read_bytes() != expected:
                raise ValueError(f"Generated file is stale: {path}")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(expected)
    verb = "Verified" if args.check else "Generated"
    print(f"{verb} {len(files)} deterministic example-data files")


if __name__ == "__main__":
    main()
