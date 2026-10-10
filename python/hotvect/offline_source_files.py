from collections.abc import Sequence

SUPPORTED_OFFLINE_SOURCE_SUFFIXES = (
    ".txt",
    ".json",
    ".jsonl",
    ".jsons",
    ".csv",
    ".tsv",
    ".txt.gz",
    ".json.gz",
    ".jsonl.gz",
    ".jsons.gz",
    ".csv.gz",
    ".tsv.gz",
    ".avro",
)


def is_supported_offline_source_filename(filename: str) -> bool:
    lower_filename = filename.lower()
    uncompressed_filename = lower_filename[:-3] if lower_filename.endswith(".gz") else lower_filename
    if (
        uncompressed_filename.startswith("part-")
        and len(uncompressed_filename) > len("part-")
        and "." not in uncompressed_filename
    ):
        return True
    return lower_filename.endswith(SUPPORTED_OFFLINE_SOURCE_SUFFIXES)


def is_parallel_source_path(path_parts: Sequence[str]) -> bool:
    return (
        bool(path_parts)
        and not any(part.startswith("_") for part in path_parts)
        and is_supported_offline_source_filename(path_parts[-1])
    )
