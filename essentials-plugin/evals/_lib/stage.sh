#!/usr/bin/env bash
# Stage a test fixture into the eval workspace (the current directory).
#
# Sourced or executed from a case's scaffold.sh, which `claude plugin eval --scaffold` runs with the
# empty run workspace as its working directory. Copies <fixture> (a path from the plugin root) into
# it, leaving out the fixture's expectation files: the model under test must not see TEST-GUIDE.md,
# expected.yaml or cases.yaml.
#
# usage: stage.sh <fixture-path-from-plugin-root> [--set name=value]... [--git]
#   --set name=value  replace {{name}} with value in every staged file's contents
#   --git             git init + commit the staged tree as the base commit
set -euo pipefail

plugin_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
[ $# -ge 1 ] || { echo "usage: stage.sh <fixture> [--set name=value]... [--git]" >&2; exit 2; }
src="$plugin_root/$1"
shift
[ -d "$src" ] || { echo "stage.sh: no fixture at $src" >&2; exit 2; }

sets=()
git_init=0
while [ $# -gt 0 ]; do
  case "$1" in
    --set) sets+=("$2"); shift 2 ;;
    --git) git_init=1; shift ;;
    *) echo "stage.sh: unknown option $1" >&2; exit 2 ;;
  esac
done

(cd "$src" && tar -cf - --exclude=TEST-GUIDE.md --exclude=expected.yaml --exclude=cases.yaml .) | tar -xf -

for kv in "${sets[@]+"${sets[@]}"}"; do
  name="${kv%%=*}"
  value="${kv#*=}"
  grep -rlF "{{$name}}" . | while IFS= read -r f; do
    sed -i "s|{{$name}}|$value|g" "$f"
  done || true
done

if [ "$git_init" = 1 ]; then
  git init -q -b main .
  git add -A
  git -c user.name=eval -c user.email=eval@example.invalid commit -q -m "fixture"
fi
