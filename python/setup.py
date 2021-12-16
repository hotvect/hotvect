# -*- coding: utf-8 -*-

from setuptools import setup, find_packages


with open('README.rst') as f:
    readme = f.read()

with open('LICENSE') as f:
    license = f.read()

setup(
    name='hotvect',
    version='0.0.4-SNAPSHOT',
    description='Hotvect python interface',
    long_description=readme,
    author='Enno Shioji',
    author_email='eshioji@gmail.com',
    url='https://github.com/eshioji/hotvect',
    license=license,
    packages=find_packages(exclude=('tests', 'docs'))
)