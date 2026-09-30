#!/usr/bin/env bash
# A freshly rendered Kotlin host (the init renderer's slice-compile host), committed as the base.
set -euo pipefail
plugin_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
python3 "$plugin_root/scripts/init-render.py" --host kotlin-pg-event-sourced --out "$PWD" >/dev/null
git init -q -b main .
git add -A
git -c user.name=eval -c user.email=eval@example.invalid commit -q -m "host"
