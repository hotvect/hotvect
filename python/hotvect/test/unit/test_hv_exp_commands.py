from __future__ import annotations

import json
from types import SimpleNamespace

from hotvect.experiment_management.commands import ExperimentCommand


def _capture_print(monkeypatch):
    printed = {}
    monkeypatch.setattr("builtins.print", lambda s: printed.setdefault("out", s))
    return printed


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
