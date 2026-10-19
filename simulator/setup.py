"""Bundle shared repository resources without maintaining duplicate source files.

Source checkouts load examples/ directly. Wheels and sdists carry private model
copies so the transitional Python simulator remains installable
without the Java server or a checkout of the rest of the repository.
"""
from pathlib import Path
from shutil import copytree

from setuptools import setup
from setuptools.command.build_py import build_py
from setuptools.command.sdist import sdist

PROJECT_DIRECTORY = Path(__file__).resolve().parent
PACKAGE = "gxlf_component_simulator"
RESOURCES = (
    ("models", PROJECT_DIRECTORY.parent / "examples" / "gxlf" / "scxml"),
)


def copy_resources(package_directory):
    for relative, source in RESOURCES:
        if not source.is_dir():
            # An sdist already contains the materialized package resources.
            source = PROJECT_DIRECTORY / PACKAGE / relative
        if not source.is_dir():
            raise FileNotFoundError(f"Missing shared resources: {relative}")
        copytree(source, package_directory / relative, dirs_exist_ok=True)


class BuildWithResources(build_py):
    def run(self):
        super().run()
        copy_resources(Path(self.build_lib) / PACKAGE)


class SourceWithResources(sdist):
    def make_release_tree(self, base_dir, files):
        super().make_release_tree(base_dir, files)
        copy_resources(Path(base_dir) / PACKAGE)


setup(cmdclass={"build_py": BuildWithResources, "sdist": SourceWithResources})
