# -*- coding: utf-8 -*-
import distutils.cmd
import os

# read the contents of your README file
import shutil
from pathlib import Path

from setuptools import find_packages, setup

this_directory = Path(__file__).parent
long_description = (this_directory / "README.rst").read_text()
required_dependencies = (this_directory / "requirements.txt").read_text().splitlines()


HOTVECT_VERSION = "8.0.1"


# Copy the hotvect jar so that it can be included in the build
def copy_hotvect_jar(target_module: str, target_suffix: str):
    target_location = os.path.join(this_directory, f"../{target_module}/target")

    # First, try to find a jar with a "-SNAPSHOT" suffix
    source_path = Path(target_location).glob(f"{target_module}-{HOTVECT_VERSION}-SNAPSHOT{target_suffix}.jar")
    source = next(iter(source_path), None)

    # If no "-SNAPSHOT" version is found, try to find the exact version
    if source is None:
        source_path = Path(target_location).glob(f"{target_module}-{HOTVECT_VERSION}{target_suffix}.jar")
        source = next(iter(source_path))

    # Copy over new jar
    dest = os.path.join(
        this_directory,
        "hotvect",
        "hotvectjar",
        os.path.basename(source),
    )
    shutil.copyfile(source, dest)
    assert os.path.isfile(dest)
    return source, dest


def clean_jars(dest_dir):
    # Clear destination dir
    for jar in Path(dest_dir).glob("*.jar"):
        print(f"Removing {jar}")
        os.remove(jar)


class CopyHotvectJarCommand(distutils.cmd.Command):
    description = "Install hotvect jar into python package"
    user_options = []

    def run(self):
        dest_dir = os.path.join(this_directory, "hotvect", "hotvectjar")
        clean_jars(dest_dir)
        print(f"Cleaned {Path(dest_dir).parent} of jars")
        for target_module, target_suffix in [
            ("hotvect-offline-util", "-jar-with-dependencies"),
        ]:
            src, dest = copy_hotvect_jar(target_module, target_suffix)
            print(f"Copied {src} to {dest}")

    def initialize_options(self):
        pass

    def finalize_options(self):
        pass


setup(
    name="hotvect",
    version=HOTVECT_VERSION,
    description="Hotvect python interface",
    long_description=long_description,
    author="Enno Shioji",
    author_email="eshioji@gmail.com",
    url="https://github.bus.zalan.do/Lounge-Data/hotvect",
    license="Apache License 2.0",
    packages=find_packages(exclude=("tests", "docs")),
    include_package_data=True,
    scripts=[
        "bin/hv",
        "bin/compare-audits",
        "bin/convert-catboost-tsv-to-json",
        "bin/backtest",
        "bin/catboost_train",
    ],
    install_requires=required_dependencies,
    cmdclass={
        "copy_hotvect_jar": CopyHotvectJarCommand,
    },
)
