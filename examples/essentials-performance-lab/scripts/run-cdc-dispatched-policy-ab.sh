#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
LAB_DIR="$ROOT_DIR/examples/essentials-performance-lab"

PROFILE="${PROFILE:-compose}"
WARMUP="${WARMUP:-PT20S}"
DURATION="${DURATION:-PT120S}"
PRODUCER_THREADS="${PRODUCER_THREADS:-4}"
SUBSCRIBER_COUNT="${SUBSCRIBER_COUNT:-2}"
AGGREGATE_CARDINALITY="${AGGREGATE_CARDINALITY:-5000}"
SEED="${SEED:-42}"
WAL_PARSER_MODE="${WAL_PARSER_MODE:-BYTES}"
REPEATS="${REPEATS:-3}"

RUN_ID="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="$LAB_DIR/target/cdc-dispatched-policy-ab/$RUN_ID"
mkdir -p "$OUT_DIR"

echo "############# [perf-lab] CDC dispatched policy A/B start #############"
echo "[perf-lab] run_id=$RUN_ID"
echo "[perf-lab] profile=$PROFILE warmup=$WARMUP duration=$DURATION repeats=$REPEATS"
echo "[perf-lab] producer_threads=$PRODUCER_THREADS subscribers=$SUBSCRIBER_COUNT card=$AGGREGATE_CARDINALITY seed=$SEED wal_parser_mode=$WAL_PARSER_MODE"
echo "[perf-lab] output_dir=$OUT_DIR"

run_case() {
  local policy="$1"
  local iteration="$2"
  local run_seed="$3"
  local json_file="$OUT_DIR/${policy}-run${iteration}.json"
  local log_file="/tmp/perf-ab-${policy}-run${iteration}.log"

  echo "[perf-lab] case=$policy run=$iteration seed=$run_seed"

  mvn -q -pl examples/essentials-performance-lab \
    -DskipTests \
    -Dspring-boot.run.profiles="$PROFILE" \
    -Dspring-boot.run.arguments="--essentials.lab.scenario=baseline-polling-vs-cdc-compare --essentials.lab.warmup=$WARMUP --essentials.lab.duration=$DURATION --essentials.lab.producer-threads=$PRODUCER_THREADS --essentials.lab.subscriber-count=$SUBSCRIBER_COUNT --essentials.lab.aggregate-cardinality=$AGGREGATE_CARDINALITY --essentials.lab.random-seed=$run_seed --essentials.eventstore.cdc.wal-parser-mode=$WAL_PARSER_MODE --essentials.eventstore.cdc.cdc-dispatcher.dispatched-row-policy=$policy --essentials.lab.metrics-output-file=$json_file" \
    spring-boot:run > "$log_file" 2>&1
}

for ((i=1; i<=REPEATS; i++)); do
  run_seed=$((SEED + i * 1000))
  run_case "mark-dispatched" "$i" "$run_seed"
  run_case "delete" "$i" "$run_seed"
done

python3 - <<'PY'
import json
import math
import pathlib
from statistics import median

base = pathlib.Path("examples/essentials-performance-lab/target/cdc-dispatched-policy-ab")
run_dirs = sorted([p for p in base.iterdir() if p.is_dir()])
if not run_dirs:
    raise SystemExit("no run directory found")
out_dir = run_dirs[-1]

def num(d, *keys):
    x = d
    for k in keys:
        if not isinstance(x, dict):
            return float("nan")
        x = x.get(k)
    try:
        return float(x)
    except Exception:
        return float("nan")

def valid_compare(d):
    inbox_mode = str(d.get("cdcInbox", {}).get("mode", ""))
    direct_mode = str(d.get("cdcDirect", {}).get("mode", ""))
    inbox_state = str((d.get("cdcInbox", {}).get("cdc") or {}).get("state", ""))
    direct_state = str((d.get("cdcDirect", {}).get("cdc") or {}).get("state", ""))
    return inbox_mode == "cdc-active" and direct_mode == "cdc-active" and inbox_state in ("", "ACTIVE") and direct_state in ("", "ACTIVE")

rows = []
for p in sorted(out_dir.glob("*.json")):
    d = json.loads(p.read_text())
    name = p.stem
    policy = "delete" if name.startswith("delete-") else "mark-dispatched"
    run = name.split("run")[-1]
    rows.append({
        "policy": policy,
        "run": int(run),
        "valid": valid_compare(d),
        "cdc_inbox_delivery_eps": num(d, "cdcInbox", "deliveredEventsPerSecond"),
        "cdc_inbox_append_eps": num(d, "cdcInbox", "appendEventsPerSecond"),
        "cdc_inbox_p95_ms": num(d, "cdcInbox", "p95LatencyMs"),
        "cdc_inbox_completion_pct": num(d, "cdcInbox", "deliveryCompletionPct"),
        "cdc_inbox_lag_end": num(d, "cdcInbox", "deliveryLagEventsEnd"),
        "cdc_inbox_catchup_ms": num(d, "cdcInbox", "timeToCatchUpMs"),
    })

summary = {}
for policy in ("mark-dispatched", "delete"):
    samples = [r for r in rows if r["policy"] == policy and r["valid"]]
    summary[policy] = {
        "runs_total": len([r for r in rows if r["policy"] == policy]),
        "runs_valid": len(samples),
        "median_cdc_inbox_delivery_eps": median([r["cdc_inbox_delivery_eps"] for r in samples]) if samples else None,
        "median_cdc_inbox_append_eps": median([r["cdc_inbox_append_eps"] for r in samples]) if samples else None,
        "median_cdc_inbox_p95_ms": median([r["cdc_inbox_p95_ms"] for r in samples]) if samples else None,
        "median_cdc_inbox_completion_pct": median([r["cdc_inbox_completion_pct"] for r in samples]) if samples else None,
        "median_cdc_inbox_lag_end": median([r["cdc_inbox_lag_end"] for r in samples]) if samples else None,
        "median_cdc_inbox_catchup_ms": median([r["cdc_inbox_catchup_ms"] for r in samples]) if samples else None,
    }

summary_json = out_dir / "summary.json"
summary_json.write_text(json.dumps({"runs": rows, "summary": summary}, indent=2) + "\n")

def f(v, digits=2):
    if v is None:
        return "n/a"
    return f"{float(v):.{digits}f}"

lines = []
lines.append("| policy | runs(valid/total) | median cdcInbox delivery eps | median cdcInbox append eps | median cdcInbox p95 ms | median cdcInbox completion % | median cdcInbox lag end | median cdcInbox catchup ms |")
lines.append("|---|---:|---:|---:|---:|---:|---:|---:|")
for policy in ("mark-dispatched", "delete"):
    s = summary[policy]
    lines.append(
        f"| {policy} | {s['runs_valid']}/{s['runs_total']} | "
        f"{f(s['median_cdc_inbox_delivery_eps'])} | "
        f"{f(s['median_cdc_inbox_append_eps'])} | "
        f"{f(s['median_cdc_inbox_p95_ms'])} | "
        f"{f(s['median_cdc_inbox_completion_pct'])} | "
        f"{f(s['median_cdc_inbox_lag_end'], 0)} | "
        f"{f(s['median_cdc_inbox_catchup_ms'], 0)} |"
    )

summary_md = out_dir / "summary.md"
summary_md.write_text("\n".join(lines) + "\n")

print("[perf-lab] wrote", summary_json)
print("[perf-lab] wrote", summary_md)
PY

echo "############# [perf-lab] CDC dispatched policy A/B done #############"
echo "[perf-lab] output=$OUT_DIR"
