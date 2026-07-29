#!/usr/bin/env bash
# Build the perf-lab fat JAR on the host then build the runtime Docker image. Two-step
# process is intentional: Maven runs once on the host (uses the local repo cache, no
# repeat downloads) and the Docker build is just a `COPY` plus a base image pull.
# Total build time is dominated by the JAR build (~30s clean, ~5s incremental).
#
# Usage:
#   ./scripts/build-app-image.sh                 # build + image
#   SKIP_MVN=true ./scripts/build-app-image.sh   # use existing JAR, just rebuild image
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
LAB_DIR="$ROOT_DIR/examples/essentials-performance-lab"

JAR_PATH="$LAB_DIR/target/essentials-performance-lab-DEV-SNAPSHOT.jar"

if [[ "${SKIP_MVN:-false}" != "true" ]]; then
  echo "[perf-lab] running mvn package (skip with SKIP_MVN=true)…"
  (cd "$ROOT_DIR" && mvn -q -pl examples/essentials-performance-lab -am package -DskipTests)
fi

if [[ ! -f "$JAR_PATH" ]]; then
  echo "[perf-lab] ERROR: expected JAR not found at $JAR_PATH"
  echo "[perf-lab] run 'mvn -pl examples/essentials-performance-lab package -DskipTests' first or unset SKIP_MVN"
  exit 1
fi

echo "[perf-lab] building docker image essentials/perf-lab-app:DEV-SNAPSHOT"
docker compose -f "$LAB_DIR/docker-compose.yml" build perf-lab-app-1

echo "[perf-lab] image built. Verify:"
docker images essentials/perf-lab-app:DEV-SNAPSHOT
