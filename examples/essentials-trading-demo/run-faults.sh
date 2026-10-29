#!/usr/bin/env bash
#
# Run one fault of phase 6 (docs/design/phase-6-realistic-load.md §10) under the load driver, on a database and
# simulators of its own:
#
#   ./run-faults.sh none                 # the same run without a fault, for comparison
#   ./run-faults.sh F-1                  # damaged, duplicated and reordered webhook copies
#   ./run-faults.sh F-2                  # every webhook answered 503 for 60 s from the open of business day 4
#   ./run-faults.sh F-3                  # the instance SIGKILLed at the open of business day 4, started 10 s later
#   ./run-faults.sh F-3 --instances=2    # two instances (8080, 8081); the second is killed (--fault-instance=1: the first)
#   ./run-faults.sh F-3 --instances=2 --fault-duration=PT60S  # started again 60 s after the kill, past the 30 s
#                                                             # lease TTL, so the survivor takes the dead one's units
#   ./run-faults.sh F-4                  # the exchange restarted at the open of business day 4
#   ./run-faults.sh F-4 --fault-target=gateway   # the payment-gateway instead
#   ./run-faults.sh F-4 --fault-duration=PT30S  # held down 30 s (stop, wait, start) instead of a plain restart
#   ./run-faults.sh F-5                  # seeded reconciliation breaks (simulators/compose.breaks.yml)
#   ./run-faults.sh F-1 --business-days=3 --population=500   # extra arguments go to the driver and win
#
# What it does, in order:
#   1. recreates the exchange-simulator and the payment-gateway with empty volumes of their own (with the break settings
#      for F-5, the short delivery budget for F-2). Only those two containers and their two volumes - never `down -v`,
#      which would also delete the demo's PostgreSQL data, as it shares the compose project. Their `/test/reset` takes
#      minutes after a full run.
#   2. recreates the database `trading_faults` and its CDC slot on the running PostgreSQL; the demo's own database is
#      not touched.
#   3. starts one instance from target/classes with plain `java` - the full JIT; `spring-boot:run` would give C1 only
#      (_demo_harness/CLAUDE.md) - and waits until the operator can log in.
#   4. runs ./run-load.sh with the decided fault profile (Q-10): 2 000 customers, 1 000 active, 100 orders/s with 300/s
#      at each open, 10 business days.
#      For F-3 it kills the instance (`kill -9`) when the driver prints its `fault window open` line and starts it again
#      10 s later, appending to the same log. For F-4 it restarts the simulator when the driver prints its `fault window open` line: `docker restart`, or with
#      --fault-duration=PT<n>S `docker stop`, n seconds, `docker start`. A plain restart is as long as the container's
#      shutdown: the exchange ignores SIGTERM (10 s, Docker's stop timeout), the gateway stops in a fraction of a second.
#   5. stops the instance. The exit code is the driver's: 0 only if every check passed.
#
# Needs PostgreSQL and the simulator images (compose `simulators` profile) and a compiled module
# (`mvn -pl examples/essentials-trading-demo compile`). Logs and the driver's output land in target/faults/.
set -euo pipefail
cd "$(dirname "$0")"

FAULT="${1:?usage: run-faults.sh <none|F-1|F-2|F-3|F-4|F-5> [driver arguments]}"
shift
RUN="${RUN_TOKEN:-$(echo "$FAULT" | tr -d '-' | tr 'A-Z' 'a-z')$(date +%H%M%S)}"
DB=trading_faults
OUT=target/faults
JAVA="${JAVA_HOME:-/usr/lib/jvm/temurin-25-jdk-arm64}/bin/java"
PSQL="docker exec -i essentials-trading-demo-postgresql psql -U essentials -Atq"
COMPOSE=(docker compose -p essentials-trading-demo -f compose.yml)
if [[ $FAULT == F-5 ]]; then
  COMPOSE+=(-f simulators/compose.breaks.yml)
elif [[ $FAULT == F-2 ]]; then
  COMPOSE+=(-f simulators/compose.outage.yml)
fi
COMPOSE+=(--profile simulators)
mkdir -p "$OUT"
log() { echo "$(date +%T) [$RUN] $*"; }
TARGET=exchange
HOLD_DOWN=
INSTANCES=1
KILL=
DRIVER_ARGS=()
for arg in "$@"; do
  if [[ $arg == --fault-target=* ]]; then TARGET=${arg#--fault-target=}; fi
  if [[ $arg =~ ^--fault-duration=PT([0-9]+)S$ ]]; then HOLD_DOWN=${BASH_REMATCH[1]}; fi
  if [[ $arg == --fault-instance=* ]]; then KILL=${arg#--fault-instance=}; fi
  if [[ $arg == --instances=* ]]; then INSTANCES=${arg#--instances=}; continue; fi # the script's, not the driver's
  DRIVER_ARGS+=("$arg")
done
# Design §6 / Q-8: with two, kill the one that is not the simulators' webhook target unless told otherwise
if [[ -z $KILL ]]; then KILL=$INSTANCES; DRIVER_ARGS+=("--fault-instance=$KILL"); fi
BASE_URLS=$(for n in $(seq 1 "$INSTANCES"); do printf 'http://localhost:%d,' $((8079 + n)); done)
BASE_URLS=${BASE_URLS%,}
# Instance n's log and pid file; instance 1 keeps the single-instance names
suffix() { [[ $1 == 1 ]] && echo "" || echo "-$1"; }
CONTAINER=essentials-trading-demo-$([[ $TARGET == gateway ]] && echo payment-gateway || echo exchange)

# Passes the driver's output through; F-4's window line restarts the target simulator, in the background
act_on_fault_window() {
  while IFS= read -r line; do
    echo "$line"
    if [[ $FAULT == F-3 && $line == *"fault window open (F-3"* ]]; then
      (ownership "before the kill"
       log "fault F-3: kill -9 instance $KILL (pid $(cat "$OUT/app-$RUN$(suffix "$KILL").pid"))"; kill -9 "$(cat "$OUT/app-$RUN$(suffix "$KILL").pid")"
       sleep "${HOLD_DOWN:-10}"
       ownership "${HOLD_DOWN:-10} s after the kill"
       log "fault F-3: starting instance $KILL again"; start_instance "$KILL"; log "fault F-3: started (pid $(cat "$OUT/app-$RUN$(suffix "$KILL").pid"))"
       wait_for_instance "$KILL"; sleep 30
       ownership "30 s after it answered again") &
    fi
    if [[ $FAULT == F-4 && $line == *"fault window open (F-4"* ]]; then
      if [[ -z $HOLD_DOWN ]]; then
        (log "fault F-4: restarting $CONTAINER"; docker restart "$CONTAINER" >/dev/null; log "fault F-4: $CONTAINER restarted") &
      else
        (log "fault F-4: stopping $CONTAINER for ${HOLD_DOWN}s"; docker stop "$CONTAINER" >/dev/null; sleep "$HOLD_DOWN"
         docker start "$CONTAINER" >/dev/null; log "fault F-4: $CONTAINER started") &
      fi
    fi
  done
}

log "recreating the simulators"
"${COMPOSE[@]}" stop exchange payment-gateway >/dev/null 2>&1 || true
"${COMPOSE[@]}" rm -f exchange payment-gateway >/dev/null 2>&1 || true
docker volume rm essentials-trading-demo_exchange-data essentials-trading-demo_gateway-data >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --wait exchange payment-gateway

log "recreating database $DB"
$PSQL -d essentials-trading-demo -c "select pg_drop_replication_slot(slot_name) from pg_replication_slots where slot_name = 'essentials_default_$DB'" >/dev/null
$PSQL -d essentials-trading-demo -c "drop database if exists $DB with (force)"
$PSQL -d essentials-trading-demo -c "create database $DB"

classpath_file=target/load-driver.classpath
if [[ ! -f $classpath_file || pom.xml -nt $classpath_file ]]; then
  mvn -q dependency:build-classpath -Dmdep.outputFile="$classpath_file" -Dmdep.includeScope=runtime >/dev/null
fi

# Instance n: port 8079+n, instance id demo-n. F-3 starts one a second time, into the same log, under the same id.
# With two, each is capped at 3 GB of heap: by default a JVM may take 40 % of the machine, and three JVMs (two instances
# and the driver) beside PostgreSQL's cache do not fit that.
start_instance() {
  local n=$1 heap=()
  if [[ $INSTANCES -gt 1 ]]; then heap=(-Xmx3g); fi
  "$JAVA" "${heap[@]}" -Dserver.port=$((8079 + n)) -cp "target/classes:$(cat "$classpath_file")" \
    dk.trustworks.essentials.examples.trading.TradingDemoApplication \
    --spring.profiles.active=compose,simulators --spring.docker.compose.enabled=false \
    --spring.datasource.url="jdbc:postgresql://localhost:5432/$DB" \
    --essentials.shard-owned-queue.instance-id=demo-$n >> "$OUT/app-$RUN$(suffix "$n").log" 2>&1 &
  echo $! > "$OUT/app-$RUN$(suffix "$n").pid"
}

# /login answers before the seeded users exist; an operator call answers only once they do
wait_for_instance() {
  local n=$1
  for _ in $(seq 1 180); do
    if [[ $(curl -s -o /dev/null -w '%{http_code}' -u operator@demo.local:operator-demo-password "localhost:$((8079 + n))/api/admin/load-report?runPrefix=none" || true) == 200 ]]; then
      return 0
    fi
    kill -0 "$(cat "$OUT/app-$RUN$(suffix "$n").pid")" 2>/dev/null || { log "instance $n died; see $OUT/app-$RUN$(suffix "$n").log"; return 1; }
    sleep 1
  done
  return 1
}

# Who owns what: the shard-owned engine's units per owner (and the highest fence), the fenced locks - which hold the
# exclusive event processors - per lock manager (host:pid), and the instances' pids to read those against
ownership() {
  local pids=""
  for n in $(seq 1 "$INSTANCES"); do pids+="demo-$n=$(cat "$OUT/app-$RUN$(suffix "$n").pid") "; done
  log "ownership, $1: pids $pids| units $($PSQL -d $DB -c "select coalesce(owner, '-') || ' ' || count(*) || ' (fence max ' || max(fence) || ')' from shard_queue_lease group by owner order by owner" | tr '\n' ';') | locks $($PSQL -d $DB -c "select locked_by_lockmanager_instance_id || ' ' || count(*) from fenced_locks where locked_by_lockmanager_instance_id is not null group by locked_by_lockmanager_instance_id order by 1" | tr '\n' ';')"
}

# Pids live in files: F-3 restarts an instance from inside a pipeline, whose subshell cannot set the script's variables
stop_instances() {
  for n in $(seq 1 "$INSTANCES"); do kill -TERM "$(cat "$OUT/app-$RUN$(suffix "$n").pid")" 2>/dev/null || true; done
  for n in $(seq 1 "$INSTANCES"); do
    local pid; pid=$(cat "$OUT/app-$RUN$(suffix "$n").pid")
    for _ in $(seq 1 60); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
  done
}
trap stop_instances EXIT

for n in $(seq 1 "$INSTANCES"); do
  log "starting instance $n"
  : > "$OUT/app-$RUN$(suffix "$n").log"
  start_instance "$n"
  wait_for_instance "$n" || exit 1
done

log "running the load with fault $FAULT"
set +e
./run-load.sh --population=2000 --active-per-day=1000 --orders-per-second=100 --burst-orders-per-second=300 \
  --business-days=10 --max-in-flight=2048 --base-urls="$BASE_URLS" --fault="$FAULT" --run-token="$RUN" "${DRIVER_ARGS[@]}" 2>&1 \
  | act_on_fault_window | tee "$OUT/driver-$RUN.txt"
status=${PIPESTATUS[0]}
set -e
# The engine's per-key ordering warnings: on UUID keys they are the simulators' events, reordered on the network
# (integration/CLAUDE.md); on our own ids (LD-..., LDA-...) they would be the brokerage's (F-3's "none beyond the network's")
if [[ $FAULT == F-3 ]]; then ownership "at the end"; fi
logs=("$OUT"/app-"$RUN".log)
for n in $(seq 2 "$INSTANCES"); do logs+=("$OUT/app-$RUN-$n.log"); done
ordering=$(cat "${logs[@]}" | grep -c "is being delivered key_order" || true)
ours=$(cat "${logs[@]}" | grep "is being delivered key_order" | grep -cvE "key '[0-9a-f]{8}-[0-9a-f]{4}-" || true)
log "key-order warnings: $ordering, on our own keys: $ours"
log "driver exit $status; report target/load-driver/load-$RUN.json"
exit "$status"
