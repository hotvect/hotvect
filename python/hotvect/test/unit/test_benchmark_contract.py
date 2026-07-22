from hotvect.benchmark_contract import build_benchmark_contract


def test_build_benchmark_contract_removes_absent_fields() -> None:
    contract = build_benchmark_contract(
        parameter_s3_uri=None,
        source_paths=[],
        samples=100,
        output_prefixes={
            "metadata": "s3://bucket/meta",
            "task_output": None,
            "empty_nested": {"unused": None},
        },
    )

    assert contract == {
        "samples": 100,
        "output_prefixes": {
            "metadata": "s3://bucket/meta",
        },
    }


def test_build_benchmark_contract_merges_base_contract_and_overrides_outputs() -> None:
    source_paths = ["/data/a.jsonl", "/data/b.jsonl"]
    execution_command = ["java", "-Xmx16g", "com.hotvect.Main", "performance-test"]
    base_contract = {
        "input_channels": {"test-data": "s3://bucket/test-data/"},
        "output_prefixes": {
            "metadata": "s3://bucket/old-meta",
            "predict_parameters": "s3://bucket/params.zip",
        },
    }

    contract = build_benchmark_contract(
        base_contract=base_contract,
        parameter_s3_uri="s3://bucket/params.zip",
        source_paths=source_paths,
        instance_type="ml.c7i.4xlarge",
        training_image="training-image",
        samples=1000,
        sample_pool_size=250,
        target_rps=120.0,
        target_throughput_fraction=0.5,
        max_threads=8,
        workload_mode="batch",
        execution_command=execution_command,
        output_prefixes={
            "metadata": "s3://bucket/new-meta",
            "result": "s3://bucket/result.json",
        },
    )

    source_paths.append("/data/late.jsonl")
    execution_command.append("--late-flag")

    assert contract == {
        "input_channels": {"test-data": "s3://bucket/test-data/"},
        "parameter_s3_uri": "s3://bucket/params.zip",
        "source_paths": ["/data/a.jsonl", "/data/b.jsonl"],
        "instance_type": "ml.c7i.4xlarge",
        "training_image": "training-image",
        "samples": 1000,
        "sample_pool_size": 250,
        "target_rps": 120.0,
        "target_throughput_fraction": 0.5,
        "max_threads": 8,
        "workload_mode": "batch",
        "execution_command": ["java", "-Xmx16g", "com.hotvect.Main", "performance-test"],
        "output_prefixes": {
            "metadata": "s3://bucket/new-meta",
            "predict_parameters": "s3://bucket/params.zip",
            "result": "s3://bucket/result.json",
        },
    }
