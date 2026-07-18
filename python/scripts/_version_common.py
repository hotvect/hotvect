"""Common helpers for version-related scripts.

This module intentionally uses only the Python standard library so scripts can be run directly in CI
without activating a project virtual environment.
"""

from __future__ import annotations

import re
import subprocess
import tomllib
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Literal

SEMVER_RE = re.compile(r"^\d+\.\d+\.\d+$")


@dataclass(frozen=True)
class PomVersionInfo:
    path: Path
    version: str
    source: Literal["project", "parent"]


def is_semver(version: str) -> bool:
    return bool(SEMVER_RE.fullmatch(version))


def parse_semver(version: str) -> tuple[int, int, int]:
    if not is_semver(version):
        raise ValueError(f"Version must match X.Y.Z, got: {version}")
    major, minor, patch = version.split(".")
    return int(major), int(minor), int(patch)


def bump_semver(version: str, bump_kind: str) -> str:
    major, minor, patch = parse_semver(version)
    if bump_kind == "major":
        return f"{major + 1}.0.0"
    if bump_kind == "minor":
        return f"{major}.{minor + 1}.0"
    if bump_kind == "patch":
        return f"{major}.{minor}.{patch + 1}"
    raise ValueError(f"Unknown bump kind: {bump_kind}")


def get_pyproject_version(pyproject_path: Path) -> str:
    with pyproject_path.open("rb") as f:
        pyproject = tomllib.load(f)
    return pyproject["project"]["version"]


def get_delivery_yaml_version(delivery_yaml_path: Path) -> str:
    content = delivery_yaml_path.read_text()
    match = re.search(r'^\s*HOTVECT_VERSION:\s*"([^"]+)"', content, re.MULTILINE)
    if not match:
        raise ValueError(f"HOTVECT_VERSION not found in {delivery_yaml_path}")
    return match.group(1)


def get_pom_version_info(pom_path: Path) -> PomVersionInfo:
    tree = ET.parse(pom_path)
    root = tree.getroot()
    ns = {"mvn": "http://maven.apache.org/POM/4.0.0"}

    version_elem = root.find("mvn:version", ns)
    if version_elem is not None and version_elem.text is not None:
        return PomVersionInfo(path=pom_path, version=version_elem.text.strip(), source="project")

    parent_elem = root.find("mvn:parent", ns)
    if parent_elem is not None:
        parent_version_elem = parent_elem.find("mvn:version", ns)
        if parent_version_elem is not None and parent_version_elem.text is not None:
            return PomVersionInfo(path=pom_path, version=parent_version_elem.text.strip(), source="parent")

    raise ValueError(f"No version found in {pom_path}")


def get_pom_version(pom_path: Path) -> str:
    return get_pom_version_info(pom_path).version


def get_git_pom_version(pom_path: Path, rev: str) -> str:
    try:
        result = subprocess.run(
            ["git", "show", f"{rev}:{pom_path.as_posix()}"],  # noqa: E231
            check=True,
            capture_output=True,
            text=True,
        )
        xml_data = result.stdout
        root = ET.fromstring(xml_data)
        ns = {"mvn": "http://maven.apache.org/POM/4.0.0"}

        version_elem = root.find("mvn:version", ns)
        if version_elem is not None and version_elem.text is not None:
            return version_elem.text.strip()

        parent_elem = root.find("mvn:parent", ns)
        if parent_elem is not None:
            parent_version_elem = parent_elem.find("mvn:version", ns)
            if parent_version_elem is not None and parent_version_elem.text is not None:
                return parent_version_elem.text.strip()

        raise ValueError("No version found in git POM")
    except subprocess.CalledProcessError as e:
        raise RuntimeError(f"Failed to read {pom_path} at rev {rev}: {e}") from e
    except Exception as e:
        raise RuntimeError(f"Failed to parse POM version from git: {e}") from e


def find_pom_files(start_dir: Path) -> list[Path]:
    # Ignore local scratch dirs that may contain cloned algorithm repos, etc.
    return [p for p in start_dir.rglob("pom.xml") if ".sagemaker" not in p.parts]
