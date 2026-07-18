"""Bump hotvect versions across files.

Usage:
    python scripts/bump_versions.py <major|minor|patch|X.Y.Z>
"""

import re
import sys
from pathlib import Path

from _version_common import (
    PomVersionInfo,
    bump_semver,
    find_pom_files,
    get_delivery_yaml_version,
    get_pom_version_info,
    get_pyproject_version,
    is_semver,
)

DELIVERY_YAML_PATH = Path("delivery.yaml")
PYPROJECT_TOML_PATH = Path("python/pyproject.toml")


def _replace_once(path: Path, pattern: str, new_version: str) -> None:
    content = path.read_text()
    matches = re.findall(pattern, content, flags=re.MULTILINE | re.DOTALL)
    if len(matches) != 1:
        raise RuntimeError(f"Expected exactly one match in {path}, found {len(matches)} for pattern: {pattern}")
    updated, count = re.subn(
        pattern,
        lambda m: f"{m.group(1)}{new_version}{m.group(2)}",
        content,
        count=1,
        flags=re.MULTILINE | re.DOTALL,
    )
    if count != 1:
        raise RuntimeError(f"Failed to update {path}")
    path.write_text(updated)


def _update_pyproject_version(path: Path, old_version: str, new_version: str) -> None:
    pattern = r'(\[project\].*?^\s*version\s*=\s*")' + re.escape(old_version) + r'("[^\n]*$)'
    _replace_once(path, pattern, new_version)


def _update_delivery_version(path: Path, old_version: str, new_version: str) -> None:
    pattern = r'(^\s*HOTVECT_VERSION:\s*")' + re.escape(old_version) + r'("[^\n]*$)'
    _replace_once(path, pattern, new_version)


def _update_pom_version(info: PomVersionInfo, old_version: str, new_version: str) -> None:
    if info.source == "project":
        pattern = (
            r"(<project\b.*?<artifactId>\s*hotvect-parent\s*</artifactId>.*?<version>\s*)"
            + re.escape(old_version)
            + r"(\s*</version>)"
        )
    elif info.source == "parent":
        pattern = (
            r"(<parent>.*?<artifactId>\s*hotvect-parent\s*</artifactId>.*?<version>\s*)"
            + re.escape(old_version)
            + r"(\s*</version>.*?</parent>)"
        )
    else:
        raise ValueError(f"Unknown pom version source for {info.path}: {info.source}")
    _replace_once(info.path, pattern, new_version)


def _resolve_new_version(current_version: str, requested: str) -> str:
    if requested in {"major", "minor", "patch"}:
        return bump_semver(current_version, requested)
    if not is_semver(requested):
        raise ValueError(
            f'Invalid version argument "{requested}". ' 'Use "major", "minor", "patch" or an explicit X.Y.Z version.'
        )
    return requested


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: python scripts/bump_versions.py <major|minor|patch|X.Y.Z>")
        return 1

    requested = sys.argv[1]
    base_dir = Path(__file__).parent.parent.parent

    pyproject_path = base_dir / PYPROJECT_TOML_PATH
    delivery_yaml_path = base_dir / DELIVERY_YAML_PATH
    pom_infos = [get_pom_version_info(pom_path) for pom_path in find_pom_files(base_dir)]

    version_by_file = {pyproject_path: get_pyproject_version(pyproject_path)}
    version_by_file[delivery_yaml_path] = get_delivery_yaml_version(delivery_yaml_path)
    for info in pom_infos:
        version_by_file[info.path] = info.version

    distinct_versions = sorted(set(version_by_file.values()))
    if len(distinct_versions) != 1:
        print("❌ Found inconsistent versions across files:")
        for path in sorted(version_by_file):
            print(f"  {path.relative_to(base_dir)} => {version_by_file[path]}")
        return 1

    current_version = distinct_versions[0]
    try:
        new_version = _resolve_new_version(current_version, requested)
    except ValueError as e:
        print(f"❌ {e}")
        return 1

    if new_version == current_version:
        print(f"ℹ️ Version is already {current_version}; nothing to update.")
        return 0

    _update_pyproject_version(pyproject_path, current_version, new_version)
    _update_delivery_version(delivery_yaml_path, current_version, new_version)
    for info in pom_infos:
        _update_pom_version(info, current_version, new_version)

    print(f"✅ Updated version: {current_version} -> {new_version}")
    print(f"   Updated {len(pom_infos)} pom.xml files + delivery.yaml + python/pyproject.toml")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
