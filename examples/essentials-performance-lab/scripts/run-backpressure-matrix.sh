#!/usr/bin/env bash
set -euo pipefail

# Backpressure matrix — validates the CDC pipeline's bounded buffers hold when subscribers
# consume slower than producers produce. Sweeps subscriber-handler-delay-ms (primary pressure
# dimension) and subscriber-count (fan-out dimension). Each case reports three pass/fail
# invariants in its JSON output:
#   - invariantBoundedBufferHeld            (peak buffer ≤ backpressureBufferSize)
#   - invariantNoEventsLost                 (all produced events eventually delivered)
#   - invariantNoDispatcherTickFailures     (zero dispatcher tick failures)

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
LAB_DIR="$ROOT_DIR/examples/essentials-performance-lab"
OUT_ROOT="$LAB_DIR/target/backpressure"
RUN_ID="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="$OUT_ROOT/$RUN_ID"
mkdir -p "$OUT_DIR"

PROFILE="${PROFILE:-compose}"
WARMUP="${WARMUP:-PT5S}"
DURATION="${DURATION:-PT60S}"
PRODUCER_THREADS="${PRODUCER_THREADS:-4}"
AGGREGATE_CARDINALITY="${AGGREGATE_CARDINALITY:-1000}"
SEED="${SEED:-42}"
PLUGIN="${PLUGIN:-pgoutput}"               # pgoutput | wal2json
DELIVERY_MODE="${DELIVERY_MODE:-INBOX}"    # INBOX | DIRECT
BUFFER_SIZE="${BUFFER_SIZE:-8192}"         # eventBus.backpressureBufferSize

# id|subscriber_count|handler_delay_ms
# Covers: no-pressure baseline, moderate, heavy, and fan-out pressure.
CASES=(
  "no-delay|1|0"
  "light-1sub|1|5"
  "moderate-1sub|1|25"
  "heavy-1sub|1|100"
  "moderate-5sub|5|25"
  "heavy-5sub|5|100"
)

if [[ -n "${CUSTOM_CASES:-}" ]]; then
  IFS=';' read -r -a CASES <<< "$CUSTOM_CASES"
fi

echo "############# [perf-lab] backpressure matrix start #############"
echo "[perf-lab] run_id=$RUN_ID"
echo "[perf-lab] profile=$PROFILE warmup=$WARMUP duration=$DURATION"
echo "[perf-lab] producer_threads=$PRODUCER_THREADS card=$AGGREGATE_CARDINALITY seed=$SEED"
echo "[perf-lab] plugin=$PLUGIN delivery_mode=$DELIVERY_MODE buffer_size=$BUFFER_SIZE"
echo "[perf-lab] cases=${#CASES[@]} output_dir=$OUT_DIR"

i=0
for c in "${CASES[@]}"; do
  i=$((i+1))
  IFS='|' read -r ID SUBSCRIBER_COUNT HANDLER_DELAY_MS <<< "$c"

  JSON_FILE="$OUT_DIR/$ID.json"
  LOG_FILE="/tmp/perf-lab-backpressure-$RUN_ID-$ID.log"

  echo "[perf-lab] ($i/${#CASES[@]}) case=$ID subscribers=$SUBSCRIBER_COUNT handlerDelay=${HANDLER_DELAY_MS}ms"

  mvn -q -pl examples/essentials-performance-lab \
    -DskipTests \
    -Dspring-boot.run.profiles="$PROFILE" \
    -Dspring-boot.run.arguments="--essentials.lab.scenario=backpressure --essentials.eventstore.cdc.enabled=true --essentials.eventstore.cdc.plugin=$PLUGIN --essentials.eventstore.cdc.delivery-mode=$DELIVERY_MODE --essentials.eventstore.cdc.event-bus.backpressure-buffer-size=$BUFFER_SIZE --essentials.lab.warmup=$WARMUP --essentials.lab.duration=$DURATION --essentials.lab.producer-threads=$PRODUCER_THREADS --essentials.lab.subscriber-count=$SUBSCRIBER_COUNT --essentials.lab.subscriber-handler-delay-ms=$HANDLER_DELAY_MS --essentials.lab.aggregate-cardinality=$AGGREGATE_CARDINALITY --essentials.lab.random-seed=$SEED --essentials.lab.metrics-output-file=$JSON_FILE" \
    spring-boot:run > "$LOG_FILE" 2>&1

done

RUN_ID="$RUN_ID" OUT_DIR="$OUT_DIR" python3 - <<'PY'
import json
import os
import pathlib

out_dir = pathlib.Path(os.environ["OUT_DIR"])
rows = []
for p in sorted(out_dir.glob("*.json")):
    data = json.loads(p.read_text())
    case = p.stem
    pressure = data.get("pressure", {})
    rows.append({
        "case": case,
        "mode": data.get("mode", "unknown"),
        "handlerDelayMs": data.get("handlerDelayMs", 0),
        "bufferBound": data.get("backpressureBufferSize", 0),
        "produced": data.get("producedEvents", 0),
        "delivered": data.get("deliveredEvents", 0),
        "appendEps": float(data.get("appendEventsPerSecond", 0.0)),
        "deliveryEps": float(data.get("deliveredEventsPerSecond", 0.0)),
        "p95Ms": float(data.get("p95LatencyMs", 0.0)),
        "p99Ms": float(data.get("p99LatencyMs", 0.0)),
        "catchupMs": data.get("timeToCatchUpMs", -1),
        "peakBuffer": pressure.get("peakBackfillLiveBufferSize", 0),
        "peakInboxBacklog": pressure.get("peakInboxReceivedCount", 0),
        "tickFailures": pressure.get("dispatcherTickFailuresDelta", 0),
        "conversionFailures": pressure.get("dispatcherConversionFailuresDelta", 0),
        "poisonRows": pressure.get("dispatcherPoisonRowsDelta", 0),
        "bufferBoundHeld": bool(data.get("invariantBoundedBufferHeld", False)),
        "noEventsLost": bool(data.get("invariantNoEventsLost", False)),
        "noTickFailures": bool(data.get("invariantNoDispatcherTickFailures", False)),
    })

summary_json = out_dir / "summary.json"
summary_json.write_text(json.dumps(rows, indent=2) + "\n")

lines = []
lines.append("# Backpressure Matrix Summary")
lines.append("")
violations = [r for r in rows if not (r["bufferBoundHeld"] and r["noEventsLost"] and r["noTickFailures"])]
if violations:
    lines.append("## ⚠️ Invariant Violations")
    lines.append("")
    for r in violations:
        failed = []
        if not r["bufferBoundHeld"]: failed.append(f"buffer exceeded bound (peak={r['peakBuffer']} > {r['bufferBound']})")
        if not r["noEventsLost"]:    failed.append(f"events lost ({r['delivered']} / {r['produced']} delivered)")
        if not r["noTickFailures"]:  failed.append(f"{r['tickFailures']} dispatcher tick failures")
        lines.append(f"- `{r['case']}`: {'; '.join(failed)}")
    lines.append("")
else:
    lines.append("## ✅ All invariants held across every case.")
    lines.append("")

lines.append("## Per-case results")
lines.append("")
lines.append("| case | delay (ms) | subs | produced | delivered | delivery eps | p95 ms | p99 ms | peak buffer | bound | peak inbox | tick fails | buffer-bound | no-loss | no-tick-fails |")
lines.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|:---:|:---:|:---:|")
for r in rows:
    subs = 1  # we don't capture it in JSON directly, but case id often encodes it
    # prefer explicit metadata if present
    meta_subs = r.get("expectedDeliveries", 0)
    # expectedDeliveries = produced * subscriberCount → derive subscriberCount if possible
    if r["produced"] > 0 and isinstance(meta_subs, (int, float)) and meta_subs > 0:
        derived = int(round(meta_subs / r["produced"]))
        if derived > 0:
            subs = derived
    lines.append(
        f"| {r['case']} | {r['handlerDelayMs']} | {subs} | {r['produced']} | {r['delivered']} | {r['deliveryEps']:.2f} | {r['p95Ms']:.2f} | {r['p99Ms']:.2f} | "
        f"{r['peakBuffer']} | {r['bufferBound']} | {r['peakInboxBacklog']} | {r['tickFailures']} | "
        f"{'✅' if r['bufferBoundHeld'] else '❌'} | {'✅' if r['noEventsLost'] else '❌'} | {'✅' if r['noTickFailures'] else '❌'} |"
    )

summary_md = out_dir / "summary.md"
summary_md.write_text("\n".join(lines) + "\n")
print(f"[perf-lab] wrote {summary_json}")
print(f"[perf-lab] wrote {summary_md}")
PY

echo "############# [perf-lab] backpressure matrix done #############"
echo "[perf-lab] output=$OUT_DIR"
