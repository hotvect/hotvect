import pytest

from hotvect.sagemaker_contracts import resolve_one_shot_image_protocol


@pytest.mark.parametrize(
    ("training_image", "expected_protocol"),
    [
        ("registry.example/hotvect:10.41.1", "legacy-direct"),
        ("registry.example/hotvect:10.48.9", "legacy-direct"),
        ("registry.example/hotvect:10.44.3-cpu-torch", "legacy-direct"),
        ("registry.example/hotvect:10.49.0", "offline-source-manifest"),
        ("registry.example/hotvect:10.50", "offline-source-manifest"),
    ],
)
def test_resolve_one_shot_image_protocol_for_direct_source(training_image: str, expected_protocol: str) -> None:
    assert resolve_one_shot_image_protocol(training_image, source_kind="direct") == expected_protocol


@pytest.mark.parametrize("source_kind", ["direct", "fixed-composition", "EMS"])
def test_current_one_shot_image_supports_every_source_kind(source_kind: str) -> None:
    assert (
        resolve_one_shot_image_protocol("registry.example/hotvect:10.49.0", source_kind=source_kind)
        == "offline-source-manifest"
    )


@pytest.mark.parametrize("training_image", ["registry.example/hotvect:10.40.9", "registry.example/hotvect:9.29"])
def test_resolve_one_shot_image_protocol_rejects_images_below_minimum(training_image: str) -> None:
    with pytest.raises(ValueError, match=r"requires a Hotvect training image >= 10\.41\.1"):
        resolve_one_shot_image_protocol(training_image, source_kind="direct")


def test_resolve_one_shot_image_protocol_rejects_unversioned_image() -> None:
    with pytest.raises(ValueError, match="requires a versioned Hotvect training image tag"):
        resolve_one_shot_image_protocol("registry.example/hotvect:latest", source_kind="direct")


@pytest.mark.parametrize("source_kind", ["fixed-composition", "EMS"])
def test_legacy_image_rejects_composed_source(source_kind: str) -> None:
    with pytest.raises(ValueError, match=rf"{source_kind} execution requires a Hotvect training image >= 10\.49\.0"):
        resolve_one_shot_image_protocol("registry.example/hotvect:10.48.9", source_kind=source_kind)
