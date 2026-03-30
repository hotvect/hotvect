import logging
from pathlib import Path

from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext


def _cleanup_hotvect_log_handlers() -> None:
    root_logger = logging.getLogger()
    for handler in list(root_logger.handlers):
        if getattr(handler, "_hotvect_log_kind", None) in {"pipeline", "combined"}:
            root_logger.removeHandler(handler)
            handler.close()


def _make_pipeline(tmp_path: Path, *, algorithm_name: str) -> AlgorithmPipeline:
    pipeline = AlgorithmPipeline.__new__(AlgorithmPipeline)
    pipeline.algorithm_pipeline_context = AlgorithmPipelineContext(
        algorithm_jar_path=tmp_path / "algo.jar",
        data_base_path=tmp_path / "data",
        metadata_base_path=tmp_path / "meta",
        output_base_path=tmp_path / "out",
        jvm_options=None,
    )
    pipeline.algorithm_name = algorithm_name
    pipeline.algorithm_version = "1"
    pipeline.hyper_parameter_version = ""
    pipeline.parameter_version = "pv"
    return pipeline


def test_python_logs_do_not_mix_across_dependency_pipelines(tmp_path: Path):
    _cleanup_hotvect_log_handlers()
    root_logger = logging.getLogger()
    previous_level = root_logger.level
    root_logger.setLevel(logging.INFO)
    try:
        parent = _make_pipeline(tmp_path, algorithm_name="parent")
        child = _make_pipeline(tmp_path, algorithm_name="child")

        test_logger = logging.getLogger("hotvect.test.pyhotvect.logging")

        with parent._pipe_python_logs_to_metadata_dir():
            test_logger.info("parent:before")
            with child._pipe_python_logs_to_metadata_dir():
                test_logger.info("child:only")
            test_logger.info("parent:after")

        parent_meta_dir = Path(parent.metadata_path())
        child_meta_dir = Path(child.metadata_path())

        parent_hv_log = (parent_meta_dir / "hv.log").read_text(encoding="utf-8")
        child_hv_log = (child_meta_dir / "hv.log").read_text(encoding="utf-8")
        combined_log = (parent_meta_dir / "hv.all.log").read_text(encoding="utf-8")

        assert "parent:before" in parent_hv_log
        assert "parent:after" in parent_hv_log
        assert "child:only" not in parent_hv_log

        assert "child:only" in child_hv_log
        assert "parent:before" not in child_hv_log
        assert "parent:after" not in child_hv_log

        assert "parent:before" in combined_log
        assert "child:only" in combined_log
        assert "parent:after" in combined_log

        assert not (child_meta_dir / "hv.all.log").exists()
    finally:
        root_logger.setLevel(previous_level)
        _cleanup_hotvect_log_handlers()
