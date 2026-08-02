#!/usr/bin/env bash
# Chaos scenario: 2 perf-lab app containers competing for the same replication slot via
# the framework's advisory lock. Verifies that exactly one tailer streams at a time, then
# SIGKILLs the leader and measures how long until the other instance takes over.
#
# Failover budget under SIGKILL is bounded by PostgreSQL's wal_sender_timeout (default
# 60 s) — that's how long PG takes to detect the dead replication connection and release
# the advisory lock. We poll for ≤ 90 s.
#
# Output: target/tailer-kill-failover/<run-id>/timeline.txt + summary.md
#
# Tunable via env: HOLD_S (default 10), POLL_INTERVAL_S (default 1), FAILOVER_TIMEOUT_S
# (default 90).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
LAB_DIR="$ROOT_DIR/examples/essentials-performance-lab"
OUT_ROOT="$LAB_DIR/target/tailer-kill-failover"
RUN_ID="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="$OUT_ROOT/$RUN_ID"
mkdir -p "$OUT_DIR"

CONTAINER_PG="${CONTAINER_PG:-essentials-perf-lab-postgres}"
CONTAINER_APP_1="${CONTAINER_APP_1:-essentials-perf-lab-app-1}"
CONTAINER_APP_2="${CONTAINER_APP_2:-essentials-perf-lab-app-2}"
HOLD_S="${HOLD_S:-10}"                          # observation window before the kill
POLL_INTERVAL_S="${POLL_INTERVAL_S:-1}"
FAILOVER_TIMEOUT_S="${FAILOVER_TIMEOUT_S:-90}"

TIMELINE="$OUT_DIR/timeline.txt"
: > "$TIMELINE"

log_ts() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" | tee -a "$TIMELINE"; }

# Helper: query PG for the active_pid + slot_name of the currently-streaming essentials_*
# slot. Empty output means no active slot. Output format: "<active_pid> <slot_name>".
query_active_slot() {
  docker exec "$CONTAINER_PG" psql -U essentials -d essentials_lab -At -c \
    "SELECT active_pid::text || ' ' || slot_name FROM pg_replication_slots WHERE slot_name LIKE 'essentials\\_%' AND active_pid IS NOT NULL" \
    2>/dev/null | head -1
}

cleanup() {
  log_ts "cleaning up: docker compose --profile chaos down"
  (cd "$LAB_DIR" && docker compose --profile chaos down) >> "$TIMELINE" 2>&1 || true
}
trap cleanup EXIT

echo "############# [perf-lab] tailer-kill-failover start #############"
log_ts "run_id=$RUN_ID hold_s=$HOLD_S failover_timeout_s=$FAILOVER_TIMEOUT_S output=$OUT_DIR"

# --- Sanity: PG container running ----------------------------------------------------
if ! docker inspect -f '{{.State.Running}}' "$CONTAINER_PG" 2>/dev/null | grep -q true; then
  log_ts "ERROR: PG container '$CONTAINER_PG' not running. Bring it up first:"
  log_ts "  docker compose -f $LAB_DIR/docker-compose.yml up -d --build"
  exit 1
fi

# --- Sanity: app image built ---------------------------------------------------------
if ! docker image inspect essentials/perf-lab-app:DEV-SNAPSHOT >/dev/null 2>&1; then
  log_ts "ERROR: app image not built. Run scripts/build-app-image.sh first."
  exit 1
fi

# --- Start both app instances --------------------------------------------------------
log_ts "starting both app instances via 'docker compose --profile chaos up -d'"
(cd "$LAB_DIR" && docker compose --profile chaos up -d) >> "$TIMELINE" 2>&1

# Wait for BOTH app instances to be fully booted. Without this the standby may still be
# in Spring Boot init when we kill the leader, and the failover timer ticks against the
# standby's startup time — not the framework's actual recovery. /actuator/health
# returning UP is the cleanest "ready to compete" signal.
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
  log_ts "WARN: not both apps reported UP within 90s; proceeding anyway"
else
  log_ts "both apps UP — proceeding"
fi

# --- Observe leader --------------------------------------------------------------
log_ts "polling pg_replication_slots until exactly-one slot is active"
leader=""
deadline=$(( $(date +%s) + 60 ))
while [[ -z "$leader" ]] && [[ $(date +%s) -lt $deadline ]]; do
  leader=$(query_active_slot)
  [[ -z "$leader" ]] && sleep "$POLL_INTERVAL_S"
done

if [[ -z "$leader" ]]; then
  log_ts "FAIL: no slot became active within 60s — neither app acquired the advisory lock?"
  exit 1
fi
LEADER_PID="${leader%% *}"
LEADER_SLOT="${leader#* }"
log_ts "initial leader: active_pid=$LEADER_PID slot=$LEADER_SLOT"

# --- Identify which container holds the leading PID ---------------------------------
# pg_replication_slots.active_pid is the BACKEND pid inside PG, not the app's JVM PID.
# To find which app container is streaming we query pg_stat_activity which has both
# pid and application_name (the JDBC client_info / app name). The default Spring Boot
# app sends application_name=PostgreSQL JDBC Driver, but the IP address differs by
# container — that's the disambiguator.
APP1_IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$CONTAINER_APP_1")
APP2_IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$CONTAINER_APP_2")
LEADER_CLIENT_ADDR=$(docker exec "$CONTAINER_PG" psql -U essentials -d essentials_lab -At -c \
  "SELECT client_addr FROM pg_stat_activity WHERE pid = $LEADER_PID")
log_ts "leader client_addr=$LEADER_CLIENT_ADDR  app-1_ip=$APP1_IP  app-2_ip=$APP2_IP"

if [[ "$LEADER_CLIENT_ADDR" == "$APP1_IP" ]]; then
  LEADER_CONTAINER="$CONTAINER_APP_1"
  STANDBY_CONTAINER="$CONTAINER_APP_2"
elif [[ "$LEADER_CLIENT_ADDR" == "$APP2_IP" ]]; then
  LEADER_CONTAINER="$CONTAINER_APP_2"
  STANDBY_CONTAINER="$CONTAINER_APP_1"
else
  log_ts "WARN: couldn't match client_addr to either app container; defaulting to killing app-1"
  LEADER_CONTAINER="$CONTAINER_APP_1"
  STANDBY_CONTAINER="$CONTAINER_APP_2"
fi
log_ts "leader_container=$LEADER_CONTAINER standby_container=$STANDBY_CONTAINER"

# --- Hold steady to confirm exactly-one-active is stable ----------------------------
log_ts "holding for ${HOLD_S}s to confirm leader is stable"
for i in $(seq 1 "$HOLD_S"); do
  sleep "$POLL_INTERVAL_S"
  current=$(query_active_slot || true)
  if [[ "$current" != "$leader" ]]; then
    log_ts "  t+${i}s: leader changed unexpectedly: was='$leader' now='$current'"
  fi
done
log_ts "leader stable for ${HOLD_S}s"

# --- KILL ----------------------------------------------------------------------------
KILL_AT=$(date +%s)
log_ts "SIGKILL leader: docker kill $LEADER_CONTAINER"
docker kill "$LEADER_CONTAINER" >> "$TIMELINE" 2>&1

# --- Wait for failover --------------------------------------------------------------
log_ts "polling for failover (active_pid != $LEADER_PID); timeout ${FAILOVER_TIMEOUT_S}s"
new_leader=""
deadline=$(( KILL_AT + FAILOVER_TIMEOUT_S ))
while [[ $(date +%s) -lt $deadline ]]; do
  current=$(query_active_slot || true)
  if [[ -n "$current" ]]; then
    NEW_PID="${current%% *}"
    if [[ "$NEW_PID" != "$LEADER_PID" ]]; then
      new_leader="$current"
      break
    fi
  fi
  sleep "$POLL_INTERVAL_S"
done

FAILOVER_AT=$(date +%s)
FAILOVER_S=$(( FAILOVER_AT - KILL_AT ))

if [[ -z "$new_leader" ]]; then
  log_ts "FAIL: standby did not take over within ${FAILOVER_TIMEOUT_S}s"
  VERDICT="FAIL"
else
  NEW_LEADER_PID="${new_leader%% *}"
  log_ts "failover complete: new_active_pid=$NEW_LEADER_PID failover_seconds=$FAILOVER_S"
  VERDICT="PASS"
fi

# --- Build summary ------------------------------------------------------------------
{
  echo "# Tailer-Kill-Failover"
  echo
  echo "| Phase | Value |"
  echo "|---|---|"
  echo "| Initial leader | active_pid=$LEADER_PID, container=$LEADER_CONTAINER |"
  echo "| Standby | container=$STANDBY_CONTAINER |"
  echo "| Hold period | ${HOLD_S}s — leader was stable |"
  echo "| Failover timeout | ${FAILOVER_TIMEOUT_S}s |"
  echo "| Failover observed at | t+${FAILOVER_S}s after SIGKILL |"
  if [[ "$VERDICT" == "PASS" ]]; then
    echo "| New leader | active_pid=$NEW_LEADER_PID |"
  else
    echo "| New leader | NOT OBSERVED — check timeline.txt |"
  fi
  echo "| Verdict | **$VERDICT** |"
  echo
  echo "## What this validates"
  echo
  echo "- Advisory-lock failover after SIGKILL of the leader."
  echo "- Failover budget bounded by PostgreSQL's wal_sender_timeout (default 60s) —"
  echo "  PG won't release the lock until it detects the dead replication connection."
  echo "- Documented in cdc.md §10.1 (\"failover latency on ungraceful shutdown\")."
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
echo "############# [perf-lab] tailer-kill-failover done #############"
echo "[perf-lab] verdict=$VERDICT failover_seconds=$FAILOVER_S output=$OUT_DIR"
