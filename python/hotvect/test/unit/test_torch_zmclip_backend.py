import json

import numpy as np

from hotvect.worker.torch_zmclip_backend import TorchZmClipWorkerBackend


def test_torch_zmclip_backend_serves_v2_embeddings(tmp_path, monkeypatch):
    schema_path = tmp_path / "encoded-schema-description"
    schema_path.write_text(
        json.dumps(
            {
                "features": [
                    {
                        "name": "text",
                        "dtype": "string",
                        "shape": [],
                    }
                ]
            }
        ),
        encoding="utf-8",
    )
    model_dir = tmp_path / "model_parameter"
    model_dir.mkdir()
    (model_dir / "zmclip_checkpoint.pt").write_bytes(b"checkpoint")

    monkeypatch.setattr(
        "hotvect.worker.torch_zmclip_backend._load_zmclip",
        lambda *args, **kwargs: ("model", "tokenizer", "cpu"),
    )

    def _fake_encode_text_batch(model, tokenizer, torch_device, texts):
        rows = []
        for index, _text in enumerate(texts):
            base = float(index + 1)
            rows.append([base, base + 0.5, base + 1.0])
        return np.asarray(rows, dtype=np.float32)

    monkeypatch.setattr(
        "hotvect.worker.torch_zmclip_backend._encode_text_batch",
        _fake_encode_text_batch,
    )

    backend = TorchZmClipWorkerBackend(
        str(model_dir),
        str(schema_path),
        model_name="xlm-roberta-base-ViT-B-32",
        use_torch_compile=False,
        torch_num_threads=0,
        torch_num_interop_threads=0,
        warmup_iterations=0,
    )

    metadata = backend.model_metadata("zmclip-text-encoder")
    assert metadata["name"] == "zmclip-text-encoder"
    assert metadata["platform"] == "hotvect.torch"
    assert metadata["inputs"] == [{"name": "text", "datatype": "BYTES", "shape": [-1]}]
    assert metadata["outputs"] == [{"name": "embedding", "datatype": "FP32", "shape": [-1, 3]}]

    response = backend.infer_v2(
        {
            "id": "req-1",
            "inputs": [
                {
                    "name": "text",
                    "datatype": "BYTES",
                    "shape": [2],
                    "data": ["red shoes", "black boots"],
                }
            ],
        },
        model_name="zmclip-text-encoder",
    )

    assert response == {
        "model_name": "zmclip-text-encoder",
        "id": "req-1",
        "outputs": [
            {
                "name": "embedding",
                "datatype": "FP32",
                "shape": [2, 3],
                "data": [[1.0, 1.5, 2.0], [2.0, 2.5, 3.0]],
            }
        ],
    }
