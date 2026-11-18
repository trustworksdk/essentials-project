#!/usr/bin/env bash
# Test command for /tw-code-review:fix: runs `mvn test` only for the Maven modules
# whose files changed since FIX_TEST_BASE (plus uncommitted edits), with -am so
# their upstream modules build. Downstream modules are not re-tested; run the
# full `mvn test -q -T 1C` once at the end of a batch of fix runs.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
base="${FIX_TEST_BASE:-73e46638c}"
mapfile -t files < <( { git diff --name-only "$base" HEAD; git diff --name-only HEAD; git ls-files --others --exclude-standard; } | sort -u)
declare -A mods=()
for f in "${files[@]}"; do
  [[ -z "$f" ]] && continue
  [[ "$f" == "pom.xml" ]] && { echo "root pom.xml changed: full suite"; exec mvn test -q -T 1C; }
  d=$(dirname "$f")
  while [[ "$d" != "." && ! -f "$d/pom.xml" ]]; do d=$(dirname "$d"); done
  [[ "$d" != "." ]] && mods["$d"]=1
done
if [[ ${#mods[@]} -eq 0 ]]; then echo "no Maven module changed: nothing to test"; exit 0; fi
list=$(IFS=,; echo "${!mods[*]}")
echo "testing modules: $list"
exec mvn test -q -T 1C -pl "$list" -am
