#!/usr/bin/env bash
# =============================================================================
# dev.sh — Development convenience script
#
# Usage:
#   ./dev.sh              Start backend + frontend (full stack)
#   ./dev.sh backend      Start Spring Boot (auto-starts PostgreSQL via Docker Compose)
#   ./dev.sh frontend     Start Vite dev server with HMR
#   ./dev.sh generate     Export OpenAPI spec and regenerate TypeScript client
#
# Note: PostgreSQL is auto-managed by Spring Boot Docker Compose support.
#       The backend starts/stops the DB container automatically from backend/compose.yml.
# =============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

cmd_backend() {
    echo "Starting Spring Boot backend (PostgreSQL auto-starts via Docker Compose)..."
    cd "$SCRIPT_DIR/backend"
    ./mvnw spring-boot:run -Pskip-frontend
}

cmd_frontend() {
    echo "Starting Vite dev server (HMR)..."
    cd "$SCRIPT_DIR/frontend"
    npm run dev
}

cmd_generate() {
    echo "Exporting OpenAPI spec from running backend..."
    mkdir -p "$SCRIPT_DIR/contracts"
    curl -sf http://localhost:8080/v3/api-docs -o "$SCRIPT_DIR/contracts/openapi.json"
    echo "Spec written to contracts/openapi.json"

    echo "Regenerating TypeScript client..."
    cd "$SCRIPT_DIR/frontend"
    npx orval
    echo "Done — Orval client regenerated."
}

cmd_all() {
    trap 'kill 0' SIGINT SIGTERM
    cmd_backend &
    cmd_frontend &
    wait
}

case "${1:-all}" in
    backend)   cmd_backend ;;
    frontend)  cmd_frontend ;;
    generate)  cmd_generate ;;
    all)       cmd_all ;;
    *)
        echo "Usage: $0 {backend|frontend|generate|all}"
        echo ""
        echo "  backend   Start Spring Boot (auto-starts PostgreSQL)"
        echo "  frontend  Start Vite dev server with HMR"
        echo "  generate  Export OpenAPI spec + regenerate TS client"
        echo "  all       Start everything (default)"
        exit 1
        ;;
esac
