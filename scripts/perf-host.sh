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
# Runs the durable-queue performance suites on a real machine, outside the devcontainer,
# and collects the results somewhere durable.
#
# WHY THIS EXISTS
#   Every throughput figure in docs/durable-queue-measurements.md is marked comparative-only,
#   because the devcontainer runs dockerd inside itself: the load generator and PostgreSQL share
#   one cgroup budget of eight CPUs, and throughput at saturation varied 861% between repetitions.
#   That is §18.1's "throughput on hardware that can hold a number still", and it cannot be closed
#   from inside the container. It can be closed on a workstation.
#
#   The number to look at is therefore NOT the peak. It is the interquartile range each suite
#   reports beside its median. If those come in tight here and wide in the lab, the lab was the
#   problem and these figures can be quoted absolutely.
#
# Usage:
#   scripts/perf-host.sh                 # the default ~60 minute run
#   scripts/perf-host.sh --soak 20       # longer soak (wall clock is roughly 2x this)
#   scripts/perf-host.sh --no-soak       # cost, latency and concurrency only, ~25 minutes
#   scripts/perf-host.sh --rate 1000     # soak enqueue rate per second
#   scripts/perf-host.sh --dry-run       # print the plan and the environment, run nothing
#
# Reads and builds the repository; writes only under perf-results/.

set -euo pipefail

SOAK_MINUTES=10
SOAK_RATE=600
RUN_SOAK=1
DRY_RUN=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --soak)     SOAK_MINUTES="${2:?--soak needs minutes}"; shift 2 ;;
    --no-soak)  RUN_SOAK=0; shift ;;
    --rate)     SOAK_RATE="${2:?--rate needs messages per second}"; shift 2 ;;
    --dry-run)  DRY_RUN=1; shift ;;
    -h|--help)
      sed -n '17,38p' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

cd "$(dirname "$0")/.."
REPO="$PWD"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$REPO/perf-results/$STAMP"

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
fail() { printf '\n\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- prerequisites

command -v docker >/dev/null || fail "docker is not on the PATH."
docker info >/dev/null 2>&1 || fail "Docker is not running. Start Docker Desktop and try again."

JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA_BIN="${JAVA_BIN:-java}"
command -v "$JAVA_BIN" >/dev/null 2>&1 || fail "No java found. Install a JDK between 21 and 25."
# Not `head -1`: a JVM with JAVA_TOOL_OPTIONS set prints "Picked up ..." before the version, and
# this devcontainer is one of them. Match the version line itself.
JAVA_VERSION="$("$JAVA_BIN" -version 2>&1 | grep -m1 'version "' || true)"
JAVA_MAJOR="$(sed -E 's/.*version "([0-9]+).*/\1/' <<<"$JAVA_VERSION")"
# The version line contains quotes — openjdk version "25.0.4" — and it is written into a JSON file
# below. Unescaped, that file does not parse, which would defeat the one thing it is for.
JAVA_VERSION_JSON="${JAVA_VERSION//\"/\'}"
if ! [[ "$JAVA_MAJOR" =~ ^[0-9]+$ ]]; then
  fail "Could not read a version from '$JAVA_BIN -version'. This build needs a JDK between 21 and 25."
fi
if (( JAVA_MAJOR < 21 || JAVA_MAJOR > 25 )); then
  fail "Java $JAVA_MAJOR found ($JAVA_VERSION). This build needs 21 to 25 — the enforcer requires [21,26)."
fi

OS="$(uname -s)"
CORES="unknown"
MEMORY_GB="unknown"
case "$OS" in
  Darwin)
    CORES="$(sysctl -n hw.ncpu)"
    MEMORY_GB="$(( $(sysctl -n hw.memsize) / 1024 / 1024 / 1024 ))"
    ;;
  Linux)
    CORES="$(nproc)"
    MEMORY_GB="$(( $(awk '/MemTotal/ {print $2}' /proc/meminfo) / 1024 / 1024 ))"
    ;;
esac

# What Docker itself can use. On macOS and Windows this is the VM's allocation, set in Docker
# Desktop's settings, and it is the number that matters for PostgreSQL — not the host's.
DOCKER_CPUS="$(docker info --format '{{.NCPU}}' 2>/dev/null || echo unknown)"
DOCKER_MEM_GB="$(docker info --format '{{.MemTotal}}' 2>/dev/null | awk '{printf "%d", $1/1024/1024/1024}' || echo unknown)"

# PostgreSQL gets most of Docker's budget; the JVM runs outside it on macOS and Windows, so the two
# are not competing for one quota the way they do inside the devcontainer.
PG_SHARED_BUFFERS="1GB"
if [[ "$DOCKER_MEM_GB" =~ ^[0-9]+$ ]] && (( DOCKER_MEM_GB < 8 )); then
  PG_SHARED_BUFFERS="256MB"
fi

say "Environment"
cat <<ENV
  host              $OS, $CORES cores, ${MEMORY_GB} GB
  docker            $DOCKER_CPUS CPUs, ${DOCKER_MEM_GB} GB available to containers
  java              $JAVA_VERSION
  shared_buffers    $PG_SHARED_BUFFERS
  results           $OUT
ENV

if [[ "$OS" == "Darwin" ]]; then
  cat <<'NOTE'

  macOS: the JVM runs natively and PostgreSQL runs in the Docker Desktop VM, so the load
  generator and the database are already in separate scheduling domains — better isolation
  than the devcontainer, where both share one cgroup. There is no taskset on macOS and the
  JVM cannot be pinned; the arms are therefore NOT partitioned by CPU, and the results record
  that. If Docker has fewer than 8 CPUs or 16 GB, raise it in Docker Desktop -> Settings ->
  Resources before running, or PostgreSQL becomes the bottleneck and the numbers measure the
  VM's allocation.
NOTE
fi

if [[ "$DOCKER_CPUS" =~ ^[0-9]+$ ]] && [[ "$CORES" =~ ^[0-9]+$ ]] && (( DOCKER_CPUS >= CORES )); then
  cat <<'NOTE'

  WARNING: Docker is allowed as many CPUs as the host has. The JVM and PostgreSQL will then
  contend for every core, which is the condition that made the lab's throughput unquotable.
  Leave the host a few cores: Docker Desktop -> Settings -> Resources.
NOTE
fi

# ------------------------------------------------------------------------ plan

MVN=(./mvnw -pl examples/essentials-performance-lab -DskipDependencyCheck=true -Dbenchmark.run=true
     "-Dlab.pg.shared-buffers=$PG_SHARED_BUFFERS")

# taskset where it exists — Linux only, and only useful when both sides share one kernel.
PIN=()
PARTITIONED="no"
if [[ "$OS" == "Linux" ]] && command -v taskset >/dev/null 2>&1 && [[ "$CORES" =~ ^[0-9]+$ ]] && (( CORES >= 8 )); then
  GENERATOR_CPUS="0-$(( CORES / 2 - 1 ))"
  DATABASE_CPUS="$(( CORES / 2 ))-$(( CORES - 1 ))"
  PIN=(taskset -c "$GENERATOR_CPUS")
  MVN+=("-Dlab.pg.cpuset=$DATABASE_CPUS")
  PARTITIONED="generator $GENERATOR_CPUS, database $DATABASE_CPUS"
fi

say "Plan"
cat <<PLAN
  1. build            install the engine modules the lab measures
  2. cost             ShardOwnedVsBaselineCostIT      ~12 min   WAL and tuples per message
  3. latency          ShardOwnedLatencyIT             ~5 min    enqueue-to-handler
  4. concurrency      ShardOwnedConcurrencySweepIT    ~8 min    where parallelism stops paying
$( ((RUN_SOAK)) && echo "  5. soak             ShardOwnedSoakIT                ~$(( SOAK_MINUTES * 2 + 2 )) min   drift at ${SOAK_RATE}/s, both engines" )
  cpu partitioning    $PARTITIONED
PLAN

if (( DRY_RUN )); then
  say "Dry run — nothing executed."
  exit 0
fi

mkdir -p "$OUT"

# Record what produced the numbers, because a result without its environment cannot be compared
# with anything later.
{
  echo "{"
  echo "  \"timestamp\": \"$STAMP\","
  echo "  \"os\": \"$OS\","
  echo "  \"hostCores\": \"$CORES\","
  echo "  \"hostMemoryGb\": \"$MEMORY_GB\","
  echo "  \"dockerCpus\": \"$DOCKER_CPUS\","
  echo "  \"dockerMemoryGb\": \"$DOCKER_MEM_GB\","
  echo "  \"java\": \"$JAVA_VERSION_JSON\","
  echo "  \"sharedBuffers\": \"$PG_SHARED_BUFFERS\","
  echo "  \"cpuPartitioning\": \"$PARTITIONED\","
  echo "  \"soakMinutes\": $( ((RUN_SOAK)) && echo "$SOAK_MINUTES" || echo 0 ),"
  echo "  \"soakRate\": $SOAK_RATE,"
  echo "  \"gitCommit\": \"$(git rev-parse --short HEAD 2>/dev/null || echo unknown)\","
  echo "  \"gitDirty\": $(git diff --quiet HEAD 2>/dev/null && echo false || echo true)"
  echo "}"
} > "$OUT/environment.json"

run_suite() {
  local name="$1"; shift
  say "$name"
  local log="$OUT/$name.log"
  local started=$SECONDS
  # ${PIN[@]+...} rather than ${PIN[@]}: macOS ships bash 3.2, where expanding an EMPTY array
  # under `set -u` is an unbound-variable error — and PIN is empty on exactly that platform.
  if ${PIN[@]+"${PIN[@]}"} "${MVN[@]}" "$@" verify >"$log" 2>&1; then
    printf '  done in %d min %d s — %s\n' $(( (SECONDS-started)/60 )) $(( (SECONDS-started)%60 )) "$log"
  else
    printf '  FAILED after %d min — see %s\n' $(( (SECONDS-started)/60 )) "$log" >&2
    tail -30 "$log" >&2
    return 1
  fi
  # The tables each suite prints are the human-readable result; keep them beside the raw JSON.
  #
  # Every suite brackets its table between two `=====` banners, so take whole blocks rather than
  # matching on what a row looks like. Picking rows by keyword produced a latency summary with the
  # header and the footer and none of the numbers between them.
  awk '/=====/ { print; inside = !inside; next } inside { print }' "$log" \
    | sed -E 's/^[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]+ \[[^]]*\] [A-Z]+ [^ ]+ -- //' \
    > "$OUT/$name.summary.txt" || true
}

say "Building the modules the lab measures"
./mvnw install -pl examples/essentials-performance-lab -am \
     -DskipTests -DskipDependencyCheck=true -q > "$OUT/build.log" 2>&1 \
  || { tail -30 "$OUT/build.log" >&2; fail "Build failed — see $OUT/build.log"; }

FAILED=0
run_suite cost        -Dit.test='ShardOwnedVsBaselineCostIT' -DfailIfNoSpecifiedTests=false || FAILED=1
run_suite latency     -Dit.test='ShardOwnedLatencyIT'        -DfailIfNoSpecifiedTests=false || FAILED=1
run_suite concurrency -Dit.test='ShardOwnedConcurrencySweepIT' -DfailIfNoSpecifiedTests=false || FAILED=1
if (( RUN_SOAK )); then
  run_suite soak -Dit.test='ShardOwnedSoakIT' -DfailIfNoSpecifiedTests=false \
                 "-Dsoak.minutes=$SOAK_MINUTES" "-Dsoak.rate=$SOAK_RATE" || FAILED=1
fi

# The suites write machine-readable results into the module's target directory, which the next
# build would wipe.
if [[ -d examples/essentials-performance-lab/target/perf-lab-baseline ]]; then
  cp -R examples/essentials-performance-lab/target/perf-lab-baseline "$OUT/json"
fi

say "Results in $OUT"
ls -1 "$OUT"
cat <<'READ'

What to read first
  * The IQR column beside each median. Tight here and wide in the lab means the lab was the
    problem and these numbers can be quoted absolutely — which is what §18.1 is asking for.
  * cost.summary.txt: WAL bytes and dead tuples per message. These held to a few tenths of a
    percent even in the noisy lab, so they should not move much; if they do, something about
    this machine is different in a way worth knowing.
  * soak.summary.txt: whether latency or per-message cost drifts over the run, at a rate high
    enough to accumulate vacuum debt.

Send back the whole directory — environment.json is what makes the numbers comparable.
READ

exit "$FAILED"
