#!/usr/bin/env bash
#
# Run the phase 6 load driver (docs/design/phase-6-realistic-load.md) against running demo instances:
# simulated customers trading and moving money through the public HTTP API, from a JVM of its own.
#
#   ./run-instance.sh 1 simulators          # first, in another terminal
#   ./run-load.sh                           # a short smoke run with the defaults
#   ./run-load.sh --population=1000 --active-per-day=300 --orders-per-second=20 --burst-orders-per-second=100 \
#                 --business-days=10
#   ./run-load.sh --base-urls=http://localhost:8080,http://localhost:8081   # customers pinned across two instances
#
# Every setting is --key=value; LoadDriverSettings lists them with their defaults. The report is printed and written
# to target/load-driver/load-<run>.json; the exit code is 0 only if every check passed.
#
# The driver runs from target/classes with the module's runtime classpath, which is resolved once into
# target/load-driver.classpath (again when pom.xml changes). It does not compile: build the module first
# (`mvn -pl examples/essentials-trading-demo compile`) - never while an instance is starting, as both write target/.
set -euo pipefail
cd "$(dirname "$0")"

classpath_file=target/load-driver.classpath
if [[ ! -f target/classes/dk/trustworks/essentials/examples/trading/_demo_harness/load_driver/LoadDriver.class ]]; then
  echo "target/classes has no LoadDriver - compile the module first" >&2
  exit 2
fi
if [[ ! -f $classpath_file || pom.xml -nt $classpath_file ]]; then
  mvn -q dependency:build-classpath -Dmdep.outputFile="$classpath_file" -Dmdep.includeScope=runtime >/dev/null
fi

exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "target/classes:$(cat "$classpath_file")" \
  dk.trustworks.essentials.examples.trading._demo_harness.load_driver.LoadDriver "$@"
