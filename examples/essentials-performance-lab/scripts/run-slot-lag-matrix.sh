#!/usr/bin/env bash
# Sweeps the slot-lag-bounded scenario across a curated set of slot-tuning profiles, then
# emits per-case JSON + a Markdown summary highlighting which cases passed all five
# assertions and which slot-tuning profile gave the lowest sustained lag.
#
# Output: target/slot-lag/<run-id>/<case>.json + summary.json + summary.md
#
# Tunable via env: PROFILE, DURATION, PRODUCER_THREADS, PRODUCER_RATE_HZ, SUBSCRIBER_COUNT,
# AGGREGATE_CARDINALITY, SEED, SLOT_LAG_MAX_BYTES, SLOT_LAG_SAMPLE_INTERVAL.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
LAB_DIR="$ROOT_DIR/examples/essentials-performance-lab"
OUT_ROOT="$LAB_DIR/target/slot-lag"
RUN_ID="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="$OUT_ROOT/$RUN_ID"
mkdir -p "$OUT_DIR"

PROFILE="${PROFILE:-compose}"
DURATION="${DURATION:-PT120S}"
PRODUCER_THREADS="${PRODUCER_THREADS:-4}"
PRODUCER_RATE_HZ="${PRODUCER_RATE_HZ:-1000}"   # default 1k events/s; 0 = unthrottled
SUBSCRIBER_COUNT="${SUBSCRIBER_COUNT:-1}"
AGGREGATE_CARDINALITY="${AGGREGATE_CARDINALITY:-5000}"
SEED="${SEED:-42}"
SLOT_LAG_MAX_BYTES="${SLOT_LAG_MAX_BYTES:-104857600}"           # 100 MiB
SLOT_LAG_SAMPLE_INTERVAL="${SLOT_LAG_SAMPLE_INTERVAL:-PT5S}"

# Cases sweep the knobs that most directly affect slot retention behaviour: idle-LSN-push
# cadence (P4), slot-metric sample cadence (P1), dispatcher batch size (controls drain rate
# of the inbox → indirectly the slot-ack rate). All other CDC properties stay at framework
# defaults — the goal here isn't broad CDC tuning (that's run-cdc-tuning-matrix.sh) but
# specifically slot-growth behaviour.
#
# id|idle_push|metrics_interval|dispatcher_batch
CASES=(
  "base|PT30S|PT30S|500"
  "idle-push-tight|PT5S|PT30S|500"
  "idle-push-loose|PT55S|PT30S|500"
  "metrics-tight|PT30S|PT5S|500"
  "dispatch-batch-100|PT30S|PT30S|100"
  "dispatch-batch-2000|PT30S|PT30S|2000"
)

if [[ -n "${CUSTOM_CASES:-}" ]]; then
  IFS=';' read -r -a CASES <<< "$CUSTOM_CASES"
fi

echo "############# [perf-lab] slot-lag matrix start #############"
echo "[perf-lab] run_id=$RUN_ID"
echo "[perf-lab] profile=$PROFILE duration=$DURATION rate_hz=$PRODUCER_RATE_HZ threads=$PRODUCER_THREADS"
echo "[perf-lab] aggregate_cardinality=$AGGREGATE_CARDINALITY seed=$SEED"
echo "[perf-lab] slot_lag_max_bytes=$SLOT_LAG_MAX_BYTES sample_interval=$SLOT_LAG_SAMPLE_INTERVAL"
echo "[perf-lab] cases=${#CASES[@]} output_dir=$OUT_DIR"

i=0
for c in "${CASES[@]}"; do
  i=$((i+1))
  IFS='|' read -r ID IDLE_PUSH METRICS_INTERVAL DISPATCHER_BATCH <<< "$c"

  JSON_FILE="$OUT_DIR/$ID.json"
  LOG_FILE="/tmp/perf-lab-slot-lag-$RUN_ID-$ID.log"

  echo "[perf-lab] ($i/${#CASES[@]}) case=$ID idle_push=$IDLE_PUSH metrics_interval=$METRICS_INTERVAL dispatcher_batch=$DISPATCHER_BATCH"

  mvn -q -pl examples/essentials-performance-lab \
    -DskipTests \
    -Dspring-boot.run.profiles="$PROFILE" \
    -Dspring-boot.run.arguments="--essentials.lab.scenario=slot-lag-bounded \
--essentials.eventstore.cdc.enabled=true \
--essentials.lab.duration=$DURATION \
--essentials.lab.producer-threads=$PRODUCER_THREADS \
--essentials.lab.producer-rate-hz=$PRODUCER_RATE_HZ \
--essentials.lab.subscriber-count=$SUBSCRIBER_COUNT \
--essentials.lab.aggregate-cardinality=$AGGREGATE_CARDINALITY \
--essentials.lab.random-seed=$SEED \
--essentials.lab.slot-lag-max-bytes=$SLOT_LAG_MAX_BYTES \
--essentials.lab.slot-lag-sample-interval=$SLOT_LAG_SAMPLE_INTERVAL \
--essentials.eventstore.cdc.wal-replication-tailer.idle-lsn-push-interval=$IDLE_PUSH \
--essentials.eventstore.cdc.slot.metrics-interval=$METRICS_INTERVAL \
--essentials.eventstore.cdc.cdc-dispatcher.batch-size=$DISPATCHER_BATCH \
--essentials.lab.metrics-output-file=$JSON_FILE" \
    spring-boot:run > "$LOG_FILE" 2>&1
done

RUN_ID="$RUN_ID" OUT_DIR="$OUT_DIR" python3 - <<'PY'
import json
import os
import pathlib

out_dir = pathlib.Path(os.environ["OUT_DIR"])
rows = []
for p in sorted(out_dir.glob("*.json")):
    if p.name == "summary.json":
        continue
    data = json.loads(p.read_text())
    rows.append({
        "case": p.stem,
        "verdict": data.get("verdict", "UNKNOWN"),
        "produced": data.get("producedEvents", 0),
        "delivered": data.get("deliveredEvents", 0),
        "lagBytesMax": data.get("lagBytesMax", 0),
        "lagBytesAvg": data.get("lagBytesAvg", 0),
        "lagBytesEnd": data.get("lagBytesEnd", 0),
        "lagBytesThreshold": data.get("lagBytesThreshold", 0),
        "walStatusEnd": data.get("walStatusEnd", "?"),
        "driftPct": data.get("frameworkVsPgDriftPct", float("nan")),
        "lagBoundedOk": data.get("lagBoundedOk", False),
        "lagDrainedOk": data.get("lagDrainedOk", False),
        "walStatusOk":  data.get("walStatusOk", False),
        "deliveryOk":   data.get("deliveryOk", False),
        "driftOk":      data.get("driftOk", False),
        "durationMs":   data.get("durationMs", 0),
    })

(out_dir / "summary.json").write_text(json.dumps(rows, indent=2) + "\n")

def fmt_bytes(n):
    if n is None: return "-"
    units = ["B", "KiB", "MiB", "GiB"]
    f = float(n)
    for u in units:
        if f < 1024.0: return f"{f:.1f} {u}"
        f /= 1024.0
    return f"{f:.1f} TiB"

def fmt_drift(d):
    try:
        if d != d:  # NaN
            return "n/a"
        return f"{d:+.2f}%"
    except Exception:
        return "n/a"

def fmt_bool(b):
    return "✓" if b else "✗"

lines = []
lines.append("# Slot Lag Matrix Summary")
lines.append("")
if rows:
    passing = [r for r in rows if r["verdict"] == "PASS"]
    if passing:
        # Among passing cases, recommend the one with the lowest sustained avg lag — keeps
        # the slot the leanest while still meeting all five assertions.
        best = sorted(passing, key=lambda r: r["lagBytesAvg"])[0]
        lines.append("## Conclusion")
        lines.append("")
        lines.append(
            f"- Recommended slot-tuning profile: `{best['case']}` "
            f"(verdict=PASS, lagBytesAvg={fmt_bytes(best['lagBytesAvg'])}, "
            f"lagBytesMax={fmt_bytes(best['lagBytesMax'])})"
        )
    else:
        worst = sorted(rows, key=lambda r: r["lagBytesMax"], reverse=True)[0]
        lines.append("## Conclusion")
        lines.append("")
        lines.append(
            f"- ⚠️  No case passed all assertions. Worst lag: `{worst['case']}` "
            f"(lagBytesMax={fmt_bytes(worst['lagBytesMax'])}, walStatusEnd={worst['walStatusEnd']})"
        )
    lines.append("")

lines.append("## Cases")
lines.append("")
lines.append("| case | verdict | produced | delivered | lag max | lag avg | lag end | wal_status end | drift | bounded | drained | wal_ok | delivery | drift_ok |")
lines.append("|---|---|---:|---:|---:|---:|---:|---|---:|:---:|:---:|:---:|:---:|:---:|")
for r in rows:
    lines.append(
        f"| {r['case']} | {r['verdict']} | {r['produced']} | {r['delivered']} | "
        f"{fmt_bytes(r['lagBytesMax'])} | {fmt_bytes(r['lagBytesAvg'])} | {fmt_bytes(r['lagBytesEnd'])} | "
        f"{r['walStatusEnd']} | {fmt_drift(r['driftPct'])} | "
        f"{fmt_bool(r['lagBoundedOk'])} | {fmt_bool(r['lagDrainedOk'])} | "
        f"{fmt_bool(r['walStatusOk'])} | {fmt_bool(r['deliveryOk'])} | {fmt_bool(r['driftOk'])} |"
    )

(out_dir / "summary.md").write_text("\n".join(lines) + "\n")
print(f"[perf-lab] wrote {out_dir/'summary.json'}")
print(f"[perf-lab] wrote {out_dir/'summary.md'}")
PY

echo "############# [perf-lab] slot-lag matrix done #############"
echo "[perf-lab] output=$OUT_DIR"
