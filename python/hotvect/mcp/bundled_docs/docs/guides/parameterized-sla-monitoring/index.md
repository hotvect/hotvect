---
title: Monitor Parameter Update SLA (Parameterized)
description: Build a read-only SLA chart for currently in-use algorithms with configurable cutoff, timezone, and window
tags: [monitoring, sla, hv-exp, visualization, pandas, matplotlib]
difficulty: intermediate
estimated_time: 20 minutes
prerequisites:
  - hv-exp configured
  - Python 3.11+
  - pandas and matplotlib installed
related_docs:
  - ../../reference/cli/index.md
  - ../quickstart/index.md
  - ../../agents/contracts/documentation-sanitization/index.md
related_commands:
  - hv-exp slot list
  - hv-exp slot get
  - hv-exp algorithm parameter list
next_steps:
  - Run the script on a schedule
  - Add alert thresholds on violation ratio
  - Compare multiple SLA cutoffs
---

# How to: Monitor parameter update SLA (parameterized)

This guide shows how to produce a daily SLA chart for **currently in-use algorithms** using `hv-exp` in read-only mode.

The output includes:

- A line chart of earliest daily update time per algorithm
- A bottom summary table sorted by violation ratio

## Parameters

The script is parameterized with:

- `--sla-time` (format `HH:MM`, for example `06:45`)
- `--timezone` (IANA timezone, for example `Europe/Berlin`)
- `--window-days` (for example `14`)
- `--exclude-first-active-day` (optional switch)
- `--include-shadow-slots` (optional switch)
- `--out-dir` (output folder)

## In-use definition

An algorithm is considered in use if it appears in either:

- the slot `default_variant`, or
- an active experiment variant in that slot.

## Run command

```bash
python3 monitor_parameter_sla.py \
  --sla-time 06:45 \
  --timezone Europe/Berlin \
  --window-days 14 \
  --exclude-first-active-day \
  --out-dir ./sla_artifacts
```

## Script snippet

```python
#!/usr/bin/env python3
import argparse
import json
import subprocess
from datetime import datetime, timedelta, timezone
from pathlib import Path

import matplotlib.dates as mdates
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from dateutil import tz


def run_hv_exp(*args: str) -> dict:
    out = subprocess.check_output(["hv-exp", *args]).decode("utf-8")
    return json.loads(out)


def parse_sla_minutes(sla_time: str) -> int:
    hh, mm = sla_time.split(":", 1)
    h, m = int(hh), int(mm)
    if not (0 <= h <= 23 and 0 <= m <= 59):
        raise ValueError(f"Invalid --sla-time: {sla_time}")
    return h * 60 + m


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sla-time", default="06:45")
    parser.add_argument("--timezone", default="Europe/Berlin")
    parser.add_argument("--window-days", type=int, default=14)
    parser.add_argument("--exclude-first-active-day", action="store_true")
    parser.add_argument("--include-shadow-slots", action="store_true")
    parser.add_argument("--out-dir", default="./sla_artifacts")
    args = parser.parse_args()

    sla_minutes = parse_sla_minutes(args.sla_time)
    local_tz = tz.gettz(args.timezone)
    if local_tz is None:
        raise ValueError(f"Invalid --timezone: {args.timezone}")

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    # 1) Resolve current in-use algorithms + first active day map.
    slots = run_hv_exp("slot", "list").get("slots", [])
    slot_names = [s["name"] for s in slots]
    if not args.include_shadow_slots:
        slot_names = [s for s in slot_names if not s.startswith("shadow-")]

    current_pairs: set[tuple[str, str]] = set()
    first_active_day: dict[str, datetime.date] = {}

    def to_local_date(ts: str):
        return datetime.fromisoformat(ts.replace("Z", "+00:00")).astimezone(local_tz).date()

    for slot_name in slot_names:
        active = run_hv_exp("slot", "get", "--slot-name", slot_name).get("active_info")
        if not active:
            continue

        default_variant = active.get("default_variant")
        if default_variant and default_variant.get("algorithm"):
            algo = default_variant["algorithm"]
            pair = (algo["algorithm_name"], algo["algorithm_version"])
            current_pairs.add(pair)
            if default_variant.get("created_at"):
                aid = f"{pair[0]}:{pair[1]}"
                d = to_local_date(default_variant["created_at"])
                if aid not in first_active_day or d < first_active_day[aid]:
                    first_active_day[aid] = d

        for exp in active.get("experiments", []) or []:
            exp_day = to_local_date(exp["created_at"]) if exp.get("created_at") else None
            for variant in exp.get("variants", []) or []:
                algo = variant.get("algorithm")
                if not algo:
                    continue
                pair = (algo["algorithm_name"], algo["algorithm_version"])
                current_pairs.add(pair)
                if exp_day is not None:
                    aid = f"{pair[0]}:{pair[1]}"
                    if aid not in first_active_day or exp_day < first_active_day[aid]:
                        first_active_day[aid] = exp_day

    # 2) Pull parameter events for the time window.
    now_utc = datetime.now(timezone.utc)
    start_utc = now_utc - timedelta(days=args.window_days)
    rows = []

    for algo_name, algo_version in sorted(current_pairs):
        payload = run_hv_exp(
            "algorithm",
            "parameter",
            "list",
            "--algorithm-name",
            algo_name,
            "--algorithm-version",
            algo_version,
        )
        for p in payload.get("algorithm_parameters", []):
            ts = p.get("created_at")
            if not ts:
                continue
            created_utc = datetime.fromisoformat(ts.replace("Z", "+00:00")).astimezone(timezone.utc)
            if created_utc < start_utc:
                continue
            created_local = created_utc.astimezone(local_tz)
            rows.append(
                {
                    "algorithm_id": f"{algo_name}:{algo_version}",
                    "created_at_local": created_local,
                    "run_date_local": created_local.date(),
                    "minutes_since_midnight": created_local.hour * 60 + created_local.minute + created_local.second / 60.0,
                }
            )

    if not rows:
        raise SystemExit("No parameter events found in selected window.")

    df = pd.DataFrame(rows)
    df["lateness_minutes"] = df["minutes_since_midnight"] - sla_minutes
    df["sla_violated"] = df["lateness_minutes"] > 0

    if args.exclude_first_active_day:
        df = df[
            df.apply(
                lambda r: r["run_date_local"] != first_active_day.get(r["algorithm_id"], datetime.min.date()),
                axis=1,
            )
        ].copy()

    # One daily point per algorithm: earliest update of the day.
    daily = (
        df.sort_values("created_at_local")
        .groupby(["algorithm_id", "run_date_local"], as_index=False)
        .first()
    )

    # 3) Summary table ordered by violation ratio.
    summary = (
        daily.groupby("algorithm_id", as_index=False)
        .agg(
            days_observed=("run_date_local", "nunique"),
            violated_days=("sla_violated", "sum"),
            median_late_min=("lateness_minutes", lambda s: s[s > 0].median() if (s > 0).any() else 0.0),
            worst_late_min=("lateness_minutes", "max"),
        )
    )
    summary["violation_ratio"] = summary["violated_days"] / summary["days_observed"]
    summary = summary.sort_values(["violation_ratio", "violated_days", "algorithm_id"], ascending=[False, False, True])
    summary.to_csv(out_dir / "sla_summary_by_algorithm.csv", index=False)
    daily.to_csv(out_dir / "sla_daily_points.csv", index=False)

    # 4) Plot line chart + bottom summary table.
    fig = plt.figure(figsize=(18, 10))
    gs = fig.add_gridspec(2, 1, height_ratios=[3.1, 1.4], hspace=0.16)
    ax = fig.add_subplot(gs[0, 0])
    ax_tbl = fig.add_subplot(gs[1, 0])
    ax_tbl.axis("off")

    algo_ids = list(summary["algorithm_id"])
    palette = plt.cm.tab10(np.linspace(0, 1, max(3, len(algo_ids))))
    color_map = {aid: palette[i % len(palette)] for i, aid in enumerate(algo_ids)}

    for aid in algo_ids:
        d = daily[daily["algorithm_id"] == aid].sort_values("run_date_local")
        x = pd.to_datetime(d["run_date_local"])
        y = d["minutes_since_midnight"]
        ax.plot(x, y, color=color_map[aid], linewidth=2.0, alpha=0.9, label=aid)
        late = d[d["sla_violated"] == True]
        on = d[d["sla_violated"] == False]
        if not on.empty:
            ax.scatter(pd.to_datetime(on["run_date_local"]), on["minutes_since_midnight"], color=color_map[aid], s=26, alpha=0.8)
        if not late.empty:
            ax.scatter(pd.to_datetime(late["run_date_local"]), late["minutes_since_midnight"], color=color_map[aid], s=56, marker="D", edgecolors="black", linewidths=0.35, alpha=0.95)

    ax.axhline(sla_minutes, color="crimson", linewidth=2, linestyle="--", label=f"SLA {args.sla_time} {args.timezone}")
    ax.axhspan(sla_minutes, 24 * 60, color="crimson", alpha=0.07)
    ax.set_title("Current in-use algorithms: earliest daily parameter update vs SLA")
    ax.set_ylabel(f"Earliest daily update ({args.timezone}, minutes since midnight)")
    ax.grid(alpha=0.22)
    ax.xaxis.set_major_locator(mdates.DayLocator(interval=1))
    ax.xaxis.set_major_formatter(mdates.DateFormatter("%m-%d"))
    ax.legend(loc="center left", bbox_to_anchor=(1.01, 0.5), fontsize=9, frameon=False)

    # Bottom summary table (sorted by violation ratio).
    view = summary.copy()
    view["viol_ratio"] = (view["violation_ratio"] * 100).round(1).astype(str) + "%"
    view["median_late_min"] = view["median_late_min"].fillna(0).round(1)
    view["worst_late_min"] = view["worst_late_min"].round(1)
    view = view[["algorithm_id", "days_observed", "violated_days", "viol_ratio", "median_late_min", "worst_late_min"]]
    view.columns = ["algorithm_id", "days", "viol_days", "viol_ratio", "median_late_min", "worst_late_min"]
    table = ax_tbl.table(
        cellText=view.values.tolist(),
        colLabels=list(view.columns),
        cellLoc="center",
        colLoc="center",
        loc="center",
        colWidths=[0.44, 0.08, 0.1, 0.1, 0.14, 0.14],
    )
    table.auto_set_font_size(False)
    table.set_fontsize(9)
    table.scale(1, 1.25)
    ax_tbl.set_title("Per-algorithm SLA summary (ordered by violation ratio)", fontsize=11, pad=8)

    fig.savefig(out_dir / "sla_line_chart_with_table.png", dpi=180, bbox_inches="tight")
    plt.close(fig)


if __name__ == "__main__":
    main()
```

## Notes

- The script is read-only with respect to experiment-management data.
- The default filtering excludes slot names beginning with `shadow-`. Use `--include-shadow-slots` to include them.
- Keep docs/examples sanitized:
  - Use synthetic IDs and placeholder values only
  - Avoid internal domains, bucket names, and internal dataset names
