# -*- coding: utf-8 -*-
from pathlib import Path

from setuptools import find_namespace_packages, find_packages, setup
from setuptools.command.build_py import build_py as _build_py


class build_py(_build_py):
    def build_package_data(self) -> None:
        # Setuptools doesn't clean `build/lib` between builds. If you previously built a wheel that bundled an older
        # hotvect JAR and then updated the repo JAR, stale `*.jar` files can remain and end up inside the next wheel.
        #
        # This causes confusing "multiple hotvect JARs found" behavior at runtime and unnecessarily bloats wheels.
        jar_out_dir = Path(self.build_lib) / "hotvect" / "hotvectjar"
        if jar_out_dir.exists():
            for jar in jar_out_dir.glob("*.jar"):
                jar.unlink(missing_ok=True)

        super().build_package_data()


packages = sorted(
    set(find_packages(exclude=("tests", "docs")) + find_namespace_packages(include=["hotvect.mcp.bundled_docs.docs*"]))
)


setup(
    name="hotvect",
    packages=packages,
    include_package_data=True,
    license_files=["LICENSE", "NOTICE"],
    scripts=[
        "bin/hv",
        "bin/hv-ext",
        "bin/hv-exp",
        "bin/hv-mcp",
        "bin/catboost_train",
        "bin/sagemaker-entrypoint",
    ],
    cmdclass={"build_py": build_py},
)
