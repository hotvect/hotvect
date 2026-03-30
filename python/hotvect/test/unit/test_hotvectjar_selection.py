from pathlib import Path


def test_find_hotvect_jar_selects_highest_version(tmp_path: Path) -> None:
    from hotvect.hotvectjar import find_hotvect_jar

    older = tmp_path / "hotvect-offline-util-10.7.0-jar-with-dependencies.jar"
    newer = tmp_path / "hotvect-offline-util-10.11.0-jar-with-dependencies.jar"
    older.touch()
    newer.touch()

    selected = find_hotvect_jar("hotvect-offline-util-*-jar-with-dependencies.jar", jar_dir=tmp_path)
    assert selected == newer


def test_find_hotvect_jar_ignores_non_semver_like_versions(tmp_path: Path) -> None:
    from hotvect.hotvectjar import find_hotvect_jar

    dummy = tmp_path / "hotvect-offline-util-test-jar-with-dependencies.jar"
    real = tmp_path / "hotvect-offline-util-10.11.0-jar-with-dependencies.jar"
    dummy.touch()
    real.touch()

    selected = find_hotvect_jar("hotvect-offline-util-*-jar-with-dependencies.jar", jar_dir=tmp_path)
    assert selected == real
