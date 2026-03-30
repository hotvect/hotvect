"""hv-exp command implementations."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, Optional

from hotvect.experiment_management import (
    CommandTokenProvider,
    ExperimentManagementClient,
    ExperimentManagementConnection,
    TokenProviderAuth,
)
from hotvect.experiment_management.hotvect_config import load_experiment_management_hotvect_config
from hotvect.extra import config as hv_config


def _load_config_from_path(path: Path) -> Dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(data, dict) and "config" in data and isinstance(data["config"], dict):
        return data["config"]
    if isinstance(data, dict):
        return data
    raise ValueError(f"Invalid config JSON (expected object): {path}")


def _create_client_from_args(args) -> ExperimentManagementClient:
    url = getattr(args, "url", None)
    token_provider_command = getattr(args, "token_provider_command", None)
    token_provider_ttl_ms = getattr(args, "token_provider_ttl_ms", None)
    config_path = getattr(args, "config_path", None)

    if (url is None) ^ (token_provider_command is None):
        raise ValueError("Provide both --url and --token-provider-command, or omit both.")

    if url is not None and token_provider_command is not None:
        ttl_ms = int(token_provider_ttl_ms or 3600_000)
        provider = CommandTokenProvider(command=str(token_provider_command), ttl_seconds=ttl_ms / 1000.0)
        auth = TokenProviderAuth(provider)
        conn = ExperimentManagementConnection(environment=str(url), bearer_auth=auth)
        return ExperimentManagementClient(conn)

    cfg: Optional[Dict[str, Any]] = None
    if config_path:
        cfg_path = Path(str(config_path)).expanduser()
        if not cfg_path.exists():
            raise FileNotFoundError(f"Config not found: {cfg_path}")
        cfg = _load_config_from_path(cfg_path)
    else:
        cfg = hv_config.load_config()

    em_cfg = load_experiment_management_hotvect_config(config=cfg)
    provider = CommandTokenProvider(
        command=em_cfg.token_provider_command,
        ttl_seconds=em_cfg.token_provider_ttl_ms / 1000.0,
    )
    auth = TokenProviderAuth(provider)
    conn = ExperimentManagementConnection(environment=em_cfg.url, bearer_auth=auth)
    return ExperimentManagementClient(conn)


def _resolve_slot_name_for_experiment_id(client: ExperimentManagementClient, *, experiment_id: int) -> str:
    slots = client.get_slots() or []
    for slot in slots:
        exp = client.get_experiment(slot.name, int(experiment_id), ignore_404=True)
        if exp is not None:
            return slot.name
    raise ValueError(f"Experiment id not found in any slot: {int(experiment_id)}")


def _experiments_for_slot(client: ExperimentManagementClient, *, slot_name: str) -> list[dict[str, Any]]:
    experiments = client.get_experiments(slot_name) or []
    return [{"slot_name": slot_name, "experiment": e.model_dump(mode="json")} for e in experiments]


def _experiment_id_for_rampup_log(log: Any) -> int | None:
    experiment_id = getattr(log, "experiment_id", None)
    if experiment_id is not None:
        return int(experiment_id)
    experiment = getattr(log, "experiment", None)
    if experiment is None:
        return None
    return getattr(experiment, "experiment_id", None)


def _sorted_slot_names_for_list_in_use(client: ExperimentManagementClient, *, slot_name: str) -> list[str]:
    slot_name = slot_name.strip()
    if slot_name:
        return [slot_name]
    slots = client.get_slots() or []
    return sorted(s.name for s in slots)


class ExperimentCommand:
    @classmethod
    def register_parser(cls, parser: Any) -> Any:
        parser.add_argument(
            "--config-path",
            default="",
            help="Path to hotvect config JSON (default: ~/.hotvect/config.json)",
        )
        parser.add_argument(
            "--url",
            default=None,
            help="Override experiment-management base URL (requires --token-provider-command).",
        )
        parser.add_argument(
            "--token-provider-command",
            default=None,
            help="Override token provider command that prints a bearer token (requires --url).",
        )
        parser.add_argument(
            "--token-provider-ttl-ms",
            type=int,
            default=3600_000,
            help="Token cache TTL in ms when using --token-provider-command (default: 3600000).",
        )

        top = parser.add_subparsers(dest="subcommand", required=True, metavar="<subcommand>")

        slot = top.add_parser("slot", help="Slot operations")
        slot_sub = slot.add_subparsers(dest="slot_subcommand", required=True, metavar="<subcommand>")
        slot_sub.add_parser("list", help="List slots")
        slot_get = slot_sub.add_parser("get", help="Get default variant + active experiments for a slot")
        slot_get.add_argument("--slot-name", required=True)

        experiment = top.add_parser("experiment", help="Experiment operations")
        exp_sub = experiment.add_subparsers(dest="experiment_subcommand", required=True, metavar="<subcommand>")

        exp_list = exp_sub.add_parser("list", help="List experiments")
        exp_list.add_argument("--slot-name", default="", help="Optional slot name")

        exp_get = exp_sub.add_parser("get", help="Get experiment by id")
        exp_get.add_argument("--experiment-id", type=int, required=True)

        exp_rampup = exp_sub.add_parser("rampup-log", help="Get experiment ramp-up log")
        exp_rampup.add_argument("--experiment-id", type=int, required=True)

        default_variant = top.add_parser("default-variant", help="Default-variant operations")
        dv_sub = default_variant.add_subparsers(
            dest="default_variant_subcommand", required=True, metavar="<subcommand>"
        )
        dv_list = dv_sub.add_parser("list", help="List default variants by slot")
        dv_list.add_argument("--slot-name", default="", help="Optional slot name")

        algorithm = top.add_parser("algorithm", help="Algorithm operations")
        algo_sub = algorithm.add_subparsers(dest="algorithm_subcommand", required=True, metavar="<subcommand>")

        algo_list = algo_sub.add_parser("list", help="List algorithms")
        algo_list.add_argument("--slot-name", default="", help="Optional slot name")

        algo_list_active = algo_sub.add_parser("list-active", help="List active algorithms")
        algo_list_active.add_argument("--slot-name", default="", help="Optional slot name")

        algo_list_in_use = algo_sub.add_parser(
            "list-in-use",
            help="List algorithms currently in use (default variants + active experiments)",
        )
        algo_list_in_use.add_argument("--slot-name", default="", help="Optional slot name")

        algo_get = algo_sub.add_parser("get", help="Get algorithm by name/version")
        algo_get.add_argument("--algorithm-name", required=True)
        algo_get.add_argument("--algorithm-version", required=True)

        algo_param = algo_sub.add_parser("parameter", help="Algorithm parameter operations")
        algo_param_sub = algo_param.add_subparsers(dest="algorithm_parameter_subcommand", required=True)

        algo_param_list = algo_param_sub.add_parser("list", help="List algorithm parameters for an algorithm")
        algo_param_list.add_argument("--algorithm-name", required=True)
        algo_param_list.add_argument("--algorithm-version", required=True)

        algo_param_get = algo_param_sub.add_parser("get", help="Get algorithm parameter by id")
        algo_param_get.add_argument("--algorithm-parameter-id", required=True)

        return parser

    def execute(self, args: Any) -> None:
        client = _create_client_from_args(args)

        if args.subcommand == "slot":
            if args.slot_subcommand == "list":
                slots = client.get_slots() or []
                out = {"ok": True, "slots": [s.model_dump(mode="json") for s in slots]}
                print(json.dumps(out, indent=2))
                return

            if args.slot_subcommand == "get":
                info = client.get_default_variant_and_active_experiments(args.slot_name)
                out = {"ok": True, "active_info": info.model_dump(mode="json") if info else None}
                print(json.dumps(out, indent=2))
                return

            raise ValueError(f"Unknown slot subcommand: {args.slot_subcommand}")

        if args.subcommand == "experiment":
            if args.experiment_subcommand == "list":
                slot_name = str(getattr(args, "slot_name", "") or "").strip()
                items: list[dict[str, Any]] = []
                if slot_name:
                    items.extend(_experiments_for_slot(client, slot_name=slot_name))
                else:
                    slots = client.get_slots() or []
                    for s in slots:
                        items.extend(_experiments_for_slot(client, slot_name=s.name))

                out = {"ok": True, "experiments": items}
                print(json.dumps(out, indent=2))
                return

            if args.experiment_subcommand == "get":
                experiment_id = int(args.experiment_id)
                slot_name = _resolve_slot_name_for_experiment_id(client, experiment_id=experiment_id)
                experiment = client.get_experiment(slot_name, experiment_id)
                out = {
                    "ok": True,
                    "slot_name": slot_name,
                    "experiment": experiment.model_dump(mode="json") if experiment else None,
                }
                print(json.dumps(out, indent=2))
                return

            if args.experiment_subcommand == "rampup-log":
                experiment_id = int(args.experiment_id)
                slot_name = _resolve_slot_name_for_experiment_id(client, experiment_id=experiment_id)
                logs = client.get_experiment_rampup_logs(slot_name) or []
                filtered = [log for log in logs if _experiment_id_for_rampup_log(log) == experiment_id]
                out = {
                    "ok": True,
                    "slot_name": slot_name,
                    "experiment_id": experiment_id,
                    "experiment_ramp_up_log": [log.model_dump(mode="json") for log in filtered],
                }
                print(json.dumps(out, indent=2))
                return

            raise ValueError(f"Unknown experiment subcommand: {args.experiment_subcommand}")

        if args.subcommand == "default-variant":
            if args.default_variant_subcommand != "list":
                raise ValueError(f"Unknown default-variant subcommand: {args.default_variant_subcommand}")

            slot_name = str(getattr(args, "slot_name", "") or "").strip()
            defaults: list[dict[str, Any]] = []
            if slot_name:
                info = client.get_default_variant_and_active_experiments(slot_name)
                if info:
                    defaults.append(
                        {"slot_name": slot_name, "default_variant": info.default_variant.model_dump(mode="json")}
                    )
            else:
                slots = client.get_slots() or []
                for s in slots:
                    info = client.get_default_variant_and_active_experiments(s.name)
                    if not info:
                        continue
                    defaults.append(
                        {"slot_name": s.name, "default_variant": info.default_variant.model_dump(mode="json")}
                    )

            out = {"ok": True, "default_variants": defaults}
            print(json.dumps(out, indent=2))
            return

        if args.subcommand == "algorithm":
            if args.algorithm_subcommand == "list":
                slot_name = str(getattr(args, "slot_name", "") or "").strip()
                algos = client.get_algorithms() or []
                if slot_name:
                    with_active_variants = client.get_algorithms_with_active_variants() or []
                    allowed = {
                        (a.algorithm_name, a.algorithm_version)
                        for a in with_active_variants
                        if any(v.slot_name == slot_name for v in a.variants)
                    }
                    algos = [a for a in algos if (a.algorithm_name, a.algorithm_version) in allowed]
                out = {"ok": True, "algorithms": [a.model_dump(mode="json") for a in algos]}
                print(json.dumps(out, indent=2))
                return

            if args.algorithm_subcommand == "list-active":
                slot_name = str(getattr(args, "slot_name", "") or "").strip()
                algos = client.get_active_algorithms() or []
                if slot_name:
                    with_active_variants = client.get_algorithms_with_active_variants() or []
                    allowed = {
                        (a.algorithm_name, a.algorithm_version)
                        for a in with_active_variants
                        if any(v.slot_name == slot_name for v in a.variants)
                    }
                    algos = [a for a in algos if (a.algorithm_name, a.algorithm_version) in allowed]
                out = {"ok": True, "algorithms": [a.model_dump(mode="json") for a in algos]}
                print(json.dumps(out, indent=2))
                return

            if args.algorithm_subcommand == "list-in-use":
                slot_name = str(getattr(args, "slot_name", "") or "").strip()
                slot_names = _sorted_slot_names_for_list_in_use(client, slot_name=slot_name)

                by_algo: dict[tuple[str, str], dict[str, Any]] = {}
                seen_usage: set[tuple[str, str, str, int | None, int]] = set()

                for sn in slot_names:
                    info = client.get_default_variant_and_active_experiments(sn)
                    if info is None:
                        continue

                    default_variant = info.default_variant
                    default_key = (
                        default_variant.algorithm.algorithm_name,
                        default_variant.algorithm.algorithm_version,
                    )
                    by_algo.setdefault(
                        default_key,
                        {
                            "algorithm_name": default_key[0],
                            "algorithm_version": default_key[1],
                            "in_use_by": [],
                        },
                    )
                    usage_key = (default_key[0], default_key[1], sn, None, int(default_variant.variant_id))
                    if usage_key not in seen_usage:
                        by_algo[default_key]["in_use_by"].append(
                            {
                                "slot_name": sn,
                                "source": "default_variant",
                                "variant_id": int(default_variant.variant_id),
                            }
                        )
                        seen_usage.add(usage_key)

                    for experiment in info.experiments or []:
                        experiment_id = int(experiment.experiment_id)
                        for variant in experiment.variants or []:
                            algo_key = (variant.algorithm.algorithm_name, variant.algorithm.algorithm_version)
                            by_algo.setdefault(
                                algo_key,
                                {
                                    "algorithm_name": algo_key[0],
                                    "algorithm_version": algo_key[1],
                                    "in_use_by": [],
                                },
                            )
                            usage_key = (algo_key[0], algo_key[1], sn, experiment_id, int(variant.variant_id))
                            if usage_key in seen_usage:
                                continue
                            by_algo[algo_key]["in_use_by"].append(
                                {
                                    "slot_name": sn,
                                    "source": "active_experiment",
                                    "experiment_id": experiment_id,
                                    "variant_id": int(variant.variant_id),
                                }
                            )
                            seen_usage.add(usage_key)

                algorithms: list[dict[str, Any]] = []
                for key in sorted(by_algo.keys()):
                    entry = by_algo[key]
                    entry["in_use_by"] = sorted(
                        entry["in_use_by"],
                        key=lambda u: (
                            str(u.get("slot_name", "")),
                            str(u.get("source", "")),
                            int(u.get("experiment_id", -1)),
                            int(u.get("variant_id", -1)),
                        ),
                    )
                    algorithms.append(entry)

                out = {
                    "ok": True,
                    "slot_name": slot_name or None,
                    "algorithms": algorithms,
                }
                print(json.dumps(out, indent=2))
                return

            if args.algorithm_subcommand == "get":
                algo = client.get_algorithm(args.algorithm_name, args.algorithm_version)
                out = {"ok": True, "algorithm": algo.model_dump(mode="json") if algo else None}
                print(json.dumps(out, indent=2))
                return

            if args.algorithm_subcommand == "parameter":
                if args.algorithm_parameter_subcommand == "list":
                    params = client.get_algorithm_parameters() or []
                    filtered = [
                        p
                        for p in params
                        if p.algorithm.algorithm_name == args.algorithm_name
                        and p.algorithm.algorithm_version == args.algorithm_version
                    ]
                    out = {
                        "ok": True,
                        "algorithm_name": args.algorithm_name,
                        "algorithm_version": args.algorithm_version,
                        "algorithm_parameters": [p.model_dump(mode="json") for p in filtered],
                    }
                    print(json.dumps(out, indent=2))
                    return

                if args.algorithm_parameter_subcommand == "get":
                    param = client.get_algorithm_parameter(str(args.algorithm_parameter_id))
                    out = {"ok": True, "algorithm_parameter": param.model_dump(mode="json") if param else None}
                    print(json.dumps(out, indent=2))
                    return

                raise ValueError(f"Unknown algorithm parameter subcommand: {args.algorithm_parameter_subcommand}")

            raise ValueError(f"Unknown algorithm subcommand: {args.algorithm_subcommand}")

        raise ValueError(f"Unknown subcommand: {args.subcommand}")
