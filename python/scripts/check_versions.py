"""Script to check that the versions have been updated correctly.

This script checks that all the versions match the target version as specified in the parameters.
It also checks that the version has changed in comparison to a previous revision.
Useful to run from the CI/CD. It only uses built-in libraries to make easy to run it.
It assumes that git is installed in the system.
"""
import subprocess
import sys
import tomllib
import xml.etree.ElementTree as ET
from pathlib import Path

MAIN_POM_PATH = Path("pom.xml")


def get_pyproject_version(pyproject_path: Path) -> str:
    with pyproject_path.open("rb") as f:
        pyproject = tomllib.load(f)
    return pyproject["project"]["version"]


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
        if version_elem is not None:
            return version_elem.text.strip()

        parent_elem = root.find("mvn:parent", ns)
        if parent_elem is not None:
            parent_version_elem = parent_elem.find("mvn:version", ns)
            if parent_version_elem is not None:
                return parent_version_elem.text.strip()

        raise ValueError("No version found in git POM")
    except subprocess.CalledProcessError as e:
        raise RuntimeError(f"Failed to read {pom_path} at rev {rev}: {e}")
    except Exception as e:
        raise RuntimeError(f"Failed to parse POM version from git: {e}")


def get_pom_version(pom_path: Path) -> str:
    tree = ET.parse(pom_path)
    root = tree.getroot()
    ns = {"mvn": "http://maven.apache.org/POM/4.0.0"}

    version_elem = root.find("mvn:version", ns)
    if version_elem is not None:
        return version_elem.text.strip()

    parent_elem = root.find("mvn:parent", ns)
    if parent_elem is not None:
        parent_version_elem = parent_elem.find("mvn:version", ns)
        if parent_version_elem is not None:
            return parent_version_elem.text.strip()

    raise ValueError(f"No version found in {pom_path}")


def find_pom_files(start_dir: Path) -> list[Path]:
    return list(start_dir.rglob("pom.xml"))


def main():
    base_dir = Path(__file__).parent.parent.parent
    print(f"base_dir: {base_dir}")
    pyproject_path = base_dir / "python" / "pyproject.toml"

    if len(sys.argv) < 2:
        print("Usage: python check_versions.py <target_version> [git_rev]")
        sys.exit(1)

    target_version = sys.argv[1]
    git_rev = sys.argv[2] if len(sys.argv) >= 3 else None

    print(f"🔍 Target version: {target_version}")

    #  Check pyproject.toml
    try:
        py_version = get_pyproject_version(pyproject_path)
        print(f"📦 pyproject.toml => {py_version}")
        if py_version != target_version:
            print(f"\n❌ pyproject.toml version mismatch: {py_version} != {target_version}")
            sys.exit(1)
    except Exception as e:
        print(f"❌ Failed to read pyproject.toml: {e}")
        sys.exit(1)

    #  Check all pom.xml files
    mismatches = []
    for pom_file in find_pom_files(base_dir):
        pom_version = get_pom_version(pom_file)
        print(f"📄 {pom_file.relative_to(base_dir)} => {pom_version}")
        if pom_version != target_version:
            mismatches.append((pom_file, pom_version))

    if mismatches:
        print("\n❌ Version mismatches found:")
        for path, version in mismatches:
            print(f"  {path.relative_to(base_dir)} => {version}")
        sys.exit(1)

    #  Git check for specific pom.xml
    if git_rev:
        old_version = get_git_pom_version(MAIN_POM_PATH, git_rev)
        print(f"📂 {MAIN_POM_PATH} at {git_rev} => {old_version}")
        if old_version == target_version:
            print(f"\n❌ Version in {MAIN_POM_PATH} has not changed from {git_rev}")
            sys.exit(1)
        else:
            print(f"✅ Version in {MAIN_POM_PATH} has changed from previous revision.")

    print("\n✅ All versions match.")


if __name__ == "__main__":
    main()
