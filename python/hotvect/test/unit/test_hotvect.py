import os.path

from hotvect.hotvectjar import HOTVECT_JAR_PATH


def test_hotvect_jar_location():
    assert os.path.isfile(HOTVECT_JAR_PATH)
