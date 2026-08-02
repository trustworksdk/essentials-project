#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
LAB_DIR="$ROOT_DIR/examples/essentials-performance-lab"
OUT_DIR="$LAB_DIR/target/matrix"
mkdir -p "$OUT_DIR"

WARMUP="${WARMUP:-PT5S}"
DURATION="${DURATION:-PT20S}"
PRODUCER_THREADS="${PRODUCER_THREADS:-4}"
SEED="${SEED:-42}"
PROFILE="${PROFILE:-compose}"

# id|subscriber_count|aggregate_cardinality|backfill_batch|dispatcher_batch|dispatcher_poll|tailer_poll
CASES=(
  "s1_card5000|1|5000|1000|200|PT0.025S|PT0.025S"
  "s1_card50|1|50|1000|200|PT0.025S|PT0.025S"
  "s5_card5000|5|5000|1000|200|PT0.025S|PT0.025S"
  "s5_card50|5|50|1000|200|PT0.025S|PT0.025S"
)

printf "[perf-lab] matrix start warmup=%s duration=%s producer_threads=%s seed=%s\n" "$WARMUP" "$DURATION" "$PRODUCER_THREADS" "$SEED"

for c in "${CASES[@]}"; do
  IFS='|' read -r ID SUBS CARD BACKFILL_BATCH DISPATCHER_BATCH DISPATCHER_POLL TAILER_POLL <<< "$c"
  JSON_FILE="$OUT_DIR/$ID.json"
  LOG_FILE="/tmp/perf-lab-$ID.log"

  printf "[perf-lab] case=%s subs=%s card=%s\n" "$ID" "$SUBS" "$CARD"

  mvn -q -pl examples/essentials-performance-lab \
    -DskipTests \
    -Dspring-boot.run.profiles="$PROFILE" \
    -Dspring-boot.run.arguments="--essentials.lab.scenario=baseline-polling-vs-cdc-compare --essentials.eventstore.cdc.enabled=false --essentials.lab.subscriber-count=$SUBS --essentials.lab.aggregate-cardinality=$CARD --essentials.lab.producer-threads=$PRODUCER_THREADS --essentials.lab.random-seed=$SEED --essentials.lab.warmup=$WARMUP --essentials.lab.duration=$DURATION --essentials.eventstore.cdc.cdc-event-store-backfill-batch-size=$BACKFILL_BATCH --essentials.eventstore.cdc.cdc-dispatcher.batch-size=$DISPATCHER_BATCH --essentials.eventstore.cdc.cdc-dispatcher.poll-interval=$DISPATCHER_POLL --essentials.eventstore.cdc.wal2-json-tailer.poll-interval=$TAILER_POLL --essentials.lab.metrics-output-file=$JSON_FILE" \
    spring-boot:run > "$LOG_FILE" 2>&1

done

python3 - <<'PY'
import json, pathlib, re
out_dir = pathlib.Path("examples/essentials-performance-lab/target/matrix")
rows = []
for p in sorted(out_dir.glob("*.json")):
    d = json.loads(p.read_text())
    case = p.stem
    log = pathlib.Path(f"/tmp/perf-lab-{case}.log")
    txt = log.read_text() if log.exists() else ""
    rows.append({
        "case": case,
        "subs": d.get("config", {}).get("subscriberCount"),
        "card": d.get("config", {}).get("aggregateCardinality"),
        "polling_append_eps": d["polling"].get("appendEventsPerSecond", 0),
        "polling_delivery_eps": d["polling"].get("deliveredEventsPerSecond", 0),
        "cdc_inbox_append_eps": d.get("cdcInbox", d.get("cdc", {})).get("appendEventsPerSecond", 0),
        "cdc_inbox_delivery_eps": d.get("cdcInbox", d.get("cdc", {})).get("deliveredEventsPerSecond", 0),
        "cdc_direct_append_eps": d.get("cdcDirect", {}).get("appendEventsPerSecond", 0),
        "cdc_direct_delivery_eps": d.get("cdcDirect", {}).get("deliveredEventsPerSecond", 0),
        "delta_append_eps": d["delta"].get("appendEventsPerSecondDiff", 0),
        "delta_delivery_eps": d["delta"].get("deliveredEventsPerSecondDiff", 0),
        "delta_direct_append_eps": d.get("deltaDirect", {}).get("appendEventsPerSecondDiff", 0),
        "delta_direct_delivery_eps": d.get("deltaDirect", {}).get("deliveredEventsPerSecondDiff", 0),
        "delta_p95_ms": d["delta"].get("p95LatencyMsDiff", 0),
        "delta_direct_p95_ms": d.get("deltaDirect", {}).get("p95LatencyMsDiff", 0),
        "warn_phrase_count": len(re.findall(r"Do you have multiple instances of the same subscriber", txt)),
    })

summary_json = out_dir / "summary.json"
summary_md = out_dir / "summary.md"
summary_json.write_text(json.dumps(rows, indent=2) + "\n")

lines = [
    "| case | subs | card | poll append eps | cdc inbox append eps | cdc direct append eps | Δ inbox append eps | Δ direct append eps | poll delivery eps | cdc inbox delivery eps | cdc direct delivery eps | Δ inbox delivery eps | Δ direct delivery eps | Δ inbox p95 ms | Δ direct p95 ms | warn count |",
    "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
]
for r in rows:
    lines.append(
        f"| {r['case']} | {r['subs']} | {r['card']} | "
        f"{r['polling_append_eps']:.2f} | {r['cdc_inbox_append_eps']:.2f} | {r['cdc_direct_append_eps']:.2f} | {r['delta_append_eps']:.2f} | {r['delta_direct_append_eps']:.2f} | "
        f"{r['polling_delivery_eps']:.2f} | {r['cdc_inbox_delivery_eps']:.2f} | {r['cdc_direct_delivery_eps']:.2f} | {r['delta_delivery_eps']:.2f} | {r['delta_direct_delivery_eps']:.2f} | "
        f"{r['delta_p95_ms']:.2f} | {r['delta_direct_p95_ms']:.2f} | {r['warn_phrase_count']} |"
    )
summary_md.write_text("\n".join(lines) + "\n")

print("[perf-lab] wrote", summary_json)
print("[perf-lab] wrote", summary_md)
PY

echo "[perf-lab] matrix complete"
