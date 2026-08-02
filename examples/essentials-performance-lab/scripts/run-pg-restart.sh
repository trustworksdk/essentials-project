#!/usr/bin/env bash
# Drives the slot-lag-bounded scenario against the compose Postgres while we
# `docker restart` the PG container at a configurable point during the run.
# Validates that the tailer's reconnect path survives a real PG restart and the
# scenario still produces a sensible JSON output (slot recreated, events drained,
# verdict reflects whatever invariants survived the restart).
#
# Output: target/pg-restart/<run-id>/scenario.json + restart-log.txt
#
# Tunable via env: DURATION (default PT90S), RESTART_AT_S (default 30), RATE_HZ,
# AGGREGATE_CARDINALITY, SEED, PRODUCER_THREADS, CONTAINER_NAME.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
LAB_DIR="$ROOT_DIR/examples/essentials-performance-lab"
OUT_ROOT="$LAB_DIR/target/pg-restart"
RUN_ID="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="$OUT_ROOT/$RUN_ID"
mkdir -p "$OUT_DIR"

PROFILE="${PROFILE:-compose}"
DURATION="${DURATION:-PT90S}"
RESTART_AT_S="${RESTART_AT_S:-30}"           # seconds after scenario start to trigger restart
RATE_HZ="${RATE_HZ:-200}"
AGGREGATE_CARDINALITY="${AGGREGATE_CARDINALITY:-1000}"
SEED="${SEED:-42}"
PRODUCER_THREADS="${PRODUCER_THREADS:-2}"
CONTAINER_NAME="${CONTAINER_NAME:-essentials-perf-lab-postgres}"

JSON_FILE="$OUT_DIR/scenario.json"
RESTART_LOG="$OUT_DIR/restart-log.txt"

echo "############# [perf-lab] pg-restart start #############"
echo "[perf-lab] run_id=$RUN_ID profile=$PROFILE duration=$DURATION restart_at=${RESTART_AT_S}s"
echo "[perf-lab] rate_hz=$RATE_HZ threads=$PRODUCER_THREADS card=$AGGREGATE_CARDINALITY"
echo "[perf-lab] container=$CONTAINER_NAME output=$OUT_DIR"

# Verify container is up before doing anything destructive.
if ! docker inspect -f '{{.State.Running}}' "$CONTAINER_NAME" 2>/dev/null | grep -q true; then
  echo "[perf-lab] ERROR: container '$CONTAINER_NAME' isn't running. Bring it up first:"
  echo "  docker compose -f $LAB_DIR/docker-compose.yml up -d --build"
  exit 1
fi

# Schedule the restart. Run it as a background job that fires after RESTART_AT_S; it logs
# its actions to $RESTART_LOG so the operator can correlate with the scenario timeline.
(
  sleep "$RESTART_AT_S"
  echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) starting docker restart of $CONTAINER_NAME" >> "$RESTART_LOG"
  docker restart "$CONTAINER_NAME" >> "$RESTART_LOG" 2>&1
  echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) docker restart returned $?" >> "$RESTART_LOG"
  # Wait for PG to accept connections again so the operator gets a clean post-restart timestamp.
  for i in $(seq 1 60); do
    if docker exec "$CONTAINER_NAME" pg_isready -U essentials -d essentials_lab >/dev/null 2>&1; then
      echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) pg_isready after $i polls" >> "$RESTART_LOG"
      break
    fi
    sleep 1
  done
) &
RESTART_PID=$!

# Run the scenario in foreground. It will: (1) seed events; (2) sample slot state every
# few seconds; (3) drive a steady producer; (4) survive whatever the docker restart does
# to its connections; (5) drain; (6) emit JSON. The tailer reconnect path is what we're
# validating — slot-lag-bounded already cross-checks the framework's gauges against PG.
mvn -q -pl examples/essentials-performance-lab \
  -DskipTests \
  -Dspring-boot.run.profiles="$PROFILE" \
  -Dspring-boot.run.arguments="--essentials.lab.scenario=slot-lag-bounded \
--essentials.eventstore.cdc.enabled=true \
--essentials.eventstore.cdc.slot.metrics-interval=PT2S \
--essentials.lab.duration=$DURATION \
--essentials.lab.producer-threads=$PRODUCER_THREADS \
--essentials.lab.producer-rate-hz=$RATE_HZ \
--essentials.lab.aggregate-cardinality=$AGGREGATE_CARDINALITY \
--essentials.lab.random-seed=$SEED \
--essentials.lab.slot-lag-sample-interval=PT2S \
--essentials.lab.metrics-output-file=$JSON_FILE" \
  spring-boot:run 2>&1 | tee "$OUT_DIR/scenario.log" | grep -E "perf-lab\] slot=|CDC heartbeat|connect attempt|Recover" || true

wait "$RESTART_PID" 2>/dev/null || true

echo
echo "[perf-lab] restart-log:"
cat "$RESTART_LOG" || true
echo
if [[ -f "$JSON_FILE" ]]; then
  python3 - <<PY
import json, pathlib
p = pathlib.Path("$JSON_FILE")
d = json.loads(p.read_text())
print("[perf-lab] verdict           ", d.get("verdict"))
print("[perf-lab] producedEvents    ", d.get("producedEvents"))
print("[perf-lab] deliveredEvents   ", d.get("deliveredEvents"))
print("[perf-lab] lagBytesMax       ", d.get("lagBytesMax"))
print("[perf-lab] lagBytesEnd       ", d.get("lagBytesEnd"))
print("[perf-lab] walStatusEnd      ", d.get("walStatusEnd"))
PY
fi

echo "############# [perf-lab] pg-restart done #############"
echo "[perf-lab] output=$OUT_DIR"
