#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
LAB_DIR="$ROOT_DIR/examples/essentials-performance-lab"
OUT_ROOT="$LAB_DIR/target/cdc-tuning"
RUN_ID="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="$OUT_ROOT/$RUN_ID"
mkdir -p "$OUT_DIR"

PROFILE="${PROFILE:-compose}"
WARMUP="${WARMUP:-PT20S}"
DURATION="${DURATION:-PT120S}"
PRODUCER_THREADS="${PRODUCER_THREADS:-4}"
SUBSCRIBER_COUNT="${SUBSCRIBER_COUNT:-2}"
AGGREGATE_CARDINALITY="${AGGREGATE_CARDINALITY:-5000}"
SEED="${SEED:-42}"

# Curated default set to keep runtime bounded while still exploring trade-offs.
# id|backfill_batch|dispatcher_batch|dispatcher_poll|tailer_poll
CASES=(
  "base|1000|200|PT0.025S|PT0.025S"
  "disp-b500|1000|500|PT0.025S|PT0.025S"
  "disp-b1000|1000|1000|PT0.025S|PT0.025S"
  "disp-p10ms|1000|200|PT0.01S|PT0.025S"
  "disp-p50ms|1000|200|PT0.05S|PT0.025S"
  "backfill-500|500|200|PT0.025S|PT0.025S"
  "backfill-2000|2000|200|PT0.025S|PT0.025S"
  "throughput-bias|2000|1000|PT0.01S|PT0.01S"
  "latency-bias|500|200|PT0.05S|PT0.05S"
)

if [[ -n "${CUSTOM_CASES:-}" ]]; then
  # CUSTOM_CASES format: "id|backfill|dispBatch|dispPoll|tailerPoll;id2|..."
  IFS=';' read -r -a CASES <<< "$CUSTOM_CASES"
fi

echo "############# [perf-lab] CDC tuning matrix start #############"
echo "[perf-lab] run_id=$RUN_ID"
echo "[perf-lab] profile=$PROFILE warmup=$WARMUP duration=$DURATION"
echo "[perf-lab] producer_threads=$PRODUCER_THREADS subscribers=$SUBSCRIBER_COUNT card=$AGGREGATE_CARDINALITY seed=$SEED"
echo "[perf-lab] cases=${#CASES[@]} output_dir=$OUT_DIR"

i=0
for c in "${CASES[@]}"; do
  i=$((i+1))
  IFS='|' read -r ID BACKFILL_BATCH DISPATCHER_BATCH DISPATCHER_POLL TAILER_POLL <<< "$c"

  JSON_FILE="$OUT_DIR/$ID.json"
  LOG_FILE="/tmp/perf-lab-cdc-tuning-$RUN_ID-$ID.log"

  echo "[perf-lab] ($i/${#CASES[@]}) case=$ID backfill=$BACKFILL_BATCH dispBatch=$DISPATCHER_BATCH dispPoll=$DISPATCHER_POLL tailerPoll=$TAILER_POLL"

  mvn -q -pl examples/essentials-performance-lab \
    -DskipTests \
    -Dspring-boot.run.profiles="$PROFILE" \
    -Dspring-boot.run.arguments="--essentials.lab.scenario=baseline-polling-vs-cdc-compare --essentials.eventstore.cdc.enabled=false --essentials.lab.warmup=$WARMUP --essentials.lab.duration=$DURATION --essentials.lab.producer-threads=$PRODUCER_THREADS --essentials.lab.subscriber-count=$SUBSCRIBER_COUNT --essentials.lab.aggregate-cardinality=$AGGREGATE_CARDINALITY --essentials.lab.random-seed=$SEED --essentials.eventstore.cdc.cdc-event-store-backfill-batch-size=$BACKFILL_BATCH --essentials.eventstore.cdc.cdc-dispatcher.batch-size=$DISPATCHER_BATCH --essentials.eventstore.cdc.cdc-dispatcher.poll-interval=$DISPATCHER_POLL --essentials.eventstore.cdc.wal2-json-tailer.poll-interval=$TAILER_POLL --essentials.lab.metrics-output-file=$JSON_FILE" \
    spring-boot:run > "$LOG_FILE" 2>&1

done

RUN_ID="$RUN_ID" OUT_DIR="$OUT_DIR" python3 - <<'PY'
import json
import os
import pathlib
import re

out_dir = pathlib.Path(os.environ["OUT_DIR"])
run_id = os.environ["RUN_ID"]
rows = []
for p in sorted(out_dir.glob("*.json")):
    data = json.loads(p.read_text())
    case = p.stem
    log_path = pathlib.Path(f"/tmp/perf-lab-cdc-tuning-{run_id}-{case}.log")
    txt = log_path.read_text() if log_path.exists() else ""

    conf = data.get("config", {})
    polling = data.get("polling", {})
    cdc_inbox = data.get("cdcInbox", data.get("cdc", {}))
    cdc_direct = data.get("cdcDirect", {})
    delta_inbox = data.get("deltaInbox", data.get("delta", {}))
    delta_direct = data.get("deltaDirect", {})

    rows.append({
        "case": case,
        "subscriberCount": conf.get("subscriberCount"),
        "aggregateCardinality": conf.get("aggregateCardinality"),
        "pollingAppendEps": float(polling.get("appendEventsPerSecond", 0.0)),
        "pollingDeliveryEps": float(polling.get("deliveredEventsPerSecond", 0.0)),
        "pollingP95Ms": float(polling.get("p95LatencyMs", 0.0)),
        "cdcInboxAppendEps": float(cdc_inbox.get("appendEventsPerSecond", 0.0)),
        "cdcInboxDeliveryEps": float(cdc_inbox.get("deliveredEventsPerSecond", 0.0)),
        "cdcInboxP95Ms": float(cdc_inbox.get("p95LatencyMs", 0.0)),
        "cdcDirectAppendEps": float(cdc_direct.get("appendEventsPerSecond", 0.0)),
        "cdcDirectDeliveryEps": float(cdc_direct.get("deliveredEventsPerSecond", 0.0)),
        "cdcDirectP95Ms": float(cdc_direct.get("p95LatencyMs", 0.0)),
        "deltaInboxAppendEps": float(delta_inbox.get("appendEventsPerSecondDiff", 0.0)),
        "deltaInboxDeliveryEps": float(delta_inbox.get("deliveredEventsPerSecondDiff", 0.0)),
        "deltaInboxP95Ms": float(delta_inbox.get("p95LatencyMsDiff", 0.0)),
        "deltaDirectAppendEps": float(delta_direct.get("appendEventsPerSecondDiff", 0.0)),
        "deltaDirectDeliveryEps": float(delta_direct.get("deliveredEventsPerSecondDiff", 0.0)),
        "deltaDirectP95Ms": float(delta_direct.get("p95LatencyMsDiff", 0.0)),
        "cdcInboxMode": cdc_inbox.get("mode", "unknown"),
        "cdcDirectMode": cdc_direct.get("mode", "unknown"),
        "warnCount": len(re.findall(r"Do you have multiple instances of the same subscriber", txt)),
    })

summary_json = out_dir / "summary.json"
summary_json.write_text(json.dumps(rows, indent=2) + "\n")

rows_by_delta_inbox_delivery = sorted(rows, key=lambda r: r["deltaInboxDeliveryEps"], reverse=True)
rows_by_delta_direct_delivery = sorted(rows, key=lambda r: r["deltaDirectDeliveryEps"], reverse=True)
rows_by_cdc_inbox_delivery = sorted(rows, key=lambda r: r["cdcInboxDeliveryEps"], reverse=True)
rows_by_cdc_direct_delivery = sorted(rows, key=lambda r: r["cdcDirectDeliveryEps"], reverse=True)

lines = []
lines.append("# CDC Tuning Summary")
lines.append("")
if rows:
    # Selection policy:
    # 1) Prefer positive delivery delta + non-regressed p95 + clean warnings.
    # 2) Otherwise best delivery delta with clean warnings.
    strict_candidates = [
        r for r in rows
        if r["deltaInboxDeliveryEps"] > 0 and r["deltaInboxP95Ms"] <= 0 and r["warnCount"] == 0
    ]
    if strict_candidates:
        best = sorted(strict_candidates, key=lambda r: r["deltaInboxDeliveryEps"], reverse=True)[0]
        decision_basis = "positive delivery delta, non-regressed p95, and zero warning noise"
    else:
        clean_candidates = [r for r in rows if r["warnCount"] == 0]
        pool = clean_candidates if clean_candidates else rows
        best = sorted(pool, key=lambda r: r["deltaInboxDeliveryEps"], reverse=True)[0]
        decision_basis = "highest delivery delta among available clean runs"

    lines.append("## Conclusion")
    lines.append("")
    lines.append(f"- Recommended inbox profile: `{best['case']}` ({decision_basis})")
    best_direct = sorted(rows, key=lambda r: r["deltaDirectDeliveryEps"], reverse=True)[0]
    lines.append(f"- Recommended direct profile: `{best_direct['case']}` (highest direct delivery delta)")
    lines.append(
        f"- Inbox key outcome: Δdelivery={best['deltaInboxDeliveryEps']:.2f} eps, "
        f"Δappend={best['deltaInboxAppendEps']:.2f} eps, Δp95={best['deltaInboxP95Ms']:.2f} ms, warn={best['warnCount']}"
    )
    lines.append(
        f"- Direct key outcome: Δdelivery={best_direct['deltaDirectDeliveryEps']:.2f} eps, "
        f"Δappend={best_direct['deltaDirectAppendEps']:.2f} eps, Δp95={best_direct['deltaDirectP95Ms']:.2f} ms, warn={best_direct['warnCount']}"
    )
    lines.append("")
    lines.append("### Suggested default properties")
    lines.append("")
    case_to_props = {
        "base": {
            "essentials.eventstore.cdc.cdc-event-store-backfill-batch-size": "1000",
            "essentials.eventstore.cdc.cdc-dispatcher.batch-size": "200",
            "essentials.eventstore.cdc.cdc-dispatcher.poll-interval": "PT0.025S",
            "essentials.eventstore.cdc.wal2-json-tailer.poll-interval": "PT0.025S",
        },
        "disp-b500": {
            "essentials.eventstore.cdc.cdc-event-store-backfill-batch-size": "1000",
            "essentials.eventstore.cdc.cdc-dispatcher.batch-size": "500",
            "essentials.eventstore.cdc.cdc-dispatcher.poll-interval": "PT0.025S",
            "essentials.eventstore.cdc.wal2-json-tailer.poll-interval": "PT0.025S",
        },
        "disp-b1000": {
            "essentials.eventstore.cdc.cdc-event-store-backfill-batch-size": "1000",
            "essentials.eventstore.cdc.cdc-dispatcher.batch-size": "1000",
            "essentials.eventstore.cdc.cdc-dispatcher.poll-interval": "PT0.025S",
            "essentials.eventstore.cdc.wal2-json-tailer.poll-interval": "PT0.025S",
        },
        "disp-p10ms": {
            "essentials.eventstore.cdc.cdc-event-store-backfill-batch-size": "1000",
            "essentials.eventstore.cdc.cdc-dispatcher.batch-size": "200",
            "essentials.eventstore.cdc.cdc-dispatcher.poll-interval": "PT0.01S",
            "essentials.eventstore.cdc.wal2-json-tailer.poll-interval": "PT0.025S",
        },
        "disp-p50ms": {
            "essentials.eventstore.cdc.cdc-event-store-backfill-batch-size": "1000",
            "essentials.eventstore.cdc.cdc-dispatcher.batch-size": "200",
            "essentials.eventstore.cdc.cdc-dispatcher.poll-interval": "PT0.05S",
            "essentials.eventstore.cdc.wal2-json-tailer.poll-interval": "PT0.025S",
        },
        "backfill-500": {
            "essentials.eventstore.cdc.cdc-event-store-backfill-batch-size": "500",
            "essentials.eventstore.cdc.cdc-dispatcher.batch-size": "200",
            "essentials.eventstore.cdc.cdc-dispatcher.poll-interval": "PT0.025S",
            "essentials.eventstore.cdc.wal2-json-tailer.poll-interval": "PT0.025S",
        },
        "backfill-2000": {
            "essentials.eventstore.cdc.cdc-event-store-backfill-batch-size": "2000",
            "essentials.eventstore.cdc.cdc-dispatcher.batch-size": "200",
            "essentials.eventstore.cdc.cdc-dispatcher.poll-interval": "PT0.025S",
            "essentials.eventstore.cdc.wal2-json-tailer.poll-interval": "PT0.025S",
        },
        "throughput-bias": {
            "essentials.eventstore.cdc.cdc-event-store-backfill-batch-size": "2000",
            "essentials.eventstore.cdc.cdc-dispatcher.batch-size": "1000",
            "essentials.eventstore.cdc.cdc-dispatcher.poll-interval": "PT0.01S",
            "essentials.eventstore.cdc.wal2-json-tailer.poll-interval": "PT0.01S",
        },
        "latency-bias": {
            "essentials.eventstore.cdc.cdc-event-store-backfill-batch-size": "500",
            "essentials.eventstore.cdc.cdc-dispatcher.batch-size": "200",
            "essentials.eventstore.cdc.cdc-dispatcher.poll-interval": "PT0.05S",
            "essentials.eventstore.cdc.wal2-json-tailer.poll-interval": "PT0.05S",
        },
    }
    props = case_to_props.get(best["case"], {})
    if props:
        lines.append("```properties")
        for k, v in props.items():
            lines.append(f"{k}={v}")
        lines.append("```")
    lines.append("")

lines.append("")
lines.append("## Ranked by Delta Delivery EPS (CDC Inbox - Polling)")
lines.append("")
lines.append("| case | cdc mode | Δ delivery eps | Δ append eps | Δ p95 ms | cdc delivery eps | poll delivery eps | warn count |")
lines.append("|---|---|---:|---:|---:|---:|---:|---:|")
for r in rows_by_delta_inbox_delivery:
    lines.append(
        f"| {r['case']} | {r['cdcInboxMode']} | {r['deltaInboxDeliveryEps']:.2f} | {r['deltaInboxAppendEps']:.2f} | {r['deltaInboxP95Ms']:.2f} | "
        f"{r['cdcInboxDeliveryEps']:.2f} | {r['pollingDeliveryEps']:.2f} | {r['warnCount']} |"
    )

lines.append("")
lines.append("## Ranked by Delta Delivery EPS (CDC Direct - Polling)")
lines.append("")
lines.append("| case | cdc mode | Δ delivery eps | Δ append eps | Δ p95 ms | cdc delivery eps | poll delivery eps | warn count |")
lines.append("|---|---|---:|---:|---:|---:|---:|---:|")
for r in rows_by_delta_direct_delivery:
    lines.append(
        f"| {r['case']} | {r['cdcDirectMode']} | {r['deltaDirectDeliveryEps']:.2f} | {r['deltaDirectAppendEps']:.2f} | {r['deltaDirectP95Ms']:.2f} | "
        f"{r['cdcDirectDeliveryEps']:.2f} | {r['pollingDeliveryEps']:.2f} | {r['warnCount']} |"
    )

lines.append("")
lines.append("## Ranked by CDC Inbox Delivery EPS")
lines.append("")
lines.append("| case | cdc mode | cdc delivery eps | cdc append eps | cdc p95 ms | Δ delivery eps | warn count |")
lines.append("|---|---|---:|---:|---:|---:|---:|")
for r in rows_by_cdc_inbox_delivery:
    lines.append(
        f"| {r['case']} | {r['cdcInboxMode']} | {r['cdcInboxDeliveryEps']:.2f} | {r['cdcInboxAppendEps']:.2f} | {r['cdcInboxP95Ms']:.2f} | "
        f"{r['deltaInboxDeliveryEps']:.2f} | {r['warnCount']} |"
    )

lines.append("")
lines.append("## Ranked by CDC Direct Delivery EPS")
lines.append("")
lines.append("| case | cdc mode | cdc delivery eps | cdc append eps | cdc p95 ms | Δ delivery eps | warn count |")
lines.append("|---|---|---:|---:|---:|---:|---:|")
for r in rows_by_cdc_direct_delivery:
    lines.append(
        f"| {r['case']} | {r['cdcDirectMode']} | {r['cdcDirectDeliveryEps']:.2f} | {r['cdcDirectAppendEps']:.2f} | {r['cdcDirectP95Ms']:.2f} | "
        f"{r['deltaDirectDeliveryEps']:.2f} | {r['warnCount']} |"
    )

summary_md = out_dir / "summary.md"
summary_md.write_text("\n".join(lines) + "\n")

print(f"[perf-lab] wrote {summary_json}")
print(f"[perf-lab] wrote {summary_md}")
PY

echo "############# [perf-lab] CDC tuning matrix done #############"
echo "[perf-lab] output=$OUT_DIR"
