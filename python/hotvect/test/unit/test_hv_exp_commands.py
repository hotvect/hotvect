from __future__ import annotations

import io
import json
from types import SimpleNamespace

from hotvect.experiment_management._generated_operations import _GeneratedEmsOperations
from hotvect.experiment_management.commands import (
    ExperimentCommand,
    _create_client_from_args,
    _create_online_results_store_from_args,
    _resolve_online_results_root_from_args,
)


def _capture_print(monkeypatch):
    printed = {}
    monkeypatch.setattr("builtins.print", lambda s: printed.setdefault("out", s))
    return printed


def test_create_client_from_args_uses_cli_timeout_overrides(monkeypatch):
    args = SimpleNamespace(
        url="http://localhost:9999",
        token_provider_command="echo tok",
        token_provider_ttl_ms=1000,
        connect_timeout_seconds=4.5,
        read_timeout_seconds=19.0,
        config_path="",
    )
    recorded = {}

    class FakeClient:
        def __init__(self, **kwargs):
            recorded.update(kwargs)

    monkeypatch.setattr(
        "hotvect.experiment_management.commands.CommandTokenProvider", lambda command, ttl_seconds: command
    )
    monkeypatch.setattr("hotvect.experiment_management.commands.TokenProviderAuth", lambda provider: provider)
    monkeypatch.setattr("hotvect.experiment_management.commands.ExperimentManagementClient", FakeClient)

    _create_client_from_args(args)

    assert recorded["base_url"] == "http://localhost:9999"
    assert recorded["connect_timeout"] == 4.5
    assert recorded["read_timeout"] == 19.0


def test_create_client_from_args_uses_config_timeouts_when_cli_omits_them(monkeypatch, tmp_path):
    config_path = tmp_path / "hv-config.json"
    config_path.write_text(
        json.dumps(
            {
                "experiment_management": {
                    "url": "http://localhost:9999",
                    "token_provider_command": "echo tok",
                    "token_provider_ttl_ms": 1000,
                    "connect_timeout_seconds": 6.0,
                    "read_timeout_seconds": 17.5,
                }
            }
        ),
        encoding="utf-8",
    )
    args = SimpleNamespace(
        url=None,
        token_provider_command=None,
        token_provider_ttl_ms=1000,
        connect_timeout_seconds=None,
        read_timeout_seconds=None,
        config_path=str(config_path),
    )
    recorded = {}

    class FakeClient:
        def __init__(self, **kwargs):
            recorded.update(kwargs)

    monkeypatch.setattr(
        "hotvect.experiment_management.commands.CommandTokenProvider", lambda command, ttl_seconds: command
    )
    monkeypatch.setattr("hotvect.experiment_management.commands.TokenProviderAuth", lambda provider: provider)
    monkeypatch.setattr("hotvect.experiment_management.commands.ExperimentManagementClient", FakeClient)

    _create_client_from_args(args)

    assert recorded["base_url"] == "http://localhost:9999"
    assert recorded["connect_timeout"] == 6.0
    assert recorded["read_timeout"] == 17.5


def test_snapshot_export_uses_configured_auth_and_passes_token_only_through_child_environment(monkeypatch, tmp_path):
    args = SimpleNamespace(
        subcommand="snapshot",
        snapshot_subcommand="export",
        root_slot="product-ranking",
        output=str(tmp_path / "ems-snapshot.json"),
        domain_model_jar=["product-domain.jar", "shared-types.jar"],
        url="https://ems.example",
        token_provider_command="get-ems-token",
        token_provider_ttl_ms=123_000,
        connect_timeout_seconds=4.5,
        read_timeout_seconds=17.0,
        config_path="",
    )
    captured = {}

    class StubTokenProvider:
        def __init__(self, *, command, ttl_seconds):
            assert command == "get-ems-token"
            assert ttl_seconds == 123.0

        def __call__(self):
            return "secret-ems-token"

    def capture(command, _display, env=None):
        captured.update(command=command, env=env)

    monkeypatch.setattr("hotvect.experiment_management.commands.CommandTokenProvider", StubTokenProvider)
    monkeypatch.setattr("hotvect.experiment_management.commands.stream_output", capture)
    monkeypatch.setattr("hotvect.hotvectjar.HOTVECT_JAR_PATH", tmp_path / "offline.jar")
    printed = _capture_print(monkeypatch)

    ExperimentCommand().execute(args)

    command = captured["command"]
    assert command[0:6] == [
        "java",
        "-cp",
        str(tmp_path / "offline.jar"),
        "com.hotvect.offlineutils.commandline.Main",
        "ems-snapshot-export",
        "--root-slot",
    ]
    assert command[command.index("--ems-uri") + 1] == "https://ems.example"
    assert command[command.index("--ems-connect-timeout-seconds") + 1] == "4.5"
    assert command[command.index("--ems-read-timeout-seconds") + 1] == "17.0"
    assert command.count("--domain-model-jar") == 2
    assert "secret-ems-token" not in command
    assert captured["env"]["HOTVECT_EMS_BEARER_TOKEN"] == "secret-ems-token"
    assert json.loads(printed["out"])["output"] == str(tmp_path / "ems-snapshot.json")


def test_hv_exp_slot_list(monkeypatch):
    args = SimpleNamespace(
        subcommand="slot",
        slot_subcommand="list",
        url="http://localhost:9999",
        token_provider_command="echo tok",
        token_provider_ttl_ms=1000,
        config_path="",
    )

    class FakeSlot:
        def __init__(self, name: str):
            self.name = name

        def model_dump(self, *, mode: str):
            assert mode == "json"
            return {"name": self.name}

    class FakeClient:
        def get_slots(self):
            return [FakeSlot("a"), FakeSlot("b")]

    monkeypatch.setattr("hotvect.experiment_management.commands._create_client_from_args", lambda _args: FakeClient())
    printed = _capture_print(monkeypatch)
    ExperimentCommand().execute(args)
    out = json.loads(printed["out"])
    assert [s["name"] for s in out["slots"]] == ["a", "b"]


def test_hv_exp_slot_get(monkeypatch):
    args = SimpleNamespace(
        subcommand="slot",
        slot_subcommand="get",
        slot_name="slot1",
        url="http://localhost:9999",
        token_provider_command="echo tok",
        token_provider_ttl_ms=1000,
        config_path="",
    )

    class FakeActiveInfo:
        def model_dump(self, *, mode: str):
            assert mode == "json"
            return {"slot_salt": "salt"}

    class FakeClient:
        def get_default_variant_and_active_experiments(self, slot_name: str):
            assert slot_name == "slot1"
            return FakeActiveInfo()

    monkeypatch.setattr("hotvect.experiment_management.commands._create_client_from_args", lambda _args: FakeClient())
    printed = _capture_print(monkeypatch)
    ExperimentCommand().execute(args)
    out = json.loads(printed["out"])
    assert out["active_info"]["slot_salt"] == "salt"


def test_hv_exp_experiment_list_all(monkeypatch):
    args = SimpleNamespace(
        subcommand="experiment",
        experiment_subcommand="list",
        slot_name="",
        url="http://localhost:9999",
        token_provider_command="echo tok",
        token_provider_ttl_ms=1000,
        config_path="",
    )

    class FakeSlot:
        def __init__(self, name: str):
            self.name = name

    class FakeExperiment:
        def __init__(self, experiment_id: int):
            self.experiment_id = experiment_id

        def model_dump(self, *, mode: str):
            assert mode == "json"
            return {"experiment_id": self.experiment_id}

    class FakeClient:
        def get_slots(self):
            return [FakeSlot("a"), FakeSlot("b")]

        def get_experiments(self, slot_name: str):
            return [FakeExperiment(1)] if slot_name == "a" else []

    monkeypatch.setattr("hotvect.experiment_management.commands._create_client_from_args", lambda _args: FakeClient())
    printed = _capture_print(monkeypatch)
    ExperimentCommand().execute(args)
    out = json.loads(printed["out"])
    assert out["experiments"] == [{"slot_name": "a", "experiment": {"experiment_id": 1}}]


def test_hv_exp_experiment_get_resolves_slot(monkeypatch):
    args = SimpleNamespace(
        subcommand="experiment",
        experiment_subcommand="get",
        experiment_id=12,
        url="http://localhost:9999",
        token_provider_command="echo tok",
        token_provider_ttl_ms=1000,
        config_path="",
    )

    class FakeSlot:
        def __init__(self, name: str):
            self.name = name

    class FakeExperiment:
        experiment_id = 12

        def model_dump(self, *, mode: str):
            assert mode == "json"
            return {"experiment_id": 12}

    class FakeClient:
        def get_slots(self):
            return [FakeSlot("a"), FakeSlot("b")]

        def get_experiments(self, slot_name: str):
            return [FakeExperiment()] if slot_name == "b" else []

        def get_experiment(self, slot_name: str, experiment_id: int):
            assert slot_name == "b"
            assert experiment_id == 12
            return FakeExperiment()

    monkeypatch.setattr("hotvect.experiment_management.commands._create_client_from_args", lambda _args: FakeClient())
    printed = _capture_print(monkeypatch)
    ExperimentCommand().execute(args)
    out = json.loads(printed["out"])
    assert out["slot_name"] == "b"
    assert out["experiment"]["experiment_id"] == 12


def test_hv_exp_experiment_rampup_log_resolves_slot_and_filters(monkeypatch):
    args = SimpleNamespace(
        subcommand="experiment",
        experiment_subcommand="rampup-log",
        experiment_id=2,
        url="http://localhost:9999",
        token_provider_command="echo tok",
        token_provider_ttl_ms=1000,
        config_path="",
    )

    class FakeSlot:
        def __init__(self, name: str):
            self.name = name

    class FakeExperiment:
        experiment_id = 2

    class FakeLog:
        def __init__(self, experiment_id: int, log_id: int):
            self.experiment_id = experiment_id
            self.experiment_log_id = log_id

        def model_dump(self, *, mode: str):
            assert mode == "json"
            return {"experiment_id": self.experiment_id, "experiment_log_id": self.experiment_log_id}

    class FakeClient:
        def get_slots(self):
            return [FakeSlot("slot1")]

        def get_experiments(self, slot_name: str):
            assert slot_name == "slot1"
            return [FakeExperiment()]

        def get_experiment(self, slot_name: str, experiment_id: int):
            return FakeExperiment()

        def get_experiment_ramp_up_logs(self, slot_name: str):
            assert slot_name == "slot1"
            return [FakeLog(2, 1), FakeLog(3, 2)]

    monkeypatch.setattr("hotvect.experiment_management.commands._create_client_from_args", lambda _args: FakeClient())
    printed = _capture_print(monkeypatch)
    ExperimentCommand().execute(args)
    out = json.loads(printed["out"])
    assert out["slot_name"] == "slot1"
    assert [e["experiment_log_id"] for e in out["experiment_ramp_up_log"]] == [1]


def test_hv_exp_default_variant_list(monkeypatch):
    args = SimpleNamespace(
        subcommand="default-variant",
        default_variant_subcommand="list",
        slot_name="a",
        url="http://localhost:9999",
        token_provider_command="echo tok",
        token_provider_ttl_ms=1000,
        config_path="",
    )

    class FakeVariant:
        def model_dump(self, *, mode: str):
            assert mode == "json"
            return {"variant_id": 1}

    class FakeActiveInfo:
        default_variant = FakeVariant()

    class FakeClient:
        def get_default_variant_and_active_experiments(self, slot_name: str):
            assert slot_name == "a"
            return FakeActiveInfo()

    monkeypatch.setattr("hotvect.experiment_management.commands._create_client_from_args", lambda _args: FakeClient())
    printed = _capture_print(monkeypatch)
    ExperimentCommand().execute(args)
    out = json.loads(printed["out"])
    assert out["default_variants"] == [{"slot_name": "a", "default_variant": {"variant_id": 1}}]


def test_hv_exp_algorithm_list_active_slot_filter(monkeypatch):
    args = SimpleNamespace(
        subcommand="algorithm",
        algorithm_subcommand="list-active",
        slot_name="slot1",
        url="http://localhost:9999",
        token_provider_command="echo tok",
        token_provider_ttl_ms=1000,
        config_path="",
    )

    class FakeAlgo:
        def __init__(self, name: str, version: str, state: str = "ACTIVE"):
            self.algorithm_name = name
            self.algorithm_version = version
            self.state = state

        def model_dump(self, *, mode: str):
            assert mode == "json"
            return {"algorithm_name": self.algorithm_name, "algorithm_version": self.algorithm_version}

    class FakeVariantInfo:
        def __init__(self, slot_name: str):
            self.slot_name = slot_name

    class FakeAlgoWithVariants:
        def __init__(self, name: str, version: str, slots: list[str]):
            self.algorithm_name = name
            self.algorithm_version = version
            self.variants = [FakeVariantInfo(s) for s in slots]

    class FakeClient:
        def get_active_algorithms(self):
            return [FakeAlgo("a", "1"), FakeAlgo("b", "1")]

        def get_algorithms_with_active_variants(self):
            return [FakeAlgoWithVariants("a", "1", ["slot1"]), FakeAlgoWithVariants("b", "1", ["other"])]

    monkeypatch.setattr("hotvect.experiment_management.commands._create_client_from_args", lambda _args: FakeClient())
    printed = _capture_print(monkeypatch)
    ExperimentCommand().execute(args)
    out = json.loads(printed["out"])
    assert out["algorithms"] == [{"algorithm_name": "a", "algorithm_version": "1"}]


def test_hv_exp_algorithm_parameter_list_filters_name_and_version(monkeypatch):
    args = SimpleNamespace(
        subcommand="algorithm",
        algorithm_subcommand="parameter",
        algorithm_parameter_subcommand="list",
        algorithm_name="a",
        algorithm_version="1",
        url="http://localhost:9999",
        token_provider_command="echo tok",
        token_provider_ttl_ms=1000,
        config_path="",
    )

    class FakeAlgorithm:
        def __init__(self, name: str, version: str):
            self.algorithm_name = name
            self.algorithm_version = version

    class FakeParam:
        def __init__(self, param_id: str, name: str, version: str):
            self.algorithm_parameter_id = param_id
            self.algorithm = FakeAlgorithm(name, version)

        def model_dump(self, *, mode: str):
            assert mode == "json"
            return {
                "algorithm_parameter_id": self.algorithm_parameter_id,
                "algorithm": {
                    "algorithm_name": self.algorithm.algorithm_name,
                    "algorithm_version": self.algorithm.algorithm_version,
                },
            }

    class FakeClient:
        def get_algorithm_parameters(self):
            return [FakeParam("p1", "a", "1"), FakeParam("p2", "a", "2")]

    monkeypatch.setattr("hotvect.experiment_management.commands._create_client_from_args", lambda _args: FakeClient())
    printed = _capture_print(monkeypatch)
    ExperimentCommand().execute(args)
    out = json.loads(printed["out"])
    assert [p["algorithm_parameter_id"] for p in out["algorithm_parameters"]] == ["p1"]


def test_hv_exp_algorithm_list_in_use_slot_filter(monkeypatch):
    args = SimpleNamespace(
        subcommand="algorithm",
        algorithm_subcommand="list-in-use",
        slot_name="slot1",
        url="http://localhost:9999",
        token_provider_command="echo tok",
        token_provider_ttl_ms=1000,
        config_path="",
    )

    class FakeVariant:
        def __init__(self, slot_name: str, variant_id: int):
            self.slot_name = slot_name
            self.variant_id = variant_id

    class FakeAlgo:
        def __init__(self, name: str, version: str, variants):
            self.algorithm_name = name
            self.algorithm_version = version
            self.variants = variants

    class FakeClient:
        def get_algorithms_with_active_variants(self):
            return [
                FakeAlgo("a", "1", [FakeVariant("slot1", 1), FakeVariant("slot2", 3)]),
                FakeAlgo("b", "2", [FakeVariant("slot1", 2)]),
                FakeAlgo("policy", "1", [FakeVariant("slot1", 1)]),
                FakeAlgo("other", "1", [FakeVariant("slot2", 4)]),
            ]

    monkeypatch.setattr("hotvect.experiment_management.commands._create_client_from_args", lambda _args: FakeClient())
    printed = _capture_print(monkeypatch)
    ExperimentCommand().execute(args)
    out = json.loads(printed["out"])

    assert out["slot_name"] == "slot1"
    by_algo = {(a["algorithm_name"], a["algorithm_version"]): a for a in out["algorithms"]}
    assert set(by_algo.keys()) == {("a", "1"), ("b", "2"), ("policy", "1")}

    assert by_algo[("a", "1")]["in_use_by"] == [{"slot_name": "slot1", "variant_id": 1}]
    assert by_algo[("b", "2")]["in_use_by"] == [{"slot_name": "slot1", "variant_id": 2}]
    assert by_algo[("policy", "1")]["in_use_by"] == [{"slot_name": "slot1", "variant_id": 1}]


def test_hv_exp_algorithm_list_in_use_uses_active_variants_endpoint(monkeypatch):
    args = SimpleNamespace(
        subcommand="algorithm",
        algorithm_subcommand="list-in-use",
        slot_name="slot1",
        url="http://localhost:9999",
        token_provider_command="echo tok",
        token_provider_ttl_ms=1000,
        config_path="",
    )
    payload = {
        "algorithms": [
            {
                "algorithmName": "singleton-default",
                "algorithmVersion": "1.0.0",
                "latestAlgorithmParameter": {
                    "algorithm": {
                        "algorithm_name": "singleton-default",
                        "algorithm_version": "1.0.0",
                    }
                },
                "variants": [{"variantId": 1, "slotName": "slot1"}],
            },
            {
                "algorithmName": "singleton-experiment",
                "algorithmVersion": "2.0.0",
                "variants": [{"variantId": 7, "slotName": "slot1"}],
            },
        ]
    }

    class FakeClient:
        def __init__(self):
            self._operations = _GeneratedEmsOperations(self._make_request)

        @staticmethod
        def _make_request(*, method, endpoint, params=None, body=None):
            assert method == "GET"
            assert endpoint == "/algorithms/with-active-variants"
            assert params is None
            assert body is None
            return SimpleNamespace(
                status_code=200,
                text="payload",
                json=lambda: payload,
                raise_for_status=lambda: None,
            )

        def get_algorithms_with_active_variants(self):
            response = self._operations.operation_list_active_with_variants()
            return response.algorithms

    monkeypatch.setattr("hotvect.experiment_management.commands._create_client_from_args", lambda _args: FakeClient())
    printed = _capture_print(monkeypatch)
    ExperimentCommand().execute(args)
    out = json.loads(printed["out"])

    assert {(entry["algorithm_name"], entry["algorithm_version"]) for entry in out["algorithms"]} == {
        ("singleton-default", "1.0.0"),
        ("singleton-experiment", "2.0.0"),
    }


def test_hv_exp_algorithm_list_in_use_all_slots(monkeypatch):
    args = SimpleNamespace(
        subcommand="algorithm",
        algorithm_subcommand="list-in-use",
        slot_name="",
        url="http://localhost:9999",
        token_provider_command="echo tok",
        token_provider_ttl_ms=1000,
        config_path="",
    )

    class FakeVariant:
        def __init__(self, slot_name: str, variant_id: int):
            self.slot_name = slot_name
            self.variant_id = variant_id

    class FakeAlgo:
        def __init__(self, name: str, version: str, variants):
            self.algorithm_name = name
            self.algorithm_version = version
            self.variants = variants

    class FakeClient:
        def get_algorithms_with_active_variants(self):
            return [
                FakeAlgo("a", "1", [FakeVariant("slot1", 10), FakeVariant("slot2", 21)]),
                FakeAlgo("b", "2", [FakeVariant("slot1", 11)]),
                FakeAlgo("c", "3", [FakeVariant("slot2", 20)]),
            ]

    monkeypatch.setattr("hotvect.experiment_management.commands._create_client_from_args", lambda _args: FakeClient())
    printed = _capture_print(monkeypatch)
    ExperimentCommand().execute(args)
    out = json.loads(printed["out"])

    assert out["slot_name"] is None
    by_algo = {(a["algorithm_name"], a["algorithm_version"]): a for a in out["algorithms"]}
    assert set(by_algo.keys()) == {("a", "1"), ("b", "2"), ("c", "3")}

    a_usage = by_algo[("a", "1")]["in_use_by"]
    assert {"slot_name": "slot1", "variant_id": 10} in a_usage
    assert {"slot_name": "slot2", "variant_id": 21} in a_usage


def test_resolve_online_results_root_from_config_path(tmp_path):
    config_path = tmp_path / "config.json"
    config_path.write_text(
        json.dumps({"directories": {"output_base_dir": str(tmp_path / "output")}}),
        encoding="utf-8",
    )

    args = SimpleNamespace(config_path=str(config_path), output_base_dir="")

    assert _resolve_online_results_root_from_args(args) == tmp_path / "output" / "meta" / "online-evaluation-results"


def test_hv_exp_experiment_results_list_skips_ems_client(monkeypatch):
    args = SimpleNamespace(
        subcommand="experiment",
        experiment_subcommand="results",
        experiment_results_subcommand="list",
        experiment_id=1208,
        s3_base_prefix="s3://bucket/root/",
        role_arn="",
    )

    class FakeStore:
        s3_base_prefix = "s3://bucket/root/"

        def list_analysis_dates(self, *, experiment_id: int):
            assert experiment_id == 1208
            return [
                {"analysis_date": "2026-01-21", "part_count": 2, "s3_prefix": "s3://bucket/root/experiment_id=1208/"}
            ]

    monkeypatch.setattr(
        "hotvect.experiment_management.commands._create_client_from_args",
        lambda _args: (_ for _ in ()).throw(AssertionError("EMS client should not be created")),
    )
    monkeypatch.setattr(
        "hotvect.experiment_management.commands._create_online_results_store_from_args",
        lambda _args, **_kwargs: FakeStore(),
    )

    printed = _capture_print(monkeypatch)
    ExperimentCommand().execute(args)
    out = json.loads(printed["out"])

    assert out["experiment_id"] == 1208
    assert out["analysis_dates"][0]["analysis_date"] == "2026-01-21"


def test_hv_exp_experiment_results_show_streams_raw_output(monkeypatch):
    args = SimpleNamespace(
        subcommand="experiment",
        experiment_subcommand="results",
        experiment_results_subcommand="show",
        experiment_id=1208,
        analysis_date="2026-01-21",
        s3_base_prefix="s3://bucket/root/",
        role_arn="",
    )
    buffer = io.BytesIO()

    class FakeStore:
        def stream_analysis_date(self, *, experiment_id: int, analysis_date: str, output_stream):
            assert experiment_id == 1208
            assert analysis_date == "2026-01-21"
            output_stream.write(b'{"row":1}\n')

    monkeypatch.setattr(
        "hotvect.experiment_management.commands._create_online_results_store_from_args",
        lambda _args, **_kwargs: FakeStore(),
    )
    monkeypatch.setattr("sys.stdout", SimpleNamespace(buffer=buffer))

    ExperimentCommand().execute(args)

    assert buffer.getvalue() == b'{"row":1}\n'


def test_create_online_results_store_from_args_uses_slot_mapping_from_config(monkeypatch):
    args = SimpleNamespace(
        s3_base_prefix="",
        role_arn="",
        config_path="",
    )

    class FakeSlot:
        def __init__(self, name: str):
            self.name = name

    class FakeExperiment:
        experiment_id = 1304

    class FakeClient:
        def get_slots(self):
            return [FakeSlot("slot-a")]

        def get_experiments(self, slot_name):
            assert slot_name == "slot-a"
            return [FakeExperiment()]

    class FakeSession:
        def client(self, service_name):
            assert service_name == "s3"
            return object()

    monkeypatch.setattr(
        "hotvect.experiment_management.commands._load_hotvect_config_from_args",
        lambda _args: {
            "experiment_management": {
                "url": "http://localhost:1111",
                "token_provider_command": "echo tok",
                "online_results": {
                    "slots": {
                        "slot-a": {"s3_base_prefix": "s3://bucket/slot-a-results/"},
                    }
                },
            }
        },
    )
    monkeypatch.setattr("hotvect.experiment_management.commands._create_client_from_args", lambda _args: FakeClient())
    monkeypatch.setattr("hotvect.experiment_management.commands.boto3.Session", lambda: FakeSession())

    store = _create_online_results_store_from_args(args, experiment_id=1304)

    assert store.s3_base_prefix == "s3://bucket/slot-a-results/"


def test_create_online_results_store_from_args_requires_experiment_id():
    args = SimpleNamespace(
        s3_base_prefix="",
        role_arn="",
        config_path="",
    )

    try:
        _create_online_results_store_from_args(args)
    except TypeError as exc:
        assert "experiment_id" in str(exc)
    else:
        raise AssertionError("expected TypeError")


def test_create_online_results_store_from_args_rejects_missing_slot_mapping(monkeypatch):
    args = SimpleNamespace(
        s3_base_prefix="",
        role_arn="",
        config_path="",
    )

    class FakeSlot:
        def __init__(self, name: str):
            self.name = name

    class FakeExperiment:
        experiment_id = 1304

    class FakeClient:
        def get_slots(self):
            return [FakeSlot("slot-a")]

        def get_experiments(self, slot_name):
            assert slot_name == "slot-a"
            return [FakeExperiment()]

    monkeypatch.setattr(
        "hotvect.experiment_management.commands._load_hotvect_config_from_args",
        lambda _args: {
            "experiment_management": {
                "url": "http://localhost:1111",
                "token_provider_command": "echo tok",
                "online_results": {"slots": {}},
            }
        },
    )
    monkeypatch.setattr("hotvect.experiment_management.commands._create_client_from_args", lambda _args: FakeClient())

    try:
        _create_online_results_store_from_args(args, experiment_id=1304)
    except ValueError as exc:
        assert "online_results.slots.slot-a.s3_base_prefix" in str(exc)
    else:
        raise AssertionError("expected ValueError")
