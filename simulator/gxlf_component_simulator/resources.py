"""Locate source models or their self-contained installed-package copies."""
from pathlib import Path

PACKAGE_DIRECTORY = Path(__file__).resolve().parent
REPOSITORY_DIRECTORY = PACKAGE_DIRECTORY.parent.parent


def _directory(source, packaged, required):
    if (source / required).is_file():
        return source
    if (packaged / required).is_file():
        return packaged
    raise FileNotFoundError(f"Missing simulator resource: {required}")


MODEL_DIRECTORY = _directory(
    REPOSITORY_DIRECTORY / "examples" / "gxlf" / "scxml",
    PACKAGE_DIRECTORY / "models",
    "seed-source-state-machine.scxml",
)
