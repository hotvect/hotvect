from pathlib import Path


def find_hotvect_jar(pattern):
    jars = list(Path(__file__).parent.glob(pattern))
    assert len(jars) == 1, f"Expecting one hotvect JAR file, but found the following instead: {jars}"
    return next(iter(jars))


HOTVECT_JAR_PATH = find_hotvect_jar("hotvect-offline-util-*-jar-with-dependencies.jar").absolute()
