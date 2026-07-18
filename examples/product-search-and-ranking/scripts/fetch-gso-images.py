#!/usr/bin/env python3
import argparse
import hashlib
import json
from pathlib import Path
from urllib.request import Request, urlopen

from example_catalog import (
    GSO_CREATOR,
    GSO_LICENSE_NAME,
    GSO_LICENSE_URL,
    PRODUCTS,
    image_url,
    model_url,
)


OUTPUT_DIR = Path(__file__).resolve().parents[1] / "example-data" / "action-images"
USER_AGENT = "hotvect-example-product-ranker/1.0"


def get_bytes(url: str) -> bytes:
    request = Request(url, headers={"User-Agent": USER_AGENT})
    with urlopen(request, timeout=30) as response:
        return response.read()


def validate_bytes(filename: str, content: bytes, expected_sha256: str) -> None:
    if not content.startswith(b"\xff\xd8\xff"):
        raise ValueError(f"{filename} is not a JPEG")
    actual_sha256 = hashlib.sha256(content).hexdigest()
    if actual_sha256 != expected_sha256:
        raise ValueError(
            f"SHA-256 mismatch for {filename}: expected {expected_sha256}, got {actual_sha256}"
        )


def verify_existing() -> None:
    for product in PRODUCTS:
        path = OUTPUT_DIR / product.image_filename
        validate_bytes(product.image_filename, path.read_bytes(), product.image_sha256)
    print(f"Verified {len(PRODUCTS)} licensed product images in {OUTPUT_DIR}")


def fetch() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    for product in PRODUCTS:
        metadata = json.loads(get_bytes(model_url(product)))
        expected_thumbnail_path = (
            f"/GoogleResearch/models/{product.gso_model_id}/tip/files/thumbnails/0.jpg"
        )
        if metadata["name"] != product.gso_model_id:
            raise ValueError(f"Unexpected model name for {product.gso_model_id}")
        if metadata["owner"] != GSO_CREATOR.replace(" ", ""):
            raise ValueError(f"Unexpected owner for {product.gso_model_id}: {metadata['owner']}")
        if metadata["license_name"] != GSO_LICENSE_NAME:
            raise ValueError(f"Unexpected license for {product.gso_model_id}: {metadata['license_name']}")
        if metadata["license_url"].replace("http://", "https://") != GSO_LICENSE_URL:
            raise ValueError(
                f"Unexpected license URL for {product.gso_model_id}: {metadata['license_url']}"
            )
        if metadata["thumbnail_url"] != expected_thumbnail_path:
            raise ValueError(
                f"Unexpected thumbnail URL for {product.gso_model_id}: {metadata['thumbnail_url']}"
            )

        content = get_bytes(image_url(product))
        validate_bytes(product.image_filename, content, product.image_sha256)
        destination = OUTPUT_DIR / product.image_filename
        temporary = destination.with_suffix(".tmp")
        temporary.write_bytes(content)
        temporary.replace(destination)
        print(f"Fetched {product.image_filename}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Fetch the licensed Google Scanned Objects image subset")
    parser.add_argument(
        "--check",
        action="store_true",
        help="Verify the bundled files without using the network",
    )
    args = parser.parse_args()
    if args.check:
        verify_existing()
    else:
        fetch()


if __name__ == "__main__":
    main()
