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
AUTO_CLEANUP_INACTIVE_SLOTS="${AUTO_CLEANUP_INACTIVE_SLOTS:-true}"
SLOT_PREFIX="${SLOT_PREFIX:-lab_}"
SLOT_CLEANUP_CONTAINER="${SLOT_CLEANUP_CONTAINER:-essentials-perf-lab-postgres}"
PG_DB="${PG_DB:-essentials_lab}"
PG_USER="${PG_USER:-essentials}"

RUN_ID="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="$LAB_DIR/target/cdc-dispatched-policy-ab/$RUN_ID"
mkdir -p "$OUT_DIR"

echo "############# [perf-lab] CDC dispatched policy A/B start #############"
echo "[perf-lab] run_id=$RUN_ID"
echo "[perf-lab] profile=$PROFILE warmup=$WARMUP duration=$DURATION repeats=$REPEATS"
echo "[perf-lab] producer_threads=$PRODUCER_THREADS subscribers=$SUBSCRIBER_COUNT card=$AGGREGATE_CARDINALITY seed=$SEED wal_parser_mode=$WAL_PARSER_MODE"
echo "[perf-lab] cleanup_inactive_slots=$AUTO_CLEANUP_INACTIVE_SLOTS slot_prefix=$SLOT_PREFIX container=$SLOT_CLEANUP_CONTAINER"
echo "[perf-lab] output_dir=$OUT_DIR"

show_slot_state() {
  docker exec "$SLOT_CLEANUP_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -Atqc \
    "select (select setting from pg_settings where name='max_replication_slots') || ',' || (select count(*) from pg_replication_slots) || ',' || (select count(*) from pg_replication_slots where active);"
}

cleanup_inactive_slots() {
  docker exec "$SLOT_CLEANUP_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 -Atqc \
    "do \$\$ declare r record; begin for r in select slot_name from pg_replication_slots where active = false and slot_name like '${SLOT_PREFIX}%' loop perform pg_drop_replication_slot(r.slot_name); end loop; end \$\$;"
}

if [[ "$AUTO_CLEANUP_INACTIVE_SLOTS" == "true" ]]; then
  if docker ps --format '{{.Names}}' | grep -qx "$SLOT_CLEANUP_CONTAINER"; then
    echo "[perf-lab] cleaning inactive replication slots with prefix '${SLOT_PREFIX}'"
    cleanup_inactive_slots || true
    state="$(show_slot_state || true)"
    if [[ -n "$state" ]]; then
      IFS=',' read -r max_slots total_slots active_slots <<< "$state"
      echo "[perf-lab] slots max=$max_slots total=$total_slots active=$active_slots"
    fi
  else
    echo "[perf-lab] WARNING: container '$SLOT_CLEANUP_CONTAINER' not running; skipping slot cleanup"
  fi
fi

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
  if [[ "$AUTO_CLEANUP_INACTIVE_SLOTS" == "true" ]] && docker ps --format '{{.Names}}' | grep -qx "$SLOT_CLEANUP_CONTAINER"; then
    cleanup_inactive_slots || true
  fi
  run_case "mark-dispatched" "$i" "$run_seed"
  if [[ "$AUTO_CLEANUP_INACTIVE_SLOTS" == "true" ]] && docker ps --format '{{.Names}}' | grep -qx "$SLOT_CLEANUP_CONTAINER"; then
    cleanup_inactive_slots || true
  fi
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

def reason_for(d):
    reasons = []
    inbox = d.get("cdcInbox", {})
    direct = d.get("cdcDirect", {})
    if str(inbox.get("mode", "")) != "cdc-active":
        reasons.append(f"cdcInbox mode={inbox.get('mode')}")
    if str(direct.get("mode", "")) != "cdc-active":
        reasons.append(f"cdcDirect mode={direct.get('mode')}")
    inbox_cdc = inbox.get("cdc") or {}
    direct_cdc = direct.get("cdc") or {}
    if str(inbox_cdc.get("state", "ACTIVE")) != "ACTIVE":
        reasons.append(f"cdcInbox state={inbox_cdc.get('state')} reason={inbox_cdc.get('reason')}")
    if str(direct_cdc.get("state", "ACTIVE")) != "ACTIVE":
        reasons.append(f"cdcDirect state={direct_cdc.get('state')} reason={direct_cdc.get('reason')}")
    return reasons

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
        "invalid_reasons": reason_for(d),
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
print("############# [perf-lab] A/B median summary #############")
for policy in ("mark-dispatched", "delete"):
    s = summary[policy]
    print(
        f"[perf-lab] {policy:<16} valid={s['runs_valid']}/{s['runs_total']} "
        f"delivery_eps={f(s['median_cdc_inbox_delivery_eps'])} "
        f"append_eps={f(s['median_cdc_inbox_append_eps'])} "
        f"p95_ms={f(s['median_cdc_inbox_p95_ms'])} "
        f"completion_pct={f(s['median_cdc_inbox_completion_pct'])} "
        f"lag_end={f(s['median_cdc_inbox_lag_end'], 0)} "
        f"catchup_ms={f(s['median_cdc_inbox_catchup_ms'], 0)}"
    )
if any(summary[p]["runs_valid"] < summary[p]["runs_total"] for p in ("mark-dispatched", "delete")):
    print("[perf-lab] WARNING: some runs were invalid and excluded from medians")
    for r in rows:
        if not r["valid"]:
            reasons = "; ".join(r["invalid_reasons"]) if r["invalid_reasons"] else "unknown"
            print(f"[perf-lab] invalid policy={r['policy']} run={r['run']} reason={reasons}")
print("############# [perf-lab] ################################")
PY

echo "############# [perf-lab] CDC dispatched policy A/B done #############"
echo "[perf-lab] output=$OUT_DIR"
