#!/usr/bin/env bash
#
# Stops what the run scripts started, keeping the data:
#
#   1. the load driver (run-load.sh), so it does not keep calling instances that are going away;
#   2. every instance started by run-instance.sh (a JVM running TradingDemoApplication), politely first - SIGTERM, so
#      it releases its fenced locks and queue ownership and stops its CDC tailer - and only after 30 s with SIGKILL;
#   3. the containers: the instances of run-app-stack.sh, the simulators, observability and PostgreSQL
#      (docker compose down, without -v).
#
# The volumes - postgres-data, exchange-data, gateway-data - are kept, so the next start continues where this one
# stopped. ./reset-stack.sh deletes them, all three together.
#
# Usage: ./stop-instances.sh            stop the instances and the containers
#        ./stop-instances.sh --apps     stop only the instances and the load driver; leave the containers running
#
# Works on Linux and macOS (bash 3.2: no mapfile, no GNU-only options).
set -uo pipefail
cd "$(dirname "$0")"

APPS_ONLY=false
case "${1:-}" in
  "") ;;
  --apps) APPS_ONLY=true ;;
  *) echo "usage: stop-instances.sh [--apps]" >&2; exit 2 ;;
esac

stop_processes() {
  local what=$1 pattern=$2
  local pids
  pids=$(pgrep -f "$pattern" || true)
  [[ -z $pids ]] && return 0
  echo "stopping $what: $(echo $pids)"
  kill $pids 2>/dev/null || true
  for _ in $(seq 1 30); do
    pids=$(pgrep -f "$pattern" || true)
    [[ -z $pids ]] && return 0
    sleep 1
  done
  echo "  still running after 30 s - killing: $(echo $pids)" >&2
  kill -9 $pids 2>/dev/null || true
}

stop_processes "the load driver" 'load_driver\.LoadDriver'
# The JVM spring-boot:run forks; its Maven parent ends with it
stop_processes "demo instances" 'examples\.trading\.TradingDemoApplication'

if [[ $APPS_ONLY == true ]]; then
  echo "instances stopped; containers left running"
  exit 0
fi

# Every profile, so containers started under any of them are found; down without -v keeps the volumes
docker compose -f compose.yml -f compose.app.yml \
  --profile simulators --profile observability --profile app --profile app2 down
echo "stopped; data kept (./reset-stack.sh deletes it)"
