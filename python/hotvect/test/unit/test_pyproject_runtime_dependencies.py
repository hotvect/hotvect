import tomllib
from pathlib import Path


def test_hotvect_runtime_declares_packaging_dependency() -> None:
    pyproject_path = Path(__file__).resolve().parents[3] / "pyproject.toml"
    pyproject = tomllib.loads(pyproject_path.read_text(encoding="utf-8"))

    assert "packaging" in pyproject["project"]["dependencies"]
