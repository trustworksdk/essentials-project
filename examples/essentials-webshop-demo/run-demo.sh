#!/usr/bin/env bash
#
# Start the essentials-webshop-demo Spring Boot application.
#
# The `compose` profile activates Spring Boot's Docker Compose support, so PostgreSQL and Kafka
# are started from src/main/resources/compose.yml automatically. An existing PostgreSQL container
# is reused, and its data is kept — that accumulated data is what makes the next run a meaningful
# check that the persisted format still reads back.
#
# Stopping the application — Ctrl-C, or --stop — runs Spring Boot's compose *stop* command, which
# stops the containers and keeps both them and their volume. It does NOT run `down`, and it does
# not remove data. Use --fresh when you want the opposite.
#
#   ./run-demo.sh                 start in the foreground (Ctrl-C to stop; data is kept)
#   ./run-demo.sh --fresh         start, and throw the data away on stop (compose down -v)
#   ./run-demo.sh --wipe          remove the containers and the volume now, without starting
#   ./run-demo.sh --background    start detached, wait for readiness, report the log location
#   ./run-demo.sh --stop          stop a detached run
#   ./run-demo.sh --install       install the reactor modules the demo depends on, then start
#
# Add --offline to keep Maven off the network.
#
# Shop:          http://localhost:8080/shop/index.html
# Admin console: http://localhost:8080/essentials/admin
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOG_FILE="${WEBSHOP_DEMO_LOG:-/tmp/webshop-demo.log}"
MODULE=":essentials-webshop-demo"

# The JAVA_HOME inherited in this devcontainer points at a JDK that is not there any more, so it is
# overridden per invocation. /usr/lib/jvm/temurin-jdk is the architecture-independent symlink the
# devcontainer maintains; outside the container, whatever JAVA_HOME is already set is used.
if [[ -d /usr/lib/jvm/temurin-jdk ]]; then
    export JAVA_HOME=/usr/lib/jvm/temurin-jdk
fi

background=false
install_deps=false
fresh=false
maven_flags=(-DskipDependencyCheck=true)
COMPOSE_PROJECT=essentials-webshop-demo

for arg in "$@"; do
    case "$arg" in
        --background) background=true ;;
        --install)    install_deps=true ;;
        --fresh)      fresh=true ;;
        --offline)    maven_flags+=(-o) ;;
        --wipe)
            # The project name comes from the `name:` key in compose.yml, so this reaches the stack the
            # application started whichever copy of the file compose is pointed at. Without that key the
            # project would be named after the directory the file was run from — `target/classes` — and this
            # command would cheerfully report success against a project that does not exist.
            docker compose -p "$COMPOSE_PROJECT" down -v || true
            echo "Removed the ${COMPOSE_PROJECT} containers and their volume. The next run starts empty."
            exit 0
            ;;
        --stop)
            # Matched on the module coordinate rather than on "spring-boot:run", which would also kill a
            # sibling demo (the trading demo, say) running from the same reactor.
            pkill -f "spring-boot:run.*${MODULE}" || pkill -f "${MODULE}.*spring-boot:run" || true
            sleep 3
            pkill -f "WebshopDemoApplication" || true
            echo "Stopped. The containers were stopped but kept, and so was their data."
            echo "  ./run-demo.sh --wipe    remove the containers and the volume"
            exit 0
            ;;
        *)
            echo "Unknown option: $arg" >&2
            exit 2
            ;;
    esac
done

cd "$REPO_ROOT"

if [[ "$install_deps" == true ]]; then
    echo "Installing the demo's reactor dependencies..."
    mvn -q "${maven_flags[@]}" -pl "$MODULE" -am -DskipTests install
fi

profiles=compose
if [[ "$fresh" == true ]]; then
    # `compose-fresh` sets spring.docker.compose.stop.command=down with `-v`, so a *graceful* shutdown takes
    # the volume with it. A kill -9 skips the JVM shutdown hook and therefore skips this too; --wipe is the
    # way to clean up after one of those.
    profiles=compose,compose-fresh
    echo "Running with --fresh: the database is thrown away when the application stops."
fi

run_args=("${maven_flags[@]}" -pl "$MODULE" -Dspring-boot.run.profiles="$profiles" spring-boot:run)

if [[ "$background" == false ]]; then
    exec mvn "${run_args[@]}"
fi

echo "Starting in the background, logging to ${LOG_FILE}"
nohup mvn "${run_args[@]}" > "$LOG_FILE" 2>&1 &

# Kafka's healthcheck allows a 15s start period before the first probe, so the first run on a cold
# machine — where the images still have to be pulled — takes noticeably longer than a restart.
for _ in $(seq 1 120); do
    if grep -q "Started WebshopDemoApplication" "$LOG_FILE" 2>/dev/null; then
        echo "Started. Shop: http://localhost:8080/shop/index.html"
        echo "         Admin: http://localhost:8080/essentials/admin"
        errors=$(grep -cE " ERROR |Caused by" "$LOG_FILE" || true)
        if [[ "$errors" -gt 0 ]]; then
            echo "WARNING: ${errors} error lines in ${LOG_FILE} — a clean start logs none."
        fi
        exit 0
    fi
    if ! pgrep -f "spring-boot:run" > /dev/null; then
        echo "The application exited during startup. Last lines of ${LOG_FILE}:" >&2
        tail -30 "$LOG_FILE" >&2
        exit 1
    fi
    sleep 1
done

echo "Timed out after 120s waiting for startup. See ${LOG_FILE}" >&2
exit 1
