import os
import shutil
import tomllib
from pathlib import Path

PYTHON_SUBMODULE_DIRECTORY = Path(__file__).parent.parent


def get_version_from_pyproject():
    with open(PYTHON_SUBMODULE_DIRECTORY / "pyproject.toml", "rb") as f:
        data = tomllib.load(f)
    return data["project"]["version"]


# Copy the hotvect jar so that it can be included in the build
def copy_hotvect_jar(target_module: str, target_suffix: str):
    hotvect_version = get_version_from_pyproject()
    target_location = PYTHON_SUBMODULE_DIRECTORY / f"../{target_module}/target"

    # First, try to find a jar with a "-SNAPSHOT" suffix
    source_path = Path(target_location).glob(f"{target_module}-{hotvect_version}-SNAPSHOT{target_suffix}.jar")
    source = next(iter(source_path), None)

    # If no "-SNAPSHOT" version is found, try to find the exact version
    if source is None:
        source_path = Path(target_location).glob(f"{target_module}-{hotvect_version}{target_suffix}.jar")
        source = next(iter(source_path))

    # Copy over new jar
    dest = os.path.join(PYTHON_SUBMODULE_DIRECTORY, "hotvect", "hotvectjar", os.path.basename(source))
    shutil.copyfile(source, dest)
    assert os.path.isfile(dest)
    return source, dest


def clean_jars(dest_dir):
    # Clear destination dir
    for jar in Path(dest_dir).glob("*.jar"):
        print(f"Removing {jar}")
        os.remove(jar)


def clean_and_copy_jars():
    dest_dir = os.path.join(PYTHON_SUBMODULE_DIRECTORY, "hotvect", "hotvectjar")
    clean_jars(dest_dir)
    print(f"Cleaned {Path(dest_dir).parent} of jars")
    for target_module, target_suffix in [
        ("hotvect-offline-util", "-jar-with-dependencies"),
        ("hotvect-algorithm-serve", "-jar-with-dependencies"),
        ("hotvect-algorithm-demo", "-jar-with-dependencies"),
    ]:
        src, dest = copy_hotvect_jar(target_module, target_suffix)
        print(f"Copied {src} to {dest}")


if __name__ == "__main__":
    clean_and_copy_jars()
