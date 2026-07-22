from __future__ import annotations

import re
from pathlib import Path

TEXT_DATA_EXTENSIONS = (".jsonl", ".ndjson", ".json")
PART_TEXT_DATA_BASENAME_RE = re.compile(r"^part-\d+(?:-\d+)*$")


def _strip_optional_gzip_suffix(name: str) -> str:
    return name[:-3] if name.lower().endswith(".gz") else name


def is_part_text_data_filename(name: str) -> bool:
    if not name or name.startswith(".") or name.startswith("_"):
        return False

    uncompressed = _strip_optional_gzip_suffix(name)
    lower_name = uncompressed.lower()

    basename = lower_name
    for extension in TEXT_DATA_EXTENSIONS:
        if lower_name.endswith(extension):
            basename = lower_name[: -len(extension)]
            break
    else:
        if "." in lower_name:
            return False

    return PART_TEXT_DATA_BASENAME_RE.fullmatch(basename) is not None


def is_text_data_filename(name: str) -> bool:
    if not name or name.startswith(".") or name.startswith("_"):
        return False

    uncompressed = _strip_optional_gzip_suffix(name)
    lower_name = uncompressed.lower()

    if lower_name.startswith("part-"):
        return is_part_text_data_filename(name)

    return lower_name.endswith(TEXT_DATA_EXTENSIONS)


def is_text_data_file(path: Path) -> bool:
    return path.is_file() and is_text_data_filename(path.name)


def list_text_data_files(directory: Path) -> list[Path]:
    if not directory.is_dir():
        return []
    return [path for path in sorted(directory.iterdir()) if is_text_data_file(path)]


def list_part_text_data_files(directory: Path) -> list[Path]:
    if not directory.is_dir():
        return []
    return [path for path in sorted(directory.iterdir()) if path.is_file() and is_part_text_data_filename(path.name)]
