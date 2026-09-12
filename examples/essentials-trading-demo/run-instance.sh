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
