# -*- coding: utf-8 -*-
from setuptools import find_packages, setup

setup(
    name="hotvect",
    packages=find_packages(exclude=("tests", "docs")),
    include_package_data=True,
    license_files=["LICENSE", "NOTICE"],
    scripts=[
        "bin/hv",
        "bin/hv-ext",
        "bin/catboost_train",
        "bin/sagemaker-entrypoint",
    ],
)
