from fastapi import FastAPI
from fastapi.testclient import TestClient

from hotvect.worker.litserve_server import _attach_v2_routes


class _DummyBackend:
    def model_metadata(self, model_name: str):
        return {
            "name": model_name,
            "versions": ["1"],
            "platform": "hotvect.test",
            "inputs": [{"name": "text", "datatype": "BYTES", "shape": [-1]}],
            "outputs": [{"name": "embedding", "datatype": "FP32", "shape": [-1, 2]}],
        }

    def infer_v2(self, request, *, model_name: str, debug_include_tf_inputs: bool = False):
        return {
            "model_name": model_name,
            "id": request.get("id"),
            "outputs": [
                {
                    "name": "embedding",
                    "datatype": "FP32",
                    "shape": [1, 2],
                    "data": [[1.0, 2.0]],
                }
            ],
        }


_MISSING = object()


def _build_test_client(runtime=_MISSING):
    app = FastAPI()
    _attach_v2_routes(
        app,
        runtime_getter=lambda: _DummyBackend() if runtime is _MISSING else runtime,
        model_name="demo-model",
        debug_include_tf_inputs=False,
    )
    return TestClient(app)


def test_v2_http_exposes_metadata_and_infer():
    with _build_test_client() as client:
        assert client.get("/v2").json() == {"name": "hotvect-worker-server", "version": "2.0", "extensions": []}
        assert client.get("/v2/health/live").json() == {"live": True}
        assert client.get("/v2/health/ready").json() == {"ready": True}

        metadata = client.get("/v2/models/demo-model")
        assert metadata.status_code == 200
        assert metadata.json()["name"] == "demo-model"

        response = client.post(
            "/v2/models/demo-model/infer",
            json={
                "id": "req-1",
                "inputs": [
                    {
                        "name": "text",
                        "datatype": "BYTES",
                        "shape": [1],
                        "data": ["hello"],
                    }
                ],
            },
        )
        assert response.status_code == 200
        assert response.json() == {
            "model_name": "demo-model",
            "id": "req-1",
            "outputs": [
                {
                    "name": "embedding",
                    "datatype": "FP32",
                    "shape": [1, 2],
                    "data": [[1.0, 2.0]],
                }
            ],
        }


def test_v2_http_rejects_unknown_model():
    with _build_test_client() as client:
        response = client.get("/v2/models/other-model")
        assert response.status_code == 404
        assert "Unknown model" in response.json()["error"]["message"]


def test_v2_http_ready_requires_runtime():
    with _build_test_client(runtime=None) as client:
        response = client.get("/v2/health/ready")
        assert response.status_code == 503
        assert response.json()["detail"] == "Worker backend is not ready"
