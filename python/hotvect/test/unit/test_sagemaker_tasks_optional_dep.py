def test_sagemaker_tasks_imports_without_sagemaker_training() -> None:
    import hotvect.sagemaker_tasks  # noqa: F401


def test_sagemaker_tasks_entrypoint_requires_optional_dependency() -> None:
    import hotvect.sagemaker_tasks

    try:
        hotvect.sagemaker_tasks.run_one_shot_from_sagemaker_env()
    except ImportError as e:
        assert "sagemaker-training" in str(e)
    except ValueError as e:
        assert "Missing" in str(e)
