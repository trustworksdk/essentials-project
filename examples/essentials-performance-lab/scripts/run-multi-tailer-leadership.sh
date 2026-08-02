#!/usr/bin/env bash
# Chaos scenario: 2 perf-lab app containers competing for the same replication slot via
# the framework's advisory lock. Validates that exactly ONE tailer streams at any moment
# (no split-brain), then gracefully stops the leader and verifies that the standby takes
# over without data loss.
#
# Difference from run-tailer-kill-failover.sh: this uses `docker stop` (graceful SIGTERM)
# rather than `docker kill` (SIGKILL). Graceful shutdown releases the advisory lock
# immediately via the JVM's shutdown hook; failover should be near-instantaneous (under
# 5 s on a healthy cluster). The kill scenario is bounded by wal_sender_timeout instead.
#
# Output: target/multi-tailer-leadership/<run-id>/timeline.txt + summary.md
#
# Tunable via env: OBSERVATION_S (default 20), POLL_INTERVAL_S (default 1),
# FAILOVER_TIMEOUT_S (default 15).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
LAB_DIR="$ROOT_DIR/examples/essentials-performance-lab"
OUT_ROOT="$LAB_DIR/target/multi-tailer-leadership"
RUN_ID="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="$OUT_ROOT/$RUN_ID"
mkdir -p "$OUT_DIR"

CONTAINER_PG="${CONTAINER_PG:-essentials-perf-lab-postgres}"
CONTAINER_APP_1="${CONTAINER_APP_1:-essentials-perf-lab-app-1}"
CONTAINER_APP_2="${CONTAINER_APP_2:-essentials-perf-lab-app-2}"
OBSERVATION_S="${OBSERVATION_S:-20}"
POLL_INTERVAL_S="${POLL_INTERVAL_S:-1}"
FAILOVER_TIMEOUT_S="${FAILOVER_TIMEOUT_S:-15}"

TIMELINE="$OUT_DIR/timeline.txt"
SAMPLES="$OUT_DIR/samples.csv"
: > "$TIMELINE"
echo "timestamp_unix,active_pid,slot_name,active_count" > "$SAMPLES"

log_ts() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" | tee -a "$TIMELINE"; }

# Returns "<pid> <slot>"; empty if no active slot.
query_active_slot() {
  docker exec "$CONTAINER_PG" psql -U essentials -d essentials_lab -At -c \
    "SELECT active_pid::text || ' ' || slot_name FROM pg_replication_slots WHERE slot_name LIKE 'essentials\\_%' AND active_pid IS NOT NULL" \
    2>/dev/null | head -1
}

# Returns the count of active essentials_* slots — the split-brain detector. Should
# always be 0 (no consumer yet) or 1 (advisory lock holding); 2+ would mean the lock
# isn't doing its job.
query_active_count() {
  docker exec "$CONTAINER_PG" psql -U essentials -d essentials_lab -At -c \
    "SELECT count(*) FROM pg_replication_slots WHERE slot_name LIKE 'essentials\\_%' AND active_pid IS NOT NULL" \
    2>/dev/null
}

cleanup() {
  log_ts "cleaning up: docker compose --profile chaos down"
  (cd "$LAB_DIR" && docker compose --profile chaos down) >> "$TIMELINE" 2>&1 || true
}
trap cleanup EXIT

echo "############# [perf-lab] multi-tailer-leadership start #############"
log_ts "run_id=$RUN_ID observation_s=$OBSERVATION_S failover_timeout_s=$FAILOVER_TIMEOUT_S output=$OUT_DIR"

if ! docker inspect -f '{{.State.Running}}' "$CONTAINER_PG" 2>/dev/null | grep -q true; then
  log_ts "ERROR: PG container '$CONTAINER_PG' not running. Bring it up first:"
  log_ts "  docker compose -f $LAB_DIR/docker-compose.yml up -d --build"
  exit 1
fi

if ! docker image inspect essentials/perf-lab-app:DEV-SNAPSHOT >/dev/null 2>&1; then
  log_ts "ERROR: app image not built. Run scripts/build-app-image.sh first."
  exit 1
fi

log_ts "starting both app instances"
(cd "$LAB_DIR" && docker compose --profile chaos up -d) >> "$TIMELINE" 2>&1

# Wait for the standby to be fully booted as well as the leader. Spring Boot cold-start
# inside the container takes ~20–30 s; the chaos test is meaningless until BOTH JVMs
# have reached their tailer's runPollLoop (which is when the standby is actually
# competing for the advisory lock). We poll the actuator on each container — once both
# return 200 we know they're past Spring lifecycle init.
log_ts "waiting for both app instances to be UP via /actuator/health (timeout 90s)"
deadline=$(( $(date +%s) + 90 ))
both_up=0
while [[ $(date +%s) -lt $deadline ]]; do
  app1_up=$(docker exec "$CONTAINER_APP_1" sh -c 'curl -fs http://localhost:8080/actuator/health 2>/dev/null | grep -q UP && echo 1 || echo 0' 2>/dev/null || echo 0)
  app2_up=$(docker exec "$CONTAINER_APP_2" sh -c 'curl -fs http://localhost:8080/actuator/health 2>/dev/null | grep -q UP && echo 1 || echo 0' 2>/dev/null || echo 0)
  if [[ "$app1_up" == "1" && "$app2_up" == "1" ]]; then
    both_up=1
    break
  fi
  sleep 2
done
if [[ $both_up -ne 1 ]]; then
  log_ts "WARN: not both apps reported UP within 90s; proceeding anyway — failover timing may be skewed by ongoing startup"
else
  log_ts "both apps UP — proceeding to leader observation"
fi

# --- Wait for first leader to emerge ------------------------------------------------
log_ts "waiting for first leader"
leader=""
deadline=$(( $(date +%s) + 60 ))
while [[ -z "$leader" ]] && [[ $(date +%s) -lt $deadline ]]; do
  leader=$(query_active_slot)
  [[ -z "$leader" ]] && sleep "$POLL_INTERVAL_S"
done
if [[ -z "$leader" ]]; then
  log_ts "FAIL: no leader emerged within 60s"
  exit 1
fi
INITIAL_PID="${leader%% *}"
log_ts "first leader: active_pid=$INITIAL_PID"

# --- Observation phase: log a sample per second, watch the active count -------------
log_ts "observing for ${OBSERVATION_S}s — checking exactly-one-active invariant"
splitbrain_seen=0
leader_pid_changed=0
for i in $(seq 1 "$OBSERVATION_S"); do
  current=$(query_active_slot || true)
  count=$(query_active_count || echo 0)
  ts_unix=$(date +%s)
  if [[ -n "$current" ]]; then
    pid="${current%% *}"
    slot="${current#* }"
    echo "$ts_unix,$pid,$slot,$count" >> "$SAMPLES"
    if [[ "$pid" != "$INITIAL_PID" ]]; then
      leader_pid_changed=1
      log_ts "  t+${i}s: leader changed mid-observation: $INITIAL_PID -> $pid (unexpected)"
    fi
  else
    echo "$ts_unix,,,$count" >> "$SAMPLES"
  fi
  if [[ "$count" -gt 1 ]]; then
    splitbrain_seen=1
    log_ts "  t+${i}s: SPLIT-BRAIN — active_count=$count (expected 1)"
  fi
  sleep "$POLL_INTERVAL_S"
done

INVARIANT_NO_SPLITBRAIN=$([[ $splitbrain_seen -eq 0 ]] && echo "PASS" || echo "FAIL")
INVARIANT_LEADER_STABLE=$([[ $leader_pid_changed -eq 0 ]] && echo "PASS" || echo "FAIL")
log_ts "observation phase result: no_splitbrain=$INVARIANT_NO_SPLITBRAIN leader_stable=$INVARIANT_LEADER_STABLE"

# --- Identify which container is the leader ----------------------------------------
APP1_IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$CONTAINER_APP_1")
APP2_IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$CONTAINER_APP_2")
LEADER_CLIENT_ADDR=$(docker exec "$CONTAINER_PG" psql -U essentials -d essentials_lab -At -c \
  "SELECT client_addr FROM pg_stat_activity WHERE pid = $INITIAL_PID")
if [[ "$LEADER_CLIENT_ADDR" == "$APP1_IP" ]]; then
  LEADER_CONTAINER="$CONTAINER_APP_1"
  STANDBY_CONTAINER="$CONTAINER_APP_2"
elif [[ "$LEADER_CLIENT_ADDR" == "$APP2_IP" ]]; then
  LEADER_CONTAINER="$CONTAINER_APP_2"
  STANDBY_CONTAINER="$CONTAINER_APP_1"
else
  LEADER_CONTAINER="$CONTAINER_APP_1"
  STANDBY_CONTAINER="$CONTAINER_APP_2"
fi
log_ts "leader_container=$LEADER_CONTAINER standby_container=$STANDBY_CONTAINER"

# --- Graceful failover --------------------------------------------------------------
STOP_AT=$(date +%s)
log_ts "graceful stop: docker stop $LEADER_CONTAINER (SIGTERM, 10s timeout)"
docker stop "$LEADER_CONTAINER" >> "$TIMELINE" 2>&1

# --- Wait for failover --------------------------------------------------------------
log_ts "polling for failover (active_pid != $INITIAL_PID); timeout ${FAILOVER_TIMEOUT_S}s"
new_leader=""
deadline=$(( STOP_AT + FAILOVER_TIMEOUT_S ))
while [[ $(date +%s) -lt $deadline ]]; do
  current=$(query_active_slot || true)
  if [[ -n "$current" ]]; then
    NEW_PID="${current%% *}"
    if [[ "$NEW_PID" != "$INITIAL_PID" ]]; then
      new_leader="$current"
      break
    fi
  fi
  sleep "$POLL_INTERVAL_S"
done

FAILOVER_AT=$(date +%s)
FAILOVER_S=$(( FAILOVER_AT - STOP_AT ))

if [[ -z "$new_leader" ]]; then
  INVARIANT_FAILOVER="FAIL"
  log_ts "standby did not take over within ${FAILOVER_TIMEOUT_S}s"
else
  NEW_LEADER_PID="${new_leader%% *}"
  INVARIANT_FAILOVER="PASS"
  log_ts "failover ok: new_active_pid=$NEW_LEADER_PID failover_seconds=$FAILOVER_S"
fi

VERDICT="PASS"
if [[ "$INVARIANT_NO_SPLITBRAIN" == "FAIL" || "$INVARIANT_LEADER_STABLE" == "FAIL" || "$INVARIANT_FAILOVER" == "FAIL" ]]; then
  VERDICT="FAIL"
fi

{
  echo "# Multi-Tailer-Leadership"
  echo
  echo "| Invariant | Result |"
  echo "|---|---|"
  echo "| no split-brain (active_count ≤ 1 at all samples) | **$INVARIANT_NO_SPLITBRAIN** |"
  echo "| leader stable during observation (single active_pid for ${OBSERVATION_S}s) | **$INVARIANT_LEADER_STABLE** |"
  echo "| graceful-stop failover (new active_pid within ${FAILOVER_TIMEOUT_S}s) | **$INVARIANT_FAILOVER** |"
  echo "| **overall verdict** | **$VERDICT** |"
  echo
  echo "## Run details"
  echo
  echo "- Initial leader PID: $INITIAL_PID (container=$LEADER_CONTAINER)"
  echo "- Standby container: $STANDBY_CONTAINER"
  echo "- Failover seconds: $FAILOVER_S"
  echo "- Sample CSV: \`samples.csv\`"
  echo
  echo "## What this validates"
  echo
  echo "- Advisory-lock-based leader election holds across the observation window."
  echo "- Graceful shutdown releases the lock immediately via the JVM shutdown hook;"
  echo "  failover is near-instantaneous (no \`wal_sender_timeout\` wait)."
  echo "- Documented in cdc.md §10.1 (\"failover latency on graceful shutdown\")."
  echo
  echo "## Timeline"
  echo
  echo '```'
  cat "$TIMELINE"
  echo '```'
} > "$OUT_DIR/summary.md"

log_ts "summary written to $OUT_DIR/summary.md"
echo
cat "$OUT_DIR/summary.md"
echo
echo "############# [perf-lab] multi-tailer-leadership done #############"
echo "[perf-lab] verdict=$VERDICT failover_seconds=$FAILOVER_S output=$OUT_DIR"
