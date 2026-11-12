#!/usr/bin/env bash
#
# Copyright 2021-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Runs the event store phase-0 spikes on a real machine, outside the devcontainer, and collects
# their results, the client's and the server's machine facts and every log into one directory
# and one tarball.
#
# WHY THIS EXISTS
#   Every phase-0 spike so far ran against a Testcontainers PostgreSQL with fsync=off, on the same
#   8-CPU arm64 Docker VM as the client. About half of PostgreSQL's CPU per attempt on that VM is
#   idle and wake-up cost (event-store-phase0-append.md §9), so the absolute numbers, and possibly
#   the ratios between arms, belong to that machine. docs/plans/event-store-phase0-external-db.md
#   describes rerunning them on real hardware. A result without the machine that produced it
#   cannot be compared with anything, and the spikes append to their result files, so a second
#   run silently mixes with the first. This script makes a run self-describing and keeps runs apart.
#
# Usage:
#   scripts/run-phase0-spikes.sh --container                        # Testcontainers here; needs Docker
#   scripts/run-phase0-spikes.sh --external jdbc:postgresql://dbhost:5432/postgres --user bench \
#       --server-cpu-command "ssh bench@dbhost cat /proc/stat"      # password from SPIKE_PASSWORD or a prompt
#   scripts/run-phase0-spikes.sh --container --suite xid,jdbc#protocol_trace -- -Dspike.pg.images=postgres:17.5
#   scripts/run-phase0-spikes.sh --external ... --suite contention -- -Dspike.contention.seconds=20
#   scripts/run-phase0-spikes.sh --container --dry-run              # print the plan and the Maven commands
#
# Options:
#   --container                  start PostgreSQL in Testcontainers on this machine (needs Docker)
#   --external <jdbcUrl>         use this server instead (env SPIKE_JDBC_URL); it must accept a superuser
#   --user <name>                superuser on the external server (env SPIKE_USER, default postgres)
#   --password <pw>              its password (env SPIKE_PASSWORD; prompted for when neither is set and
#                                stdin is a terminal). Prefer the env var or the prompt: a flag ends up in
#                                your shell history. Never printed or written to the results.
#   --server-cpu-command <cmd>   passed as -Dspike.serverCpuCommand, e.g. "ssh bench@dbhost cat /proc/stat"
#   --server-ssh <prefix>        how to reach the server for host facts, e.g. "ssh bench@dbhost". Derived
#                                from --server-cpu-command when that is "ssh ... cat /...".
#   --suite <list>               comma-separated suites, "default" or "all"; repeatable. A suite may carry a
#                                test method: jdbc#protocol_trace. Suites, in default (priority) order:
#                                  xid           AppendXidBeforeIdentitySpikeIT                          P0.1
#                                  table-design  TableDesignOptionsSpikeIT                               P0.7
#                                  contention    ContendedAppendThroughputSpikeIT                        P0.4
#                                  notify        WakeupNotifyCostSpikeIT                                 P0.10 (one pass with
#                                                the server's fsync; a container runs fsync=off and fsync=on)
#                                  tail          TailHoleReReadSpikeIT                                   P0.11 (bulk-loads two
#                                                2 M-row tables, ~4 GB of disk on the server)
#                                  probe-cost    ProbeRunningSetCostSpikeIT                              P0.12 (opens up to 400
#                                                connections: max_connections must allow it)
#                                  append        SingleStatementAppendSpikeIT,AppendEncodingAttributionSpikeIT  P0.4
#                                  baseline      CurrentEngineBaselineSpikeIT                            P0.0
#                                  cpu           UnattributedCpuSpikeIT                                  P0.9
#                                  jdbc          JdbiVsRawPgjdbcSpikeIT                                  P0.6 (+ round_trip_reconciliation)
#                                  probe         CombinedProbeSnapshotOrderSpikeIT                       P0.2
#                                "default" is that order (external-db doc §6); "all" is the same set in
#                                phase-number order.
#   --mvn-arg <arg>              one extra Maven argument, repeatable; everything after "--" is the same
#   --out <dir>                  results directory (default phase0-results/<timestamp>-<host>-<mode>/)
#   --no-build                   skip the one-off install of the module and its upstream modules
#   --dry-run                    print the plan and the Maven commands, run nothing
#   -h, --help                   this text
#
# Exit codes: 0 every suite passed, 1 a suite failed (the rest still ran), 2 usage error,
# 3 prerequisite missing, 4 build failed, 130 interrupted (what ran is still collected).
#
# Reads and builds the repository; moves components/postgresql-event-store/target/spike-results
# aside and writes only under the results directory.

set -euo pipefail

MODE=""
JDBC_URL="${SPIKE_JDBC_URL:-}"
DB_USER="${SPIKE_USER:-postgres}"
DB_PASSWORD="${SPIKE_PASSWORD:-}"
SERVER_CPU_COMMAND=""
SERVER_SSH=""
SUITE_SPECS=()
EXTRA_MVN=()
OUT=""
BUILD=1
DRY_RUN=0

usage() { sed -n '17,76p' "$0" | sed 's/^# \{0,1\}//'; }
usage_error() { printf '%s\nRun with --help for usage.\n' "$*" >&2; exit 2; }

# The command line as typed, for machine.txt, with any password value replaced. Built before
# parsing, because parsing consumes the arguments.
redact() {
  case "$1" in
    -Dspike.password=*) printf '%s' '-Dspike.password=****' ;;
    *) printf '%s' "$1" ;;
  esac
}
COMMAND_LINE="$0"
redact_next=0
for arg in "$@"; do
  if (( redact_next )); then COMMAND_LINE+=" ****"; redact_next=0; continue; fi
  case "$arg" in
    --password) COMMAND_LINE+=" --password"; redact_next=1 ;;
    --password=*) COMMAND_LINE+=" --password=****" ;;
    *\ *) COMMAND_LINE+=" '$(redact "$arg")'" ;;
    *) COMMAND_LINE+=" $(redact "$arg")" ;;
  esac
done

while [[ $# -gt 0 ]]; do
  case "$1" in
    --container)          MODE=container; shift ;;
    --external)           MODE=external; JDBC_URL="${2:?--external needs a JDBC URL}"; shift 2 ;;
    --user)               DB_USER="${2:?--user needs a name}"; shift 2 ;;
    --password)           DB_PASSWORD="${2:?--password needs a value}"; shift 2 ;;
    --password=*)         DB_PASSWORD="${1#--password=}"; shift ;;
    --server-cpu-command) SERVER_CPU_COMMAND="${2:?--server-cpu-command needs a command}"; shift 2 ;;
    --server-ssh)         SERVER_SSH="${2:?--server-ssh needs a command prefix}"; shift 2 ;;
    --suite|--suites)     SUITE_SPECS+=("${2:?--suite needs a list}"); shift 2 ;;
    --mvn-arg)            EXTRA_MVN+=("${2:?--mvn-arg needs an argument}"); shift 2 ;;
    --out)                OUT="${2:?--out needs a directory}"; shift 2 ;;
    --no-build)           BUILD=0; shift ;;
    --dry-run)            DRY_RUN=1; shift ;;
    -h|--help)            usage; exit 0 ;;
    --)                   shift; EXTRA_MVN+=("$@"); break ;;
    *)                    usage_error "Unknown argument: $1" ;;
  esac
done

[[ -n "$MODE" ]] || usage_error "Choose a mode: --container or --external <jdbcUrl>."
if [[ "$MODE" == external ]]; then
  [[ "$JDBC_URL" == jdbc:postgresql://* ]] || usage_error "--external needs a URL like jdbc:postgresql://dbhost:5432/postgres, got '$JDBC_URL'."
elif [[ -n "$SERVER_CPU_COMMAND" ]]; then
  usage_error "--server-cpu-command applies to --external only; in a container the spikes read the container's cgroup."
fi

# ---------------------------------------------------------------- suites

# bash 3.2 (macOS) has no associative arrays, hence functions.
suite_classes() {
  case "$1" in
    baseline)     echo "CurrentEngineBaselineSpikeIT" ;;
    xid)          echo "AppendXidBeforeIdentitySpikeIT" ;;
    probe)        echo "CombinedProbeSnapshotOrderSpikeIT" ;;
    append)       echo "SingleStatementAppendSpikeIT AppendEncodingAttributionSpikeIT" ;;
    contention)   echo "ContendedAppendThroughputSpikeIT" ;;
    table-design) echo "TableDesignOptionsSpikeIT" ;;
    cpu)          echo "UnattributedCpuSpikeIT" ;;
    jdbc)         echo "JdbiVsRawPgjdbcSpikeIT" ;;
    notify)       echo "WakeupNotifyCostSpikeIT" ;;
    tail)         echo "TailHoleReReadSpikeIT" ;;
    probe-cost)   echo "ProbeRunningSetCostSpikeIT" ;;
    *)            return 1 ;;
  esac
}
# The files each suite writes under target/spike-results/, as globs.
suite_results() {
  case "$1" in
    baseline)     echo "p0-0-*" ;;
    xid)          echo "p0-1-*" ;;
    probe)        echo "p0-2-*" ;;
    append)       echo "p0-4-append* p0-4-encoding*" ;;
    contention)   echo "p0-4-contention*" ;;
    table-design) echo "p0-7-*" ;;
    cpu)          echo "p0-9-*" ;;
    jdbc)         echo "p0-6-*" ;;
    notify)       echo "p0-10-*" ;;
    tail)         echo "p0-11-*" ;;
    probe-cost)   echo "p0-12-*" ;;
  esac
}
DEFAULT_SUITES="xid table-design contention notify tail probe-cost append baseline cpu jdbc probe"
ALL_SUITES="baseline xid probe append contention table-design cpu jdbc notify tail probe-cost"

(( ${#SUITE_SPECS[@]} )) || SUITE_SPECS=(default)
SUITES=()
for spec_list in "${SUITE_SPECS[@]}"; do
  for spec in ${spec_list//,/ }; do
    case "$spec" in
      default) for s in $DEFAULT_SUITES; do SUITES+=("$s"); done ;;
      all)     for s in $ALL_SUITES; do SUITES+=("$s"); done ;;
      *)
        suite_classes "${spec%%#*}" >/dev/null \
          || usage_error "Unknown suite '${spec%%#*}'. Known: $ALL_SUITES, default, all."
        SUITES+=("$spec") ;;
    esac
  done
done

# -Dit.test for a suite spec: name or name#method; a method applies to each class of the suite.
it_test_for() {
  local name="${1%%#*}" method="" classes="" c
  [[ "$1" == *#* ]] && method="#${1#*#}"
  for c in $(suite_classes "$name"); do classes+="${classes:+,}$c$method"; done
  echo "$classes"
}

# ---------------------------------------------------------------- environment

cd "$(dirname "$0")/.."
REPO="$PWD"
MODULE="components/postgresql-event-store"
SPIKE_RESULTS="$REPO/$MODULE/target/spike-results"
STAMP="$(date +%Y%m%d-%H%M%S)"
HOST="$(uname -n | cut -d. -f1 | tr -c 'A-Za-z0-9-\n' '-')"
OUT="${OUT:-$REPO/phase0-results/$STAMP-$HOST-$MODE}"
case "$OUT" in /*) ;; *) OUT="$REPO/$OUT" ;; esac

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
warn() { printf '\033[33m%s\033[0m\n' "$*" >&2; }
fail() { printf '\n\033[31m%s\033[0m\n' "$1" >&2; exit "${2:-3}"; }   # message [exit code, default 3]

# Portable `timeout`: macOS has none by default.
with_timeout() {
  local seconds="$1" pid watcher rc=0; shift
  "$@" & pid=$!
  ( sleep "$seconds"; kill "$pid" 2>/dev/null ) >/dev/null 2>&1 & watcher=$!
  wait "$pid" || rc=$?
  kill "$watcher" 2>/dev/null || true
  wait "$watcher" 2>/dev/null || true
  return "$rc"
}

# Host, port and database out of jdbc:postgresql://host[:port][/db][?params]. The query string is
# dropped for psql: JDBC-only parameters (ssl=true, ...) are not libpq ones.
DB_HOST="" DB_PORT=5432 DB_NAME=postgres
if [[ "$MODE" == external ]]; then
  rest="${JDBC_URL#jdbc:postgresql://}"
  rest="${rest%%\?*}"
  hostport="${rest%%/*}"
  [[ "$rest" == */* && -n "${rest#*/}" ]] && DB_NAME="${rest#*/}"
  DB_HOST="${hostport%%:*}"
  [[ "$hostport" == *:* ]] && DB_PORT="${hostport##*:}"
  [[ -n "$DB_HOST" ]] || usage_error "Could not read a host from '$JDBC_URL'."
  if [[ -z "$SERVER_SSH" && "$SERVER_CPU_COMMAND" =~ ^(ssh[[:space:]].*)[[:space:]]+cat[[:space:]]+/ ]]; then
    SERVER_SSH="${BASH_REMATCH[1]}"
  fi
fi

# Maven: the wrapper pins the version the build is tested with; MVN overrides.
if [[ -n "${MVN:-}" ]]; then
  read -r -a MVN_CMD <<<"$MVN"
elif [[ -x "$REPO/mvnw" ]]; then
  MVN_CMD=(./mvnw)
else
  MVN_CMD=(mvn)
fi

MVN_COMMON=(-DskipDependencyCheck=true)
SPIKE_ARGS=(-Dspike.run=true -Dfailsafe.forkCount=1 -DskipUTs -Dfailsafe.failIfNoSpecifiedTests=false)
if [[ "$MODE" == external ]]; then
  SPIKE_ARGS+=("-Dspike.jdbcUrl=$JDBC_URL" "-Dspike.user=$DB_USER")
  [[ -n "$SERVER_CPU_COMMAND" ]] && SPIKE_ARGS+=("-Dspike.serverCpuCommand=$SERVER_CPU_COMMAND")
fi
BUILD_CMD=("${MVN_CMD[@]}" -q install -DskipTests "${MVN_COMMON[@]}" -pl "$MODULE" -am ${EXTRA_MVN[@]+"${EXTRA_MVN[@]}"})

suite_cmd() {   # the command array for one suite, into SUITE_CMD; the password is appended only when run
  SUITE_CMD=("${MVN_CMD[@]}" verify -pl "$MODULE" "${MVN_COMMON[@]}" "${SPIKE_ARGS[@]}"
             "-Dit.test=$(it_test_for "$1")" ${EXTRA_MVN[@]+"${EXTRA_MVN[@]}"})
}
print_cmd() {
  local out="" a
  for a in "$@"; do
    a="$(redact "$a")"
    case "$a" in *[[:space:]\'\"\;\&\|\$?*]*) out+=" '${a//\'/\'\\\'\'}'" ;; *) out+=" $a" ;; esac
  done
  echo "${out# }"
}

say "Plan"
cat <<PLAN
  mode              $MODE$( [[ "$MODE" == external ]] && echo " -> $DB_HOST:$DB_PORT/$DB_NAME as $DB_USER" )
  suites            ${SUITES[*]}
  results           $OUT
  build             $( ((BUILD)) && print_cmd "${BUILD_CMD[@]}" || echo "skipped (--no-build)" )
PLAN
for spec in "${SUITES[@]}"; do
  suite_cmd "$spec"
  if [[ "$MODE" == external ]]; then
    printf '  %-16s  %s -Dspike.password=****\n' "$spec" "$(print_cmd "${SUITE_CMD[@]}")"
  else
    printf '  %-16s  %s\n' "$spec" "$(print_cmd "${SUITE_CMD[@]}")"
  fi
done

if (( DRY_RUN )); then
  say "Dry run — nothing checked or executed."
  exit 0
fi

# ---------------------------------------------------------------- prerequisites

say "Checking prerequisites"

JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA_BIN="${JAVA_BIN:-java}"
command -v "$JAVA_BIN" >/dev/null 2>&1 || fail "No java found. Install a JDK 25, 26 or 27 and put it on the PATH or in JAVA_HOME."
# Not `head -1`: with JAVA_TOOL_OPTIONS set the JVM prints "Picked up ..." first.
JAVA_VERSION="$("$JAVA_BIN" -version 2>&1 | grep -m1 'version "' || true)"
JAVA_MAJOR="$(sed -E 's/.*version "([0-9]+).*/\1/' <<<"$JAVA_VERSION")"
[[ "$JAVA_MAJOR" =~ ^[0-9]+$ ]] || fail "Could not read a version from '$JAVA_BIN -version'. The build needs JDK 25 to 27."
(( JAVA_MAJOR >= 25 && JAVA_MAJOR < 28 )) \
  || fail "Java $JAVA_MAJOR found ($JAVA_VERSION). The build needs JDK 25 to 27 — the enforcer requires [25,28)."
echo "  java    $JAVA_VERSION"

if [[ "${MVN_CMD[0]}" == ./mvnw ]]; then
  echo "  maven   ./mvnw (downloads its pinned Maven on first use)"
else
  command -v "${MVN_CMD[0]}" >/dev/null 2>&1 || fail "'${MVN_CMD[0]}' is not on the PATH. Install Maven 3.8.8+ or set MVN."
  echo "  maven   ${MVN_CMD[*]}"
fi

if [[ "$MODE" == container ]]; then
  command -v docker >/dev/null 2>&1 || fail "docker is not on the PATH; --container needs Docker for Testcontainers."
  docker info >/dev/null 2>&1 || fail "Docker is not running (docker info failed). Start it and try again."
  echo "  docker  $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo running)"
else
  if [[ -z "$DB_PASSWORD" ]]; then
    if [[ -t 0 ]]; then
      read -r -s -p "  Password for $DB_USER@$DB_HOST: " DB_PASSWORD; echo
    fi
    [[ -n "$DB_PASSWORD" ]] || fail "No password for $DB_USER. Set SPIKE_PASSWORD, or pass --password." 2
  fi
  # A TCP connect first: it needs nothing installed and tells a firewall from a bad password.
  # shellcheck disable=SC2016  # $0 and $1 are expanded by the inner bash
  with_timeout 5 bash -c 'exec 3<>"/dev/tcp/$0/$1"' "$DB_HOST" "$DB_PORT" 2>/dev/null \
    || fail "Cannot open a TCP connection to $DB_HOST:$DB_PORT. Check listen_addresses, pg_hba.conf and the firewall (external-db doc §3)."
  echo "  server  $DB_HOST:$DB_PORT accepts TCP connections"
  if command -v psql >/dev/null 2>&1; then
    HAVE_PSQL=1
    is_super="$(PGPASSWORD="$DB_PASSWORD" PGCONNECT_TIMEOUT=10 psql -X -At -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
                -c "SELECT rolsuper FROM pg_roles WHERE rolname = current_user" 2>&1)" \
      || fail "psql could not log in as $DB_USER on $DB_HOST:$DB_PORT/$DB_NAME: $is_super"
    [[ "$is_super" == t ]] || fail "$DB_USER is not a superuser on $DB_HOST; the spikes need one (external-db doc §3, Access)."
    echo "  server  login as $DB_USER works, superuser"
  else
    HAVE_PSQL=0
    echo "  server  psql not installed: login, superuser and pg_stat_statements are checked by the first spike's own start-up check instead"
  fi
  if [[ -n "$SERVER_CPU_COMMAND" ]]; then
    with_timeout 20 sh -c "$SERVER_CPU_COMMAND" </dev/null >/dev/null 2>&1 \
      || fail "--server-cpu-command failed or hung: '$SERVER_CPU_COMMAND'. Set up key-based ssh so it runs without a prompt."
    echo "  cpu     --server-cpu-command runs"
  fi
fi

# Nothing else should build this module meanwhile: a concurrent mvn cleans target/ under the run.
if others="$(pgrep -fl 'org.codehaus.plexus.classworlds.launcher.Launcher|maven-wrapper' 2>/dev/null)" && [[ -n "$others" ]]; then
  warn "  Another Maven process is running; it may rebuild target/ under this run:"
  warn "$others"
fi

# ---------------------------------------------------------------- run

mkdir -p "$OUT/logs"

# The spikes append to their result files, so earlier runs (the VM's, say) would mix with this one.
if [[ -d "$SPIKE_RESULTS" ]]; then
  ASIDE="$SPIKE_RESULTS.before-$STAMP"
  mv "$SPIKE_RESULTS" "$ASIDE"
  echo "  moved earlier spike results aside to ${ASIDE#"$REPO"/}"
fi

# ---- machine facts, written before the run so they exist even if it is interrupted
{
  echo "# Client machine — written $(date -u +%Y-%m-%dT%H:%M:%SZ) by scripts/run-phase0-spikes.sh"
  echo
  echo "command:   $COMMAND_LINE"
  echo "mode:      $MODE"
  [[ "$MODE" == external ]] && echo "server:    $DB_HOST:$DB_PORT/$DB_NAME as $DB_USER"
  echo "suites:    ${SUITES[*]}"
  echo
  echo "## git"
  echo "branch:    $(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
  echo "commit:    $(git rev-parse HEAD 2>/dev/null || echo unknown)"
  echo "dirty:     $(git diff --quiet HEAD 2>/dev/null && echo no || echo yes)"
  git status --short 2>/dev/null | sed 's/^/  /' || true
  echo
  echo "## os"
  uname -a
  case "$(uname -s)" in
    Darwin)
      echo "cpu:       $(sysctl -n machdep.cpu.brand_string 2>/dev/null || echo unknown)"
      echo "cpus:      $(sysctl -n hw.ncpu) logical, $(sysctl -n hw.physicalcpu 2>/dev/null || echo '?') physical"
      echo "memory:    $(( $(sysctl -n hw.memsize) / 1024 / 1024 / 1024 )) GB"
      sw_vers 2>/dev/null || true
      ;;
    Linux)
      # x86 has a "model name"; arm64 kernels often print none, so fall back to the architecture.
      cpu_model="$(grep -m1 'model name' /proc/cpuinfo 2>/dev/null | cut -d: -f2- | sed 's/^ *//' || true)"
      echo "cpu:       ${cpu_model:-$(uname -m), no model name in /proc/cpuinfo}"
      echo "cpus:      $(nproc)"
      command -v lscpu >/dev/null 2>&1 && lscpu | grep -E 'Model name|^CPU\(s\)|MHz|Thread|Core|Socket' || true
      free -g 2>/dev/null || grep -E 'MemTotal|MemAvailable' /proc/meminfo
      if [[ -r /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor ]]; then
        echo "governor:  $(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor)"
      fi
      [[ -r /sys/devices/system/cpu/intel_pstate/no_turbo ]] && echo "no_turbo:  $(cat /sys/devices/system/cpu/intel_pstate/no_turbo)"
      ;;
  esac
  echo
  echo "## jdk"
  "$JAVA_BIN" -version 2>&1
  echo
  echo "## maven"
  "${MVN_CMD[@]}" -v 2>&1 || echo "(${MVN_CMD[*]} -v failed)"
  if [[ "$MODE" == container ]]; then
    echo
    echo "## docker"
    docker version --format 'client {{.Client.Version}}, server {{.Server.Version}} ({{.Server.Os}}/{{.Server.Arch}})' 2>&1 || true
    docker info --format 'cpus available to containers: {{.NCPU}}, memory: {{.MemTotal}} bytes, kernel {{.KernelVersion}}, {{.OperatingSystem}}' 2>&1 || true
  else
    echo
    echo "## network to $DB_HOST"
    ping -c 20 -i 0.2 "$DB_HOST" 2>&1 | tail -2 || echo "(ping failed — ICMP may be blocked)"
  fi
} > "$OUT/machine.txt" 2>&1

# ---- server facts (external mode)
if [[ "$MODE" == external ]]; then
  {
    echo "# Database server $DB_HOST:$DB_PORT — written $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo
    if (( HAVE_PSQL )); then
      export PGPASSWORD="$DB_PASSWORD"
      q() { psql -X -At -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -c "$1" 2>&1 || true; }
      echo "## version"
      q "SELECT version()"
      echo
      echo "## settings"
      q "SELECT rpad(name, 32) || setting || coalesce(' ' || unit, '') FROM pg_settings WHERE name IN
         ('fsync','synchronous_commit','full_page_writes','wal_level','wal_compression','wal_buffers',
          'shared_buffers','effective_cache_size','work_mem','max_connections','max_wal_size','checkpoint_timeout',
          'shared_preload_libraries','jit','huge_pages','random_page_cost','autovacuum','track_io_timing',
          'pg_stat_statements.track','pg_stat_statements.track_utility','pg_stat_statements.track_planning',
          'pg_stat_statements.max','data_directory') ORDER BY name"
      echo
      # psql's \timing is measured on the client around each statement: one network round trip plus a
      # trivial execution, the floor under every single-thread latency the spikes report.
      printf '%s\n' '## round trip: 200 x SELECT 1 over one connection (psql \timing, ms)'
      { printf '%s\n' '\timing on'; i=0; while (( i < 200 )); do echo 'SELECT 1;'; i=$((i + 1)); done; } \
        | LC_ALL=C psql -X -At -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" 2>&1 \
        | awk '/^Time: / { print $2 }' | sort -n \
        | awk '{ v[NR] = $1; s += $1 }
               END { if (NR == 0) { print "no timings"; exit }
                     printf "n=%d min=%.3f p50=%.3f avg=%.3f p90=%.3f p99=%.3f max=%.3f\n",
                            NR, v[1], v[int(NR*0.5)+1], s/NR, v[int(NR*0.9)+1], v[int(NR*0.99)+1], v[NR] }'
      unset PGPASSWORD
    else
      echo "psql is not installed on the client, so the server's version and settings are not here."
      echo "Every spike's results header records them (the line starting 'EXTERNAL server')."
    fi
    if [[ -n "$SERVER_SSH" ]]; then
      echo
      echo "## host (via: $SERVER_SSH)"
      # shellcheck disable=SC2016  # expanded on the database host, not here
      remote='uname -a; echo "cpus: $(nproc)"; grep -m1 "model name" /proc/cpuinfo; free -g;
              lsblk -d -o NAME,ROTA,MODEL,SIZE;
              cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null | sed "s/^/governor: /";
              cat /sys/devices/system/cpu/intel_pstate/no_turbo 2>/dev/null | sed "s/^/no_turbo: /";
              cat /proc/loadavg | sed "s/^/loadavg: /"'
      with_timeout 30 sh -c "$SERVER_SSH \"\$1\"" sh "$remote" </dev/null 2>&1 || echo "(host facts failed)"
    else
      echo
      echo "No --server-ssh (or ssh-shaped --server-cpu-command): no host facts. Record the CPU, disk"
      echo "(lsblk -d -o NAME,ROTA,MODEL,SIZE) and pg_test_fsync by hand (external-db doc §7)."
    fi
  } > "$OUT/server.txt"
fi

# ---- build
STATUS_BUILD="skipped"
if (( BUILD )); then
  say "Building $MODULE and its upstream modules"
  started=$SECONDS
  if "${BUILD_CMD[@]}" > "$OUT/logs/build.log" 2>&1; then
    STATUS_BUILD="ok in $(( SECONDS - started )) s"
  else
    tail -30 "$OUT/logs/build.log" >&2
    fail "Build failed — see $OUT/logs/build.log" 4
  fi
fi

# ---- suites
INTERRUPTED=0
trap 'INTERRUPTED=1' INT
FAILED=0
RESULT_ROWS=()
fmt_duration() { printf '%dm%02ds' $(( $1 / 60 )) $(( $1 % 60 )); }

for spec in "${SUITES[@]}"; do
  name="${spec%%#*}"
  log_name="$(echo "$spec" | tr '#' '-')"
  log="$OUT/logs/$log_name.log"
  if (( INTERRUPTED )); then
    RESULT_ROWS+=("$spec|not run (interrupted)|-|-")
    continue
  fi
  suite_cmd "$spec"
  say "$spec  ($(it_test_for "$spec"))"
  echo "  log: $log"
  started=$SECONDS
  rc=0
  if [[ "$MODE" == external ]]; then
    "${SUITE_CMD[@]}" "-Dspike.password=$DB_PASSWORD" > "$log" 2>&1 || rc=$?
  else
    "${SUITE_CMD[@]}" > "$log" 2>&1 || rc=$?
  fi
  took="$(fmt_duration $(( SECONDS - started )))"
  if (( INTERRUPTED )); then
    status="interrupted"; FAILED=1
  elif (( rc == 0 )); then
    # Failsafe passes when nothing ran — a typo'd method or a disabled class. Say so.
    if grep -q 'Tests run: [1-9]' "$log"; then status="passed"; else status="passed (no tests ran?)"; fi
  else
    status="FAILED (exit $rc)"; FAILED=1
    tail -30 "$log" >&2
  fi
  echo "  $status in $took"
  RESULT_ROWS+=("$spec|$status|$took|logs/$log_name.log")

  # Failsafe reports of this suite's classes; the next suite's run does not remove them, but a
  # later rerun of the same class would overwrite them.
  if [[ -d "$REPO/$MODULE/target/failsafe-reports" ]]; then
    mkdir -p "$OUT/failsafe-reports"
    for c in $(suite_classes "$name"); do
      for f in "$REPO/$MODULE/target/failsafe-reports/"*"$c"*; do
        [[ -e "$f" ]] && cp "$f" "$OUT/failsafe-reports/"
      done
    done
  fi
done
trap - INT

# ---------------------------------------------------------------- collect

if [[ -d "$SPIKE_RESULTS" ]]; then
  mkdir -p "$OUT/spike-results"
  cp -R "$SPIKE_RESULTS/." "$OUT/spike-results/"
fi

{
  echo "# Phase-0 spike run $STAMP on $HOST ($MODE)"
  echo
  echo "Written by \`scripts/run-phase0-spikes.sh\`. Machine facts: [machine.txt](machine.txt)$( [[ "$MODE" == external ]] && echo ", server facts: [server.txt](server.txt)" )."
  echo
  echo "- Mode: $MODE$( [[ "$MODE" == external ]] && echo " ($DB_HOST:$DB_PORT/$DB_NAME as $DB_USER)" )"
  echo "- Commit: $(git rev-parse --short HEAD 2>/dev/null || echo unknown)$(git diff --quiet HEAD 2>/dev/null || echo ' (dirty)') on $(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
  echo "- Build: $STATUS_BUILD"
  if (( ${#EXTRA_MVN[@]} )); then echo "- Extra Maven arguments: \`$(print_cmd "${EXTRA_MVN[@]}")\`"; fi
  echo
  echo "| Suite | Test classes | Result | Duration | Log | Result files |"
  echo "|---|---|---|---|---|---|"
  for row in "${RESULT_ROWS[@]}"; do
    IFS='|' read -r spec status took logfile <<<"$row"
    files=""
    for g in $(suite_results "${spec%%#*}"); do
      for f in "$OUT/spike-results/"$g; do
        [[ -e "$f" ]] && files+="${files:+, }[${f##*/}](spike-results/${f##*/})"
      done
    done
    logcell="-"; [[ "$logfile" != - ]] && logcell="[${logfile##*/}]($logfile)"
    echo "| $spec | $(it_test_for "$spec" | sed 's/,/, /g') | $status | $took | $logcell | ${files:--} |"
  done
  echo
  echo "Read the results with docs/plans/event-store-phase0-external-db.md §8."
} > "$OUT/SUMMARY.md"

TARBALL="$OUT.tar.gz"
tar -czf "$TARBALL" -C "$(dirname "$OUT")" "$(basename "$OUT")"

say "Results in ${OUT#"$REPO"/}"
sed -n '/^| /p' "$OUT/SUMMARY.md"
echo
echo "Tarball: ${TARBALL#"$REPO"/}  — copy that back."

(( INTERRUPTED )) && exit 130
exit "$FAILED"
