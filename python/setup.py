# -*- coding: utf-8 -*-

from setuptools import setup, find_packages

# read the contents of your README file
from pathlib import Path
this_directory = Path(__file__).parent
long_description = (this_directory / "README.rst").read_text()


setup(
    name='hotvect',
    version='2.0.0-dev',
    description='Hotvect python interface',
    long_description=long_description,
    author='Enno Shioji',
    author_email='eshioji@gmail.com',
    url='https://github.com/eshioji/hotvect',
    license='Apache License 2.0',
    packages=find_packages(exclude=('tests', 'docs')),
    install_requires=['scikit-learn', 'pandas']
)
