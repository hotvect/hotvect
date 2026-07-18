from __future__ import annotations

import io
import json
from types import SimpleNamespace

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

    class FakeConnection:
        def __init__(self, *, environment, connect_timeout, read_timeout, bearer_auth):
            recorded["environment"] = environment
            recorded["connect_timeout"] = connect_timeout
            recorded["read_timeout"] = read_timeout
            recorded["bearer_auth"] = bearer_auth

    class FakeClient:
        def __init__(self, connection):
            recorded["connection"] = connection

    monkeypatch.setattr(
        "hotvect.experiment_management.commands.CommandTokenProvider", lambda command, ttl_seconds: command
    )
    monkeypatch.setattr("hotvect.experiment_management.commands.TokenProviderAuth", lambda provider: provider)
    monkeypatch.setattr("hotvect.experiment_management.commands.ExperimentManagementConnection", FakeConnection)
    monkeypatch.setattr("hotvect.experiment_management.commands.ExperimentManagementClient", FakeClient)

    _create_client_from_args(args)

    assert recorded["environment"] == "http://localhost:9999"
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

    class FakeConnection:
        def __init__(self, *, environment, connect_timeout, read_timeout, bearer_auth):
            recorded["environment"] = environment
            recorded["connect_timeout"] = connect_timeout
            recorded["read_timeout"] = read_timeout
            recorded["bearer_auth"] = bearer_auth

    class FakeClient:
        def __init__(self, connection):
            recorded["connection"] = connection

    monkeypatch.setattr(
        "hotvect.experiment_management.commands.CommandTokenProvider", lambda command, ttl_seconds: command
    )
    monkeypatch.setattr("hotvect.experiment_management.commands.TokenProviderAuth", lambda provider: provider)
    monkeypatch.setattr("hotvect.experiment_management.commands.ExperimentManagementConnection", FakeConnection)
    monkeypatch.setattr("hotvect.experiment_management.commands.ExperimentManagementClient", FakeClient)

    _create_client_from_args(args)

    assert recorded["environment"] == "http://localhost:9999"
    assert recorded["connect_timeout"] == 6.0
    assert recorded["read_timeout"] == 17.5


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
        def model_dump(self, *, mode: str):
            assert mode == "json"
            return {"experiment_id": 12}

    class FakeClient:
        def get_slots(self):
            return [FakeSlot("a"), FakeSlot("b")]

        def get_experiment(self, slot_name: str, experiment_id: int, *, ignore_404: bool = False):
            assert experiment_id == 12
            if slot_name == "b":
                return FakeExperiment()
            if ignore_404:
                return None
            raise RuntimeError("should not be called without ignore_404 for non-match")

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
        pass

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

        def get_experiment(self, slot_name: str, experiment_id: int, *, ignore_404: bool = False):
            assert slot_name == "slot1"
            assert experiment_id == 2
            return FakeExperiment()

        def get_experiment_rampup_logs(self, slot_name: str):
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

    class FakeAlgo:
        def __init__(self, name: str, version: str):
            self.algorithm_name = name
            self.algorithm_version = version

    class FakeVariant:
        def __init__(self, variant_id: int, name: str, version: str):
            self.variant_id = variant_id
            self.algorithm = FakeAlgo(name, version)

    class FakeExperiment:
        def __init__(self, experiment_id: int, variants):
            self.experiment_id = experiment_id
            self.variants = variants

    class FakeActiveInfo:
        def __init__(self):
            self.default_variant = FakeVariant(1, "a", "1")
            self.experiments = [FakeExperiment(11, [FakeVariant(2, "b", "2"), FakeVariant(3, "a", "1")])]

    class FakeClient:
        def get_default_variant_and_active_experiments(self, slot_name: str):
            assert slot_name == "slot1"
            return FakeActiveInfo()

    monkeypatch.setattr("hotvect.experiment_management.commands._create_client_from_args", lambda _args: FakeClient())
    printed = _capture_print(monkeypatch)
    ExperimentCommand().execute(args)
    out = json.loads(printed["out"])

    assert out["slot_name"] == "slot1"
    by_algo = {(a["algorithm_name"], a["algorithm_version"]): a for a in out["algorithms"]}
    assert set(by_algo.keys()) == {("a", "1"), ("b", "2")}

    a_usage = by_algo[("a", "1")]["in_use_by"]
    assert any(u["slot_name"] == "slot1" and u["source"] == "default_variant" and u["variant_id"] == 1 for u in a_usage)
    assert any(
        u["slot_name"] == "slot1"
        and u["source"] == "active_experiment"
        and u["experiment_id"] == 11
        and u["variant_id"] == 3
        for u in a_usage
    )

    b_usage = by_algo[("b", "2")]["in_use_by"]
    assert b_usage == [{"slot_name": "slot1", "source": "active_experiment", "experiment_id": 11, "variant_id": 2}]


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

    class FakeSlot:
        def __init__(self, name: str):
            self.name = name

    class FakeAlgo:
        def __init__(self, name: str, version: str):
            self.algorithm_name = name
            self.algorithm_version = version

    class FakeVariant:
        def __init__(self, variant_id: int, name: str, version: str):
            self.variant_id = variant_id
            self.algorithm = FakeAlgo(name, version)

    class FakeExperiment:
        def __init__(self, experiment_id: int, variants):
            self.experiment_id = experiment_id
            self.variants = variants

    class FakeActiveInfo:
        def __init__(self, default_variant, experiments):
            self.default_variant = default_variant
            self.experiments = experiments

    class FakeClient:
        def get_slots(self):
            return [FakeSlot("slot1"), FakeSlot("slot2")]

        def get_default_variant_and_active_experiments(self, slot_name: str):
            if slot_name == "slot1":
                return FakeActiveInfo(
                    default_variant=FakeVariant(10, "a", "1"),
                    experiments=[FakeExperiment(100, [FakeVariant(11, "b", "2")])],
                )
            if slot_name == "slot2":
                return FakeActiveInfo(
                    default_variant=FakeVariant(20, "c", "3"),
                    experiments=[FakeExperiment(200, [FakeVariant(21, "a", "1")])],
                )
            raise AssertionError(f"unexpected slot: {slot_name}")

    monkeypatch.setattr("hotvect.experiment_management.commands._create_client_from_args", lambda _args: FakeClient())
    printed = _capture_print(monkeypatch)
    ExperimentCommand().execute(args)
    out = json.loads(printed["out"])

    assert out["slot_name"] is None
    by_algo = {(a["algorithm_name"], a["algorithm_version"]): a for a in out["algorithms"]}
    assert set(by_algo.keys()) == {("a", "1"), ("b", "2"), ("c", "3")}

    a_usage = by_algo[("a", "1")]["in_use_by"]
    assert {"slot_name": "slot1", "source": "default_variant", "variant_id": 10} in a_usage
    assert {"slot_name": "slot2", "source": "active_experiment", "experiment_id": 200, "variant_id": 21} in a_usage


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

    class FakeClient:
        def get_slots(self):
            return [FakeSlot("slot-a")]

        def get_experiment(self, slot_name, experiment_id, ignore_404=False):
            assert slot_name == "slot-a"
            assert experiment_id == 1304
            return object()

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

    class FakeClient:
        def get_slots(self):
            return [FakeSlot("slot-a")]

        def get_experiment(self, slot_name, experiment_id, ignore_404=False):
            assert slot_name == "slot-a"
            assert experiment_id == 1304
            return object()

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
