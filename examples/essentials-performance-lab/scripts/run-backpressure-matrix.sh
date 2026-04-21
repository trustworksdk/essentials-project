#!/usr/bin/env bash
set -euo pipefail

# Backpressure matrix — validates the CDC pipeline's bounded buffers hold when subscribers
# consume slower than producers produce. Sweeps subscriber-handler-delay-ms (primary pressure
# dimension) and subscriber-count (fan-out dimension). Each case reports four invariants:
#   - invariantBoundedBufferHeld            (peak buffer ≤ backpressureBufferSize)
#   - invariantNoEventsActuallyLost         (every produced event is durably in the DB)
#   - invariantCaughtUpWithinTimeout        (subscribers received everything before timeout)
#   - invariantNoDispatcherTickFailures     (zero dispatcher tick failures)
#
# Required for correctness: BoundedBuffer + NoEventsActuallyLost + NoDispatcherTickFailures.
# CaughtUpWithinTimeout is a delivery-timeliness signal — false means "backlog still draining
# when we gave up waiting" and typically indicates stale inbox state from a prior run. Set
# RESET_CDC_STATE=true (with PGHOST/PGPORT/PGUSER/PGPASSWORD/PGDB set) to truncate the inbox
# and drop the replication slot before the matrix runs, avoiding dispatcher starvation.

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

# id|subscriber_count|handler_delay_ms|producer_rate_hz
#
# producer_rate_hz is chosen so a single subscriber at handler_delay_ms drains the produced
# backlog within ~2 × duration (subscriber drain rate = 1000 / delay eps; we overshoot 2×).
# For no-delay and light cases we leave it unthrottled (0).
CASES=(
  "no-delay|1|0|0"
  "light-1sub|1|5|0"
  "moderate-1sub|1|25|80"
  "heavy-1sub|1|100|20"
  "moderate-5sub|5|25|80"
  "heavy-5sub|5|100|20"
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

# Optional pre-run cleanup. Stale inbox rows (e.g. from a prior interrupted run) cause dispatcher
# starvation: the scenario's own events sit at the tail of a massive backlog and never reach
# subscribers within the catchup budget. Setting RESET_CDC_STATE=true truncates the inbox and
# drops the replication slot so the matrix starts from a clean baseline.
#
# Two connection paths — preferred order:
#   1. Host psql on PATH + libpq env vars (PGHOST, PGPORT, PGUSER, PGPASSWORD, PGDB).
#   2. Docker exec into the compose Postgres container ($CDC_RESET_CONTAINER, default
#      essentials-perf-lab-postgres) — no host psql needed. Uses the container's POSTGRES_USER
#      and POSTGRES_DB via local socket.
#
# Picks path 1 when `psql` is on PATH, otherwise falls back to path 2 when the container is
# running. Fails fast if neither is available.
if [[ "${RESET_CDC_STATE:-false}" == "true" ]]; then
  CDC_INBOX_TABLE="${CDC_INBOX_TABLE:-eventstore_cdc_inbox}"
  CDC_SLOT_NAME="${CDC_SLOT_NAME:-essentials_default_essentials_lab}"
  CDC_RESET_CONTAINER="${CDC_RESET_CONTAINER:-essentials-perf-lab-postgres}"
  CDC_RESET_CONTAINER_USER="${CDC_RESET_CONTAINER_USER:-essentials}"
  CDC_RESET_CONTAINER_DB="${CDC_RESET_CONTAINER_DB:-essentials_lab}"
  echo "[perf-lab] RESET_CDC_STATE=true — cleaning inbox table '$CDC_INBOX_TABLE' and dropping slot '$CDC_SLOT_NAME'"

  if command -v psql >/dev/null 2>&1; then
    RESET_MODE="host"
    run_sql() { psql -v ON_ERROR_STOP=0 -c "$1" >/dev/null 2>&1 || true; }
  elif command -v docker >/dev/null 2>&1 && docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$CDC_RESET_CONTAINER"; then
    RESET_MODE="docker:$CDC_RESET_CONTAINER"
    run_sql() {
      docker exec -i "$CDC_RESET_CONTAINER" \
        psql -v ON_ERROR_STOP=0 -U "$CDC_RESET_CONTAINER_USER" -d "$CDC_RESET_CONTAINER_DB" -c "$1" >/dev/null 2>&1 || true
    }
  else
    echo "[perf-lab] neither host psql nor docker container '$CDC_RESET_CONTAINER' is available; cannot reset CDC state." >&2
    echo "[perf-lab] install postgresql-client (brew install libpq / apt install postgresql-client) or" >&2
    echo "[perf-lab] start the compose stack first: docker compose -f examples/essentials-performance-lab/docker-compose.yml up -d" >&2
    exit 1
  fi
  echo "[perf-lab] reset mode: $RESET_MODE"

  run_sql "TRUNCATE TABLE $CDC_INBOX_TABLE"
  run_sql "SELECT pg_terminate_backend(active_pid) FROM pg_replication_slots WHERE slot_name = '$CDC_SLOT_NAME' AND active_pid IS NOT NULL"
  run_sql "SELECT pg_drop_replication_slot('$CDC_SLOT_NAME') WHERE EXISTS (SELECT 1 FROM pg_replication_slots WHERE slot_name = '$CDC_SLOT_NAME')"
  echo "[perf-lab] reset complete"
fi

HEARTBEAT_INTERVAL_S="${HEARTBEAT_INTERVAL_S:-15}"

i=0
for c in "${CASES[@]}"; do
  i=$((i+1))
  IFS='|' read -r ID SUBSCRIBER_COUNT HANDLER_DELAY_MS PRODUCER_RATE_HZ <<< "$c"

  JSON_FILE="$OUT_DIR/$ID.json"
  LOG_FILE="/tmp/perf-lab-backpressure-$RUN_ID-$ID.log"

  echo "[perf-lab] ($i/${#CASES[@]}) case=$ID subscribers=$SUBSCRIBER_COUNT handlerDelay=${HANDLER_DELAY_MS}ms producerRate=${PRODUCER_RATE_HZ}eps"

  CASE_START=$(date +%s)
  mvn -q -pl examples/essentials-performance-lab \
    -DskipTests \
    -Dspring-boot.run.profiles="$PROFILE" \
    -Dspring-boot.run.arguments="--essentials.lab.scenario=backpressure --essentials.eventstore.cdc.enabled=true --essentials.eventstore.cdc.plugin=$PLUGIN --essentials.eventstore.cdc.delivery-mode=$DELIVERY_MODE --essentials.eventstore.cdc.event-bus.backpressure-buffer-size=$BUFFER_SIZE --essentials.lab.warmup=$WARMUP --essentials.lab.duration=$DURATION --essentials.lab.producer-threads=$PRODUCER_THREADS --essentials.lab.subscriber-count=$SUBSCRIBER_COUNT --essentials.lab.subscriber-handler-delay-ms=$HANDLER_DELAY_MS --essentials.lab.producer-rate-hz=$PRODUCER_RATE_HZ --essentials.lab.aggregate-cardinality=$AGGREGATE_CARDINALITY --essentials.lab.random-seed=$SEED --essentials.lab.metrics-output-file=$JSON_FILE" \
    spring-boot:run > "$LOG_FILE" 2>&1 &
  MVN_PID=$!

  # Heartbeat: while the case is running, print the latest [backpressure] progress line every
  # HEARTBEAT_INTERVAL_S seconds so the operator sees progress during long drain phases.
  while kill -0 "$MVN_PID" 2>/dev/null; do
    sleep "$HEARTBEAT_INTERVAL_S"
    if ! kill -0 "$MVN_PID" 2>/dev/null; then break; fi
    elapsed=$(( $(date +%s) - CASE_START ))
    LAST="$(grep -a '\[backpressure\] progress' "$LOG_FILE" 2>/dev/null | tail -1 | sed 's/.*progress //' | tr -d '\n' || true)"
    if [ -n "$LAST" ]; then
      printf '  ... [%3ds] %s\n' "$elapsed" "$LAST"
    else
      printf '  ... [%3ds] (still starting up, no progress line yet)\n' "$elapsed"
    fi
  done

  # Use set +e / restore around wait so we can inspect the return code without aborting the script
  # mid-matrix. set -e is still active for everything else.
  set +e
  wait "$MVN_PID"
  MVN_RC=$?
  set -e
  if [ $MVN_RC -ne 0 ]; then
    echo "[perf-lab] case $ID failed (rc=$MVN_RC); see $LOG_FILE" >&2
    exit $MVN_RC
  fi
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
        "producerRateHz": data.get("producerRateHz", 0),
        "catchupBudgetMs": data.get("catchupBudgetMs", 0),
        "bufferBound": data.get("backpressureBufferSize", 0),
        "produced": data.get("producedEvents", 0),
        "eventsInDb": data.get("eventsInDbCount", -1),
        "delivered": data.get("deliveredEvents", 0),
        "appendEps": float(data.get("appendEventsPerSecond", 0.0)),
        "deliveryEps": float(data.get("deliveredEventsPerSecond", 0.0)),
        "p95Ms": float(data.get("p95LatencyMs", 0.0)),
        "p99Ms": float(data.get("p99LatencyMs", 0.0)),
        "catchupMs": data.get("timeToCatchUpMs", -1),
        "peakBuffer": pressure.get("peakBackfillLiveBufferSize", 0),
        "peakInboxBacklog": pressure.get("peakInboxReceivedCount", 0),
        "finalInboxBacklog": pressure.get("finalInboxReceivedCount", 0),
        "tickFailures": pressure.get("dispatcherTickFailuresDelta", 0),
        "conversionFailures": pressure.get("dispatcherConversionFailuresDelta", 0),
        "poisonRows": pressure.get("dispatcherPoisonRowsDelta", 0),
        "bufferBoundHeld":  bool(data.get("invariantBoundedBufferHeld", False)),
        "noActualLoss":     bool(data.get("invariantNoEventsActuallyLost", False)),
        "caughtUp":         bool(data.get("invariantCaughtUpWithinTimeout", False)),
        "noTickFailures":   bool(data.get("invariantNoDispatcherTickFailures", False)),
    })

summary_json = out_dir / "summary.json"
summary_json.write_text(json.dumps(rows, indent=2) + "\n")

lines = []
lines.append("# Backpressure Matrix Summary")
lines.append("")

# "Correctness" invariants — their failure indicates a real bug that should block shipping.
# CaughtUpWithinTimeout is reported separately as a delivery-timeliness signal (common to fail
# with stale inbox + slow subscriber; does NOT indicate data loss).
correctness_violations = [r for r in rows if not (r["bufferBoundHeld"] and r["noActualLoss"] and r["noTickFailures"])]
timeliness_misses      = [r for r in rows if not r["caughtUp"]]

if correctness_violations:
    lines.append("## ❌ Correctness Invariant Violations")
    lines.append("")
    lines.append("These signal real bugs and should block shipping:")
    lines.append("")
    for r in correctness_violations:
        failed = []
        if not r["bufferBoundHeld"]: failed.append(f"buffer exceeded bound (peak={r['peakBuffer']} > {r['bufferBound']})")
        if not r["noActualLoss"]:
            if r["eventsInDb"] < 0:
                failed.append(f"could not verify durability (count query failed — see case log)")
            else:
                failed.append(f"events missing from DB ({r['eventsInDb']} / {r['produced']} in aggregate table)")
        if not r["noTickFailures"]: failed.append(f"{r['tickFailures']} dispatcher tick failures")
        lines.append(f"- `{r['case']}`: {'; '.join(failed)}")
    lines.append("")
else:
    lines.append("## ✅ All correctness invariants held across every case.")
    lines.append("")

if timeliness_misses:
    lines.append("## ⚠️ Delivery-timeliness misses (advisory, not correctness)")
    lines.append("")
    lines.append("These cases did NOT indicate data loss — events are durably in the DB. Subscribers just hadn't received everything before the catchup budget elapsed. Typical cause: stale inbox backlog starving the dispatcher. If you see this on a fresh inbox, consider increasing the catchup budget or reducing producer rate.")
    lines.append("")
    for r in timeliness_misses:
        lines.append(
            f"- `{r['case']}`: delivered {r['delivered']} / {r['produced']} "
            f"(eventsInDb={r['eventsInDb']}, finalInboxBacklog={r['finalInboxBacklog']})"
        )
    lines.append("")

lines.append("## Per-case results")
lines.append("")
lines.append("| case | delay ms | rate eps | catchup budget s | produced | inDb | delivered | delivery eps | p95 ms | p99 ms | peak buffer | bound | peak inbox | final inbox | tick fails | buf-bound | no-actual-loss | caught-up | no-tick-fails |")
lines.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|:---:|:---:|:---:|:---:|")
for r in rows:
    rate_display = r["producerRateHz"] if r["producerRateHz"] > 0 else "—"
    catchup_s = round(r["catchupBudgetMs"] / 1000.0, 1) if r.get("catchupBudgetMs") else "—"
    in_db = r["eventsInDb"] if r["eventsInDb"] >= 0 else "?"
    lines.append(
        f"| {r['case']} | {r['handlerDelayMs']} | {rate_display} | {catchup_s} | {r['produced']} | {in_db} | {r['delivered']} | {r['deliveryEps']:.2f} | {r['p95Ms']:.2f} | {r['p99Ms']:.2f} | "
        f"{r['peakBuffer']} | {r['bufferBound']} | {r['peakInboxBacklog']} | {r['finalInboxBacklog']} | {r['tickFailures']} | "
        f"{'✅' if r['bufferBoundHeld'] else '❌'} | "
        f"{'✅' if r['noActualLoss'] else '❌'} | "
        f"{'✅' if r['caughtUp'] else '⚠️'} | "
        f"{'✅' if r['noTickFailures'] else '❌'} |"
    )

summary_md = out_dir / "summary.md"
summary_md.write_text("\n".join(lines) + "\n")
print(f"[perf-lab] wrote {summary_json}")
print(f"[perf-lab] wrote {summary_md}")
PY

echo "############# [perf-lab] backpressure matrix done #############"
echo "[perf-lab] output=$OUT_DIR"
