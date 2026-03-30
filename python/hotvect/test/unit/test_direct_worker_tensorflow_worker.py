import sys
import types

import pytest

from hotvect.worker import tensorflow_backend


class _FakeConfig:
    def __init__(self, gpus):
        self._gpus = list(gpus)
        self.hidden = False

    def list_physical_devices(self, kind):
        assert kind == "GPU"
        if self.hidden:
            return []
        return list(self._gpus)

    def set_visible_devices(self, devices, kind):
        assert kind == "GPU"
        assert devices == []
        self.hidden = True


def _fake_tf(gpus):
    module = types.ModuleType("tensorflow")
    module.config = _FakeConfig(gpus)
    return module


def test_get_tf_and_device_cpu_mode_hides_gpus(monkeypatch):
    fake_tf = _fake_tf(["gpu0"])
    monkeypatch.delenv("CUDA_VISIBLE_DEVICES", raising=False)
    monkeypatch.setitem(sys.modules, "tensorflow", fake_tf)

    returned_tf, device = tensorflow_backend.get_tf_and_device()

    assert returned_tf is fake_tf
    assert device == "/CPU:0"
    assert fake_tf.config.hidden is True


def test_get_tf_and_device_cuda_mode_uses_gpu(monkeypatch):
    fake_tf = _fake_tf(["gpu0"])
    monkeypatch.setenv("CUDA_VISIBLE_DEVICES", "0")
    monkeypatch.setitem(sys.modules, "tensorflow", fake_tf)

    returned_tf, device = tensorflow_backend.get_tf_and_device()

    assert returned_tf is fake_tf
    assert device == "/GPU:0"


def test_get_tf_and_device_cuda_mode_fails_without_visible_gpu(monkeypatch):
    monkeypatch.setenv("CUDA_VISIBLE_DEVICES", "0")
    monkeypatch.setitem(sys.modules, "tensorflow", _fake_tf([]))

    with pytest.raises(RuntimeError, match="TensorFlow cannot see any GPU devices"):
        tensorflow_backend.get_tf_and_device()
