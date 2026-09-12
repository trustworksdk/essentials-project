#!/usr/bin/env bash
#
# Run one instance of the trading demo, so several can share a database.
#
#   ./run-instance.sh 1        # port 8080, instance id demo-1
#   ./run-instance.sh 2        # port 8081, instance id demo-2
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
# The first instance starts PostgreSQL through Spring Boot's Docker Compose support;
# later ones find it running and reuse it. Stop instance 1 last, or its shutdown
# takes the database away from the others.
set -euo pipefail

N="${1:?usage: run-instance.sh <instance-number>}"
PORT=$((8079 + N))

cd "$(dirname "$0")/../.."

JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/temurin-25-jdk-arm64}" \
exec ./mvnw -o -pl examples/essentials-trading-demo -DskipDependencyCheck=true \
    -Dspring-boot.run.profiles=compose \
    -Dspring-boot.run.jvmArguments="-Dserver.port=${PORT}" \
    -Dspring-boot.run.arguments="--essentials.shard-owned-queue.instance-id=demo-${N} --trading-demo.load.enabled=$([ "$N" = 1 ] && echo true || echo false)" \
    spring-boot:run
