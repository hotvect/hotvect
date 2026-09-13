"""Tests for download-data-dependency command."""

import argparse
import io
import json
import sys
import unittest
from contextlib import redirect_stdout
from datetime import date
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest.mock import patch

from hotvect.extra.commands.download_data_dependency import (
    DEFAULT_DOWNLOAD_MAX_RETRIES,
    DataDependencyCommand,
    _deterministic_sample,
)
from hotvect.pyhotvect import DataDependency


class TestDeterministicSample(unittest.TestCase):
    """Test cases for deterministic sampling function."""

    def test_sample_ratio_one_returns_all_files(self):
        """Test that sample_ratio=1.0 returns all files."""
        files = ["file1.json", "file2.json", "file3.json"]
        result = _deterministic_sample(files, 1.0)
        self.assertEqual(set(result), set(files))

    def test_sample_ratio_greater_than_one_returns_all_files(self):
        """Test that sample_ratio>1.0 returns all files."""
        files = ["file1.json", "file2.json", "file3.json"]
        result = _deterministic_sample(files, 1.5)
        self.assertEqual(set(result), set(files))

    def test_deterministic_same_input_same_output(self):
        """Test that same input produces same output."""
        files = [f"file{i}.json" for i in range(100)]
        sample_ratio = 0.3

        result1 = _deterministic_sample(files, sample_ratio)
        result2 = _deterministic_sample(files, sample_ratio)

        self.assertEqual(result1, result2)

    def test_deterministic_different_order_same_output(self):
        """Test that different input order produces same output."""
        files = [f"file{i}.json" for i in range(100)]
        shuffled = files[::-1]  # Reverse order
        sample_ratio = 0.3

        result1 = _deterministic_sample(files, sample_ratio)
        result2 = _deterministic_sample(shuffled, sample_ratio)

        self.assertEqual(set(result1), set(result2))

    def test_sample_ratio_returns_correct_count(self):
        """Test that sampling returns approximately correct number of files."""
        files = [f"file{i}.json" for i in range(100)]
        sample_ratio = 0.3

        result = _deterministic_sample(files, sample_ratio)

        # Should return exactly 30 files (30% of 100)
        self.assertEqual(len(result), 30)

    def test_sample_ratio_respects_minimum_one_file(self):
        """Test that sampling always returns at least 1 file."""
        files = ["file1.json", "file2.json"]
        sample_ratio = 0.01  # Would be 0.02 files, rounds to 1

        result = _deterministic_sample(files, sample_ratio)

        self.assertGreaterEqual(len(result), 1)

    def test_different_sample_ratios_same_seed_behavior(self):
        """Test that different sample ratios are consistent."""
        files = [f"file{i}.json" for i in range(100)]

        result_10 = set(_deterministic_sample(files, 0.1))
        result_20 = set(_deterministic_sample(files, 0.2))
        result_50 = set(_deterministic_sample(files, 0.5))

        # Smaller samples should be subsets of larger samples
        # (not guaranteed but highly likely with sorted + fixed seed)
        self.assertEqual(len(result_10), 10)
        self.assertEqual(len(result_20), 20)
        self.assertEqual(len(result_50), 50)


class TestResumeLogic(unittest.TestCase):
    """Test cases for resume functionality."""

    def setUp(self):
        self.command = DataDependencyCommand()
        self.s3_date_prefix = "tables/data/dt=2025-01-01"

    def test_complete_directory_skipped(self):
        """Test that complete directory with all files is skipped."""
        with TemporaryDirectory() as tmpdir:
            local_dir = Path(tmpdir) / "data" / "dt=2025-01-01"
            (local_dir / "a").mkdir(parents=True)
            (local_dir / "b").mkdir(parents=True)

            # Create files locally
            (local_dir / "a" / "part-00000.parquet").touch()
            (local_dir / "b" / "part-00000.parquet").touch()

            # Simulate S3 files
            s3_files = [
                f"{self.s3_date_prefix}/a/part-00000.parquet",
                f"{self.s3_date_prefix}/b/part-00000.parquet",
            ]

            # Check completion
            local_files = self.command._local_file_relative_paths(local_dir)
            target_rel_paths = {self.command._relative_key_under_prefix(f, self.s3_date_prefix) for f in s3_files}

            self.assertEqual(local_files, target_rel_paths)

    def test_incomplete_directory_resume(self):
        """Test that incomplete directory resumes with missing files."""
        with TemporaryDirectory() as tmpdir:
            local_dir = Path(tmpdir) / "data" / "dt=2025-01-01"
            local_dir.mkdir(parents=True)

            # Create only some files locally
            (local_dir / "a").mkdir(parents=True)
            (local_dir / "a" / "part-00000.parquet").touch()

            # Simulate S3 has more files
            s3_files = [
                f"{self.s3_date_prefix}/a/part-00000.parquet",
                f"{self.s3_date_prefix}/b/part-00000.parquet",
            ]

            # Check what needs to be downloaded
            local_files = self.command._local_file_relative_paths(local_dir)
            target_rel_paths = {self.command._relative_key_under_prefix(f, self.s3_date_prefix) for f in s3_files}

            missing = target_rel_paths - local_files
            self.assertEqual(missing, {"b/part-00000.parquet"})
            self.assertTrue(local_files < target_rel_paths)  # Subset check

    def test_extra_files_detected(self):
        """Test that extra local files are detected."""
        with TemporaryDirectory() as tmpdir:
            local_dir = Path(tmpdir) / "data" / "dt=2025-01-01"
            local_dir.mkdir(parents=True)

            # Create files including extras
            (local_dir / "a").mkdir(parents=True)
            (local_dir / "b").mkdir(parents=True)
            (local_dir / "x").mkdir(parents=True)
            (local_dir / "a" / "part-00000.parquet").touch()
            (local_dir / "b" / "part-00000.parquet").touch()
            (local_dir / "x" / "extra.parquet").touch()

            # Simulate S3 files (without extra)
            s3_files = [
                f"{self.s3_date_prefix}/a/part-00000.parquet",
                f"{self.s3_date_prefix}/b/part-00000.parquet",
            ]

            # Check for extras
            local_files = self.command._local_file_relative_paths(local_dir)
            target_rel_paths = {self.command._relative_key_under_prefix(f, self.s3_date_prefix) for f in s3_files}

            extra = local_files - target_rel_paths
            self.assertEqual(extra, {"x/extra.parquet"})
            self.assertFalse(local_files < target_rel_paths)  # Not a subset

    def test_resume_with_sampling_deterministic(self):
        """Test that resume works correctly with deterministic sampling."""
        files = [f"{self.s3_date_prefix}/grp/file{i}.json" for i in range(100)]
        sample_ratio = 0.3

        # Get target files (what should be downloaded)
        target_files = _deterministic_sample(files, sample_ratio)
        target_rel_paths = {self.command._relative_key_under_prefix(f, self.s3_date_prefix) for f in target_files}

        with TemporaryDirectory() as tmpdir:
            local_dir = Path(tmpdir) / "data" / "dt=2025-01-01"
            local_dir.mkdir(parents=True)

            # Simulate partial download: first 10 files of the sample
            for f in list(target_files)[:10]:
                rel = self.command._relative_key_under_prefix(f, self.s3_date_prefix)
                dest = local_dir / rel
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.touch()

            # Check what still needs to be downloaded
            local_files = self.command._local_file_relative_paths(local_dir)
            missing = target_rel_paths - local_files

            # Should be exactly 20 missing files (30 - 10)
            self.assertEqual(len(missing), 20)
            self.assertEqual(len(local_files), 10)
            self.assertTrue(local_files < target_rel_paths)


class TestDataDependencyCommandTarget(unittest.TestCase):
    def setUp(self):
        self.command = DataDependencyCommand()

    def _parse_args(self, *extra_args):
        parser = argparse.ArgumentParser()
        subparsers = parser.add_subparsers(dest="command")
        DataDependencyCommand.register_parser(subparsers)
        return parser.parse_args(
            [
                "data-dependency",
                "--repo-url",
                "https://github.com/company/example-algorithm.git",
                "--git-reference",
                "v77.0.0",
                "--s3-base-dir",
                "s3://bucket/tables",
                "--local-data-dir",
                "./data",
                "--scratch-dir",
                "./scratch",
                "--last-test-time",
                "2026-01-03",
                *extra_args,
            ]
        )

    def test_register_parser_defaults_target_to_evaluate(self):
        args = self._parse_args()

        self.assertEqual(args.target, "evaluate")

    def test_register_parser_accepts_predict_target(self):
        args = self._parse_args("--target", "predict")

        self.assertEqual(args.target, "predict")

    @patch("hotvect.extra.commands.download_data_dependency.AlgorithmPipeline")
    @patch("hotvect.extra.commands.download_data_dependency.clone_and_build_algorithm_jar")
    def test_get_data_dependencies_threads_target_to_pipeline(self, mock_clone, mock_pipeline_cls):
        mock_clone.return_value = SimpleNamespace(
            algorithm_name="algo",
            algorithm_version="1.2.3",
            algorithm_jar_path=Path("/tmp/algo.jar"),
        )
        dependencies = [object()]
        mock_pipeline = mock_pipeline_cls.return_value
        mock_pipeline.data_dependencies.return_value = dependencies

        with TemporaryDirectory() as scratch_dir:
            result = self.command._get_data_dependencies(
                repo_url="https://github.com/example-org/example-algorithm.git",
                git_reference="v77.0.0",
                scratch_dir=scratch_dir,
                last_test_time=date(2026, 1, 3),
                target="predict",
                algorithm_override=None,
            )

        self.assertEqual(result, ("algo", "1.2.3", dependencies))
        mock_pipeline.data_dependencies.assert_called_once_with(target="predict")
        self.assertIs(mock_clone.call_args.kwargs["progress_stream"], sys.stderr)

    def test_list_does_not_create_local_data_dir(self):
        with TemporaryDirectory() as tmpdir:
            local_data_dir = Path(tmpdir) / "local-data"
            args = SimpleNamespace(
                last_test_time="2000-01-03",
                sample_ratio=None,
                local_data_dir=str(local_data_dir),
                scratch_dir=str(Path(tmpdir) / "scratch"),
                repo_url="https://github.com/example-org/example-algorithm.git",
                git_reference="v77.0.0",
                target="evaluate",
                algorithm_override=None,
                role_arn=None,
                s3_base_dir="s3://bucket/tables",
                max_parallel_downloads=8,
                download_all=False,
                download_dependencies=None,
            )

            with (
                patch.object(self.command, "_get_data_dependencies", return_value=("algo", "1.2.3", [])),
                patch.object(self.command, "_output_dependency_info_json") as output,
                patch("hotvect.extra.commands.download_data_dependency.boto3.Session"),
            ):
                self.command.execute(args)

            self.assertFalse(local_data_dir.exists())
            output.assert_called_once()


class TestCanonicalDataDependencyCommand(unittest.TestCase):
    def setUp(self):
        self.command = DataDependencyCommand()
        self.dependency = DataDependency(
            algorithm_name="algo",
            algorithm_version="1.2.3",
            data_prefix="example_data",
            data_dates={date(2000, 1, 2)},
            data_type="test",
            additional_properties={"s3_uri": {"production": "s3://example-bucket/example_data/"}},
        )

    def _inspect_args(self, **overrides):
        values = {
            "dependency_command": "inspect",
            "last_test_time": "2000-01-03",
            "repo_url": "https://github.com/example-org/example-algorithm.git",
            "git_reference": "v77.0.0",
            "scratch_dir": "./scratch",
            "target": "evaluate",
            "algorithm_override": None,
            "remote": False,
            "local_dir": None,
            "output_format": "json",
            "s3_base_dir": None,
            "role_arn": None,
        }
        values.update(overrides)
        return SimpleNamespace(**values)

    def _download_args(self, local_dir: Path, **overrides):
        values = {
            "download_all": False,
            "download_dependencies": ["example_data"],
            "local_dir": str(local_dir),
            "s3_base_dir": None,
            "role_arn": None,
            "sample_ratio": None,
            "max_parallel_downloads": 1,
            "scratch_dir": "./scratch",
        }
        values.update(overrides)
        return SimpleNamespace(**values)

    def test_inspect_without_remote_emits_declared_dependency_only(self):
        args = self._inspect_args()
        output = io.StringIO()

        with (
            patch.object(self.command, "_get_data_dependencies", return_value=("algo", "1.2.3", [self.dependency])),
            redirect_stdout(output),
        ):
            self.command.execute_canonical(args)

        payload = json.loads(output.getvalue())
        dependency = payload["dependencies"][0]
        self.assertEqual(dependency["data_prefix"], "example_data")
        self.assertNotIn("s3_uri", dependency)

    def test_inspect_sagemaker_emits_input_data_config(self):
        args = self._inspect_args(remote=True, output_format="sagemaker")
        output = io.StringIO()

        with (
            patch.object(self.command, "_get_data_dependencies", return_value=("algo", "1.2.3", [self.dependency])),
            redirect_stdout(output),
        ):
            self.command.execute_canonical(args)

        payload = json.loads(output.getvalue())
        channel = payload["InputDataConfig"][0]
        self.assertEqual(channel["ChannelName"], "example_data")
        self.assertEqual(channel["DataSource"]["S3DataSource"]["S3Uri"], "s3://example-bucket/example_data/")
        self.assertEqual(channel["InputMode"], "FastFile")

    def test_inspect_sagemaker_uses_s3_base_dir_fallback(self):
        dependency_without_uri = DataDependency(
            algorithm_name="algo",
            algorithm_version="1.2.3",
            data_prefix="example_data",
            data_dates={date(2000, 1, 2)},
            data_type="test",
            additional_properties={},
        )
        args = self._inspect_args(
            remote=True,
            output_format="sagemaker",
            s3_base_dir="s3://fallback-bucket/tables",
        )
        output = io.StringIO()

        with (
            patch.object(
                self.command,
                "_get_data_dependencies",
                return_value=("algo", "1.2.3", [dependency_without_uri]),
            ),
            redirect_stdout(output),
        ):
            self.command.execute_canonical(args)

        channel = json.loads(output.getvalue())["InputDataConfig"][0]
        self.assertEqual(
            channel["DataSource"]["S3DataSource"]["S3Uri"],
            "s3://fallback-bucket/tables/example_data/",
        )

    def test_inspect_local_requires_remote(self):
        args = self._inspect_args(local_dir="./data")

        with patch.object(self.command, "_get_data_dependencies") as get:
            with self.assertRaisesRegex(ValueError, "--local-dir requires --remote"):
                self.command.execute_canonical(args)
        get.assert_not_called()

    def test_download_rejects_invalid_sample_ratio_before_dependency_discovery(self):
        with TemporaryDirectory() as tmpdir:
            args = self._download_args(Path(tmpdir) / "local", sample_ratio=0)
            args.dependency_command = "download"
            args.last_test_time = "2000-01-03"
            args.repo_url = "https://github.com/example-org/example-algorithm.git"
            args.git_reference = "v77.0.0"
            args.target = "evaluate"
            args.algorithm_override = None

            with patch.object(self.command, "_get_data_dependencies") as get:
                with self.assertRaisesRegex(ValueError, "--sample-ratio must be between 0 and 1"):
                    self.command.execute_canonical(args)
            get.assert_not_called()

    def test_remote_resolution_requires_declared_uri_or_fallback(self):
        dependency_without_uri = DataDependency(
            algorithm_name="algo",
            algorithm_version="1.2.3",
            data_prefix="example_data",
            data_dates={date(2000, 1, 2)},
            data_type="test",
            additional_properties={},
        )
        with self.assertRaisesRegex(ValueError, "no production s3_uri"):
            self.command._production_s3_location(dependency_without_uri)

    def test_remote_resolution_falls_back_to_s3_base_dir(self):
        dependency_without_uri = DataDependency(
            algorithm_name="algo",
            algorithm_version="1.2.3",
            data_prefix="example_data",
            data_dates={date(2000, 1, 2)},
            data_type="test",
            additional_properties={},
        )

        location = self.command._production_s3_location(
            dependency_without_uri,
            "s3://fallback-bucket/tables",
        )

        self.assertEqual(
            location,
            (
                "s3://fallback-bucket/tables/example_data/",
                "fallback-bucket",
                "tables/example_data",
            ),
        )

    def test_declared_production_uri_takes_precedence_over_s3_base_dir(self):
        location = self.command._production_s3_location(
            self.dependency,
            "s3://fallback-bucket/tables",
        )

        self.assertEqual(
            location,
            (
                "s3://example-bucket/example_data/",
                "example-bucket",
                "example_data",
            ),
        )

    def test_download_rejects_any_unknown_selected_dependency_before_creating_local_dir(self):
        with TemporaryDirectory() as tmpdir:
            local_dir = Path(tmpdir) / "local"
            args = self._download_args(local_dir, download_dependencies=["example_data", "unknown_data"])

            with self.assertRaisesRegex(ValueError, "Unknown dependency names: unknown_data"):
                self.command._execute_canonical_download(args, [self.dependency])

            self.assertFalse(local_dir.exists())

    def test_download_validates_remote_contract_before_creating_local_dir(self):
        dependency_without_uri = DataDependency(
            algorithm_name="algo",
            algorithm_version="1.2.3",
            data_prefix="example_data",
            data_dates={date(2000, 1, 2)},
            data_type="test",
            additional_properties={},
        )
        with TemporaryDirectory() as tmpdir:
            local_dir = Path(tmpdir) / "local"
            args = self._download_args(local_dir, download_all=True, download_dependencies=None)

            with self.assertRaisesRegex(ValueError, "no production s3_uri"):
                self.command._execute_canonical_download(args, [dependency_without_uri])

            self.assertFalse(local_dir.exists())

    def test_rejects_non_s3_production_uri(self):
        dependency_with_https_uri = DataDependency(
            algorithm_name="algo",
            algorithm_version="1.2.3",
            data_prefix="example_data",
            data_dates={date(2026, 1, 2)},
            data_type="test",
            additional_properties={"s3_uri": {"production": "https://example.invalid/example_data/"}},
        )

        with self.assertRaisesRegex(ValueError, "invalid S3 URI"):
            self.command._production_s3_location(dependency_with_https_uri)

    def test_rejects_non_s3_fallback_uri(self):
        dependency_without_uri = DataDependency(
            algorithm_name="algo",
            algorithm_version="1.2.3",
            data_prefix="example_data",
            data_dates={date(2026, 1, 2)},
            data_type="test",
            additional_properties={},
        )

        with self.assertRaisesRegex(ValueError, "invalid S3 URI"):
            self.command._production_s3_location(
                dependency_without_uri,
                "https://example.invalid/tables",
            )


class _FlakyDownloadS3Client:
    def __init__(self, failures_before_success: int = 0):
        self.failures_before_success = failures_before_success
        self.calls = 0

    def download_fileobj(self, _bucket, key, fileobj):
        self.calls += 1
        if self.calls <= self.failures_before_success:
            raise RuntimeError("transient failure")
        fileobj.write(f"payload:{key}".encode())


class TestDownloadExecution(unittest.TestCase):
    def setUp(self):
        self.command = DataDependencyCommand()
        self.s3_date_prefix = "tables/data/dt=2025-01-01"

    def test_execute_downloads_preserves_relative_paths(self):
        with TemporaryDirectory() as tmpdir:
            local_data_dir = Path(tmpdir) / "local"
            scratch_dir = Path(tmpdir) / "scratch"
            keys = [
                f"{self.s3_date_prefix}/a/part-00000.parquet",
                f"{self.s3_date_prefix}/b/part-00000.parquet",
            ]
            plan = [("bucket", "data", "2025-01-01", self.s3_date_prefix, keys, False)]
            client = _FlakyDownloadS3Client()

            self.command._execute_downloads(
                plan, client, str(local_data_dir), str(scratch_dir), max_parallel_downloads=1
            )

            a_path = local_data_dir / "data" / "dt=2025-01-01" / "a" / "part-00000.parquet"
            b_path = local_data_dir / "data" / "dt=2025-01-01" / "b" / "part-00000.parquet"
            self.assertTrue(a_path.exists())
            self.assertTrue(b_path.exists())
            self.assertNotEqual(a_path.read_text(), b_path.read_text())

    def test_execute_downloads_retries_transient_failures(self):
        with TemporaryDirectory() as tmpdir:
            local_data_dir = Path(tmpdir) / "local"
            scratch_dir = Path(tmpdir) / "scratch"
            key = f"{self.s3_date_prefix}/a/part-00000.parquet"
            plan = [("bucket", "data", "2025-01-01", self.s3_date_prefix, [key], False)]
            client = _FlakyDownloadS3Client(failures_before_success=2)

            with patch("hotvect.extra.commands.download_data_dependency.time.sleep", return_value=None):
                self.command._execute_downloads(
                    plan,
                    client,
                    str(local_data_dir),
                    str(scratch_dir),
                    max_parallel_downloads=1,
                )

            self.assertEqual(client.calls, 3)

    def test_execute_downloads_exits_after_retry_budget(self):
        with TemporaryDirectory() as tmpdir:
            local_data_dir = Path(tmpdir) / "local"
            scratch_dir = Path(tmpdir) / "scratch"
            key = f"{self.s3_date_prefix}/a/part-00000.parquet"
            plan = [("bucket", "data", "2025-01-01", self.s3_date_prefix, [key], False)]
            client = _FlakyDownloadS3Client(failures_before_success=100)

            with patch("hotvect.extra.commands.download_data_dependency.time.sleep", return_value=None):
                with self.assertRaises(SystemExit) as cm:
                    self.command._execute_downloads(
                        plan,
                        client,
                        str(local_data_dir),
                        str(scratch_dir),
                        max_parallel_downloads=1,
                    )

            self.assertEqual(cm.exception.code, 1)
            self.assertEqual(client.calls, DEFAULT_DOWNLOAD_MAX_RETRIES)


if __name__ == "__main__":
    unittest.main()
