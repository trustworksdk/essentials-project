#!/usr/bin/env bash
#
# Reset the demo's whole stack before a load or fault run: PostgreSQL, the exchange-simulator and the payment-gateway,
# with all their data. Run it between runs - their stores grow with every order ever placed (a 60-business-day run
# leaves ~6 GB in PostgreSQL and ~8 GB in the exchange's volume), and a full disk takes down PostgreSQL and the
# simulators mid-run. The database and the exchange must be reset together anyway (CLAUDE.md, "Simulators profile").
#
#   ./reset-stack.sh            # refuses while an instance or the load driver is running
#
# Deletes the data for good. The next ./run-instance.sh starts the containers again on empty volumes; the load driver
# registers its customers again on its first run.
set -euo pipefail
cd "$(dirname "$0")"

for port in 8080 8081; do
  if curl -s -m 2 -o /dev/null "http://localhost:$port/actuator/health"; then
    echo "An instance answers on port $port - stop it first (Ctrl-C, or kill its JVM)" >&2
    exit 2
  fi
done
if pgrep -f 'load_driver.LoadDriver' >/dev/null; then
  echo "The load driver is running - let it finish or stop it first" >&2
  exit 2
fi

# PostgreSQL's data is an anonymous volume (the image's VOLUME), which `down -v` does not always remove: note it first
pg_volume=$(docker inspect essentials-trading-demo-postgresql \
              --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}' 2>/dev/null || true)

(cd src/main/resources && docker compose -p essentials-trading-demo --profile simulators --profile observability down -v)
if [[ -n $pg_volume ]] && docker volume inspect "$pg_volume" >/dev/null 2>&1; then
  docker volume rm "$pg_volume" >/dev/null
fi

free_gb=$(df -BG --output=avail / | tail -1 | tr -dc '0-9')
echo "Stack reset. Free on /: ${free_gb} GB"
if (( free_gb < 15 )); then
  echo "WARNING: under 15 GB free - an hour-long run needs ~14 GB (PostgreSQL and the exchange's store)" >&2
fi
