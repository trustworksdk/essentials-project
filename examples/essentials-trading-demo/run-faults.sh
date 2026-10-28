#!/usr/bin/env bash
#
# Run one fault of phase 6 (docs/design/phase-6-realistic-load.md §10) under the load driver, on a database and
# simulators of its own:
#
#   ./run-faults.sh none                 # the same run without a fault, for comparison
#   ./run-faults.sh F-1                  # damaged, duplicated and reordered webhook copies
#   ./run-faults.sh F-5                  # seeded reconciliation breaks (simulators/compose.breaks.yml)
#   ./run-faults.sh F-1 --business-days=3 --population=500   # extra arguments go to the driver and win
#
# What it does, in order:
#   1. recreates the exchange-simulator and the payment-gateway with empty volumes of their own (with the break settings
#      for F-5). Only those two containers and their two volumes - never `down -v`, which would also delete the demo's
#      PostgreSQL data, as it shares the compose project. Their `/test/reset` takes minutes after a full run.
#   2. recreates the database `trading_faults` and its CDC slot on the running PostgreSQL; the demo's own database is
#      not touched.
#   3. starts one instance from target/classes with plain `java` - the full JIT; `spring-boot:run` would give C1 only
#      (_demo_harness/CLAUDE.md) - and waits until the operator can log in.
#   4. runs ./run-load.sh with the decided fault profile (Q-10): 2 000 customers, 1 000 active, 100 orders/s with 300/s
#      at each open, 10 business days.
#   5. stops the instance. The exit code is the driver's: 0 only if every check passed.
#
# Needs PostgreSQL and the simulator images (compose `simulators` profile) and a compiled module
# (`mvn -pl examples/essentials-trading-demo compile`). Logs and the driver's output land in target/faults/.
set -euo pipefail
cd "$(dirname "$0")"

FAULT="${1:?usage: run-faults.sh <none|F-1|F-5> [driver arguments]}"
shift
RUN="${RUN_TOKEN:-$(echo "$FAULT" | tr -d '-' | tr 'A-Z' 'a-z')$(date +%H%M%S)}"
DB=trading_faults
OUT=target/faults
JAVA="${JAVA_HOME:-/usr/lib/jvm/temurin-25-jdk-arm64}/bin/java"
PSQL="docker exec -i essentials-trading-demo-postgresql psql -U essentials -Atq"
COMPOSE=(docker compose -p essentials-trading-demo -f compose.yml)
if [[ $FAULT == F-5 ]]; then
  COMPOSE+=(-f simulators/compose.breaks.yml)
fi
COMPOSE+=(--profile simulators)
mkdir -p "$OUT"
log() { echo "$(date +%T) [$RUN] $*"; }

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

log "starting the instance"
"$JAVA" -Dserver.port=8080 -cp "target/classes:$(cat "$classpath_file")" \
  dk.trustworks.essentials.examples.trading.TradingDemoApplication \
  --spring.profiles.active=compose,simulators --spring.docker.compose.enabled=false \
  --spring.datasource.url="jdbc:postgresql://localhost:5432/$DB" \
  --essentials.shard-owned-queue.instance-id=demo-1 > "$OUT/app-$RUN.log" 2>&1 &
APP=$!
trap 'kill -TERM $APP 2>/dev/null || true; wait $APP 2>/dev/null || true' EXIT
# /login answers before the seeded users exist; an operator call answers only once they do
for _ in $(seq 1 180); do
  if [[ $(curl -s -o /dev/null -w '%{http_code}' -u operator@demo.local:operator-demo-password 'localhost:8080/api/admin/load-report?runPrefix=none' || true) == 200 ]]; then
    break
  fi
  kill -0 $APP 2>/dev/null || { log "the instance died; see $OUT/app-$RUN.log"; exit 1; }
  sleep 1
done

log "running the load with fault $FAULT"
set +e
./run-load.sh --population=2000 --active-per-day=1000 --orders-per-second=100 --burst-orders-per-second=300 \
  --business-days=10 --max-in-flight=2048 --fault="$FAULT" --run-token="$RUN" "$@" 2>&1 | tee "$OUT/driver-$RUN.txt"
status=${PIPESTATUS[0]}
set -e
log "driver exit $status; report target/load-driver/load-$RUN.json"
exit "$status"
