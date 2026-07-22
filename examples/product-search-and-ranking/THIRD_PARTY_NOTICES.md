# Third-party notices

The JPEG files under `example-data/action-images/` are thumbnails from the
[Google Scanned Objects](https://research.google/blog/scanned-objects-by-google-research-a-dataset-of-3d-scanned-common-household-items/) dataset.
The same image bytes are also embedded as Base64 data URLs in
`example-data/action-metadata/products.jsonl` so the local Demo UI can run without a separate image server.

- Creator: Google Research
- License: [Creative Commons Attribution 4.0 International](https://creativecommons.org/licenses/by/4.0/)
- Changes: none; the downloaded thumbnails are copied byte-for-byte into the fixture files and Base64 data URLs
- Per-file source URLs and SHA-256 hashes: `example-data/action-images/manifest.json`

The product titles, categories, prices, popularity values, novelty values, queries, and click labels in this module
are synthetic Hotvect example data. They are not Google Scanned Objects annotations.
