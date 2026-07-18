"""Script to check that the versions have been updated correctly.

This script checks that all the versions match the target version as specified in the parameters.
It also checks that the version has changed in comparison to a previous revision.
Useful to run from the CI/CD. It only uses built-in libraries to make easy to run it.
It assumes that git is installed in the system.
"""

import sys
from pathlib import Path

from _version_common import (
    find_pom_files,
    get_delivery_yaml_version,
    get_git_pom_version,
    get_pom_version,
    get_pyproject_version,
)

MAIN_POM_PATH = Path("pom.xml")
DELIVERY_YAML_PATH = Path("delivery.yaml")


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

    #  Check delivery.yaml
    delivery_yaml_path = base_dir / DELIVERY_YAML_PATH
    try:
        delivery_version = get_delivery_yaml_version(delivery_yaml_path)
        print(f"🚀 delivery.yaml => {delivery_version}")
        if delivery_version != target_version:
            print(f"\n❌ delivery.yaml version mismatch: {delivery_version} != {target_version}")
            sys.exit(1)
    except Exception as e:
        print(f"❌ Failed to read delivery.yaml: {e}")
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
