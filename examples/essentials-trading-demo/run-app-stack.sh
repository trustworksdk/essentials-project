#!/usr/bin/env bash
#
# Run the whole demo in Docker: PostgreSQL, the simulators and one or two application instances in containers.
#
#   ./run-app-stack.sh                    # one instance, http://localhost:8080
#   ./run-app-stack.sh 2                  # two instances, http://localhost:8080 and http://localhost:8081
#   ./run-app-stack.sh 1 observability    # also the collector, Prometheus, Tempo, Loki and Grafana
#   ./run-app-stack.sh down               # stop the application containers (the database keeps its data)
#
# The way to run the demo on Docker Desktop (macOS, Windows), where run-instance.sh has to reach PostgreSQL through
# a published port: see compose.app.yml for why that matters. It compiles the module against the Essentials jars in
# the local Maven repository, so `mvn install` the framework first when it has changed. Logs:
# `docker logs -f essentials-trading-demo-app-1`. Reset between load runs with ./reset-stack.sh as usual.
set -euo pipefail

cd "$(dirname "$0")"
COMPOSE=(docker compose -f compose.yml -f compose.app.yml)

if [[ "${1:-}" == "down" ]]; then
    "${COMPOSE[@]}" --profile app --profile app2 stop app-1 app-2
    exit 0
fi

INSTANCES="${1:-1}"
[[ "$INSTANCES" == 1 || "$INSTANCES" == 2 ]] || { echo "usage: run-app-stack.sh [1|2|down] [observability]" >&2; exit 2; }
PROFILES=(--profile simulators --profile app)
[[ "$INSTANCES" == 2 ]] && PROFILES+=(--profile app2)
[[ "${2:-}" == "observability" ]] && PROFILES+=(--profile observability)

echo "run-app-stack: compiling the module and collecting its runtime dependencies" >&2
# runtime AND provided: Essentials declares its third-party integrations (slf4j among them) provided, which
# spring-boot:run puts on the classpath and a runtime-scope copy leaves out.
rm -rf target/app-lib
for scope in runtime provided; do
    (cd ../.. && JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/temurin-25-jdk-arm64}" ./mvnw -o -q -pl examples/essentials-trading-demo \
        -DskipDependencyCheck=true compile dependency:copy-dependencies \
        -DincludeScope=$scope -DoutputDirectory=target/app-lib -DoverWriteSnapshots=true)
done

"${COMPOSE[@]}" "${PROFILES[@]}" up -d --build
echo "run-app-stack: started; http://localhost:8080$([[ "$INSTANCES" == 2 ]] && echo ' and http://localhost:8081')" >&2
