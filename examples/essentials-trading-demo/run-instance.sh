#!/usr/bin/env bash
#
# Run one instance of the trading demo, so several can share a database.
#
#   ./run-instance.sh 1        # port 8080, instance id demo-1
#   ./run-instance.sh 2        # port 8081, instance id demo-2
#   ./run-instance.sh 1 observability   # also metrics, traces and logs in Grafana
#   ./run-instance.sh 1 simulators      # also the exchange-simulator and payment-gateway
#   ./run-instance.sh 1 observability,simulators
#
# The optional second argument adds Spring profiles to `compose`. `observability` starts the
# collector, Prometheus, Tempo, Loki and Grafana beside PostgreSQL (http://localhost:3000) and turns
# on export. Pass it to every instance you want in the dashboards; each is labelled by its id.
# `simulators` starts the exchange-simulator (http://localhost:18080) and the payment-gateway
# (http://localhost:18081) beside PostgreSQL; it needs their images built locally.
#
# Each of those profiles names its compose profile in its own YAML, but a list property is not merged
# across Spring profiles - the last one wins - so this script passes the union explicitly.
#
# The point of running more than one is the shard-owned queue engine: ownership,
# fair-share rebalancing and fencing only do anything with several instances
# competing for the same queues. Watch it in the console's Shard-owned queues page,
# or:
#
#   curl -s localhost:8080/api/essentials/admin/v1/shard-owned-queues/trading-events/status
#
# TWO THINGS THIS SCRIPT EXISTS TO GET RIGHT
#
# 1. A DISTINCT INSTANCE ID, which matters more than it looks. The engine defaults
#    it to the hostname, which is right on a container platform and wrong for two
#    processes on one laptop: they would register as ONE instance, and because a
#    lease is acquired when `owner` already equals the asking instance — without
#    bumping the fence — BOTH processes would own every unit and deliver the same
#    messages. Per-key ordering is gone at that point, silently. Nothing detects it.
#
# 2. A distinct HTTP port, or the second instance dies on bind.
#
# FULL JIT: `spring-boot:run` adds -XX:TieredStopAtLevel=1 (C1 only, for a faster start) unless
# optimizedLaunch=false. This script turns that off: under load the C1-only JVM took ~1.5x the CPU and
# three times the booked p99 (_demo_harness/CLAUDE.md, "The JVM's CPU at the opens").
#
# STOPPING: Ctrl-C is enough and is graceful. The signal reaches the forked application
# JVM because it shares the terminal's foreground process group, Spring's shutdown hook
# runs, and the engine releases every lease and deregisters the instance on the way out —
# so a successor takes the units immediately instead of waiting out the 30s lease TTL, and
# fair share does not keep counting a process that has left. Verified: 408 units held,
# 0 owned and no membership rows a few seconds after SIGINT.
#
# Maven will print "Failed to execute goal ... Process terminated" afterwards. That is the
# plugin reporting that its child exited on a signal, not the application failing.
#
# The first instance starts PostgreSQL (and the profiles' services) - by `docker compose up` below, or
# through Spring Boot's Docker Compose support when that is the path taken; later ones find it running and
# reuse it. Either way the database outlives every instance (start-only): `docker compose down` stops it.
set -euo pipefail

N="${1:?usage: run-instance.sh <instance-number> [extra-spring-profiles]}"
PORT=$((8079 + N))
PROFILES="compose${2:+,$2}"
COMPOSE_PROFILES="$(echo "${2:-}" | tr ',' '\n' | grep -xE 'observability|simulators' | paste -sd, - || true)"

cd "$(dirname "$0")/../.."

# POSTGRESQL WITHOUT DOCKER'S PORT PROXY, where the container is reachable directly.
#
# Through `localhost:5432` every statement passes Docker's userland port proxy (docker-proxy): an extra hop each
# way, and about a core of CPU under load. The demo issues many short statements per order, so that hop decided its
# burst latency: measured on the same load, booked p50 557-878 ms -> 208-324 ms and p99 9.6-11.1 s -> 5.3-6.6 s
# when connecting to the container's own IP instead (_demo_harness/CLAUDE.md, "PostgreSQL through the port proxy").
#
# So this script starts the compose services itself and, when the PostgreSQL container's IP answers from here (a Linux
# host, a devcontainer running its own Docker), points the datasource at it and turns Spring's compose support off -
# its service connection would otherwise replace the URL with the published port. Where the IP does not answer
# (Docker Desktop on macOS or Windows keeps container IPs inside its VM), or with PG_DIRECT=0, nothing changes: the
# application connects through localhost:5432 and Spring's compose support starts or reuses the containers as before.
EXTRA_ARGS=""
if [[ "${PG_DIRECT:-auto}" != "0" ]]; then
    COMPOSE_FLAGS=()
    for profile in ${COMPOSE_PROFILES//,/ }; do
        COMPOSE_FLAGS+=(--profile "$profile")
    done
    if docker compose -f examples/essentials-trading-demo/compose.yml "${COMPOSE_FLAGS[@]}" up -d --wait >&2; then
        PG_IP="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' essentials-trading-demo-postgresql 2>/dev/null || true)"
        if [[ -n "$PG_IP" ]] && timeout 2 bash -c "</dev/tcp/${PG_IP}/5432" 2>/dev/null; then
            echo "run-instance: PostgreSQL directly at ${PG_IP}:5432, bypassing the port proxy (PG_DIRECT=0 to turn off)" >&2
            EXTRA_ARGS=" --spring.docker.compose.enabled=false --spring.datasource.url=jdbc:postgresql://${PG_IP}:5432/essentials-trading-demo"
        else
            echo "run-instance: PostgreSQL container IP not reachable from here; connecting through localhost:5432" >&2
        fi
    else
        echo "run-instance: 'docker compose up' failed; leaving it to Spring's compose support, through localhost:5432" >&2
    fi
fi

JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/temurin-25-jdk-arm64}" \
exec ./mvnw -o -pl examples/essentials-trading-demo -DskipDependencyCheck=true \
    -Dspring-boot.run.optimizedLaunch=false \
    -Dspring-boot.run.profiles="${PROFILES}" \
    -Dspring-boot.run.jvmArguments="-Dserver.port=${PORT}" \
    -Dspring-boot.run.arguments="--essentials.shard-owned-queue.instance-id=demo-${N}${COMPOSE_PROFILES:+ --spring.docker.compose.profiles.active=${COMPOSE_PROFILES}}${EXTRA_ARGS}" \
    spring-boot:run
