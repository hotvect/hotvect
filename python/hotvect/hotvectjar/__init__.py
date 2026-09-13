import logging
import os
import re
from pathlib import Path

logger = logging.getLogger(__name__)


def _extract_version_from_jar_name(jar_name: str) -> str | None:
    match = re.match(r"^hotvect-[a-z0-9-]+-(.+)-jar-with-dependencies\.jar$", jar_name)
    if not match:
        return None
    return match.group(1)


def _normalize_version_for_sorting(version_str: str) -> str:
    # CDP snapshot builds produce JARs like `10.13.4-SNAPSHOT`; normalize them so they
    # sort after older releases but before the exact same released version.
    if version_str.endswith("-SNAPSHOT"):
        return version_str.removesuffix("-SNAPSHOT") + ".dev0"
    return version_str


def _override_env_var(pattern: str) -> str | None:
    if pattern.startswith("hotvect-offline-util-"):
        return "HOTVECT_OFFLINE_UTIL_JAR"
    if pattern.startswith("hotvect-algorithm-demo-"):
        return "HOTVECT_ALGORITHM_DEMO_JAR"
    return None


def find_hotvect_jar(pattern: str, *, jar_dir: Path | None = None) -> Path:
    """
    Find the bundled `hotvect-offline-util-...-jar-with-dependencies.jar`.

    Historically we asserted that exactly one matching JAR exists. In practice, users can end up with multiple
    bundled JARs (e.g. upgrading `hotvect` without cleaning old site-packages files), which would break imports.
    To be robust, pick the highest semantic version when multiple matches exist, and warn.
    """
    from packaging.version import InvalidVersion, Version

    env_var = _override_env_var(pattern)
    if env_var:
        override = os.environ.get(env_var)
        if override:
            path = Path(override).expanduser()
            if not path.is_file():
                raise FileNotFoundError(f"{env_var} points to a missing hotvect JAR: {path}")
            return path

    root = jar_dir or Path(__file__).parent
    jars = sorted(root.glob(pattern))
    if not jars:
        raise FileNotFoundError(f"No hotvect JAR found for pattern {pattern!r} in {root}")

    if len(jars) == 1:
        return jars[0]

    def _sort_key(path: Path) -> tuple[int, Version, str]:
        version_str = _extract_version_from_jar_name(path.name)
        if version_str is None:
            return (0, Version("0"), path.name)
        try:
            return (1, Version(_normalize_version_for_sorting(version_str)), path.name)
        except InvalidVersion:
            return (0, Version("0"), path.name)

    selected = max(jars, key=_sort_key)
    logger.warning("Multiple hotvect JARs found (%s). Selecting: %s", jars, selected)
    return selected


HOTVECT_JAR_PATH = find_hotvect_jar("hotvect-offline-util-*-jar-with-dependencies.jar").absolute()
HOTVECT_ALGORITHM_SERVE_JAR_PATH = find_hotvect_jar("hotvect-algorithm-serve-*-jar-with-dependencies.jar").absolute()
HOTVECT_ALGORITHM_DEMO_JAR_PATH = find_hotvect_jar("hotvect-algorithm-demo-*-jar-with-dependencies.jar").absolute()
