#!/usr/bin/env bash
#
# Copyright 2021-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Ranks JUnit test execution times from Surefire/Failsafe reports so that test-suite
# optimizations can be measured rather than guessed at.
#
# Usage:
#   scripts/test-timings.sh                    # human-readable report over the whole reactor
#   scripts/test-timings.sh --csv              # machine-readable: seconds,tests,layer,module,class
#   scripts/test-timings.sh --csv > before.csv # capture a baseline to diff against later
#   scripts/test-timings.sh --top 40           # show the 40 slowest classes (default 25)
#   scripts/test-timings.sh --root some/module # restrict to a subtree
#
# Reads only; never modifies the reports it parses.

set -euo pipefail

ROOT="."
TOP=25
FORMAT="text"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --csv)   FORMAT="csv"; shift ;;
    --top)   TOP="${2:?--top needs a number}"; shift 2 ;;
    --root)  ROOT="${2:?--root needs a path}"; shift 2 ;;
    -h|--help)
      sed -n '18,29p' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

# Collect one record per test class: seconds, test count, layer, module, class.
# Surefire/Failsafe .txt reports carry a "Tests run: N, ... Time elapsed: S s" summary line.
collect() {
  find "$ROOT" -type d \( -name surefire-reports -o -name failsafe-reports \) \
       -not -path "*/.claude/*" -not -path "*/.git/*" -print0 2>/dev/null |
  while IFS= read -r -d '' dir; do
    case "$dir" in
      *surefire-reports) layer="unit" ;;
      *)                 layer="integration" ;;
    esac
    # target/<layer>-reports -> module path
    module="${dir%/target/*}"
    module="${module#./}"
    [[ -z "$module" || "$module" == "$dir" ]] && module="(root)"

    for report in "$dir"/*.txt; do
      [[ -f "$report" ]] || continue
      local_class="$(basename "$report" .txt)"
      summary="$(grep -m1 -E 'Tests run:.*Time elapsed:' "$report" 2>/dev/null || true)"
      [[ -n "$summary" ]] || continue

      seconds="$(printf '%s' "$summary" | sed -nE 's/.*Time elapsed: ([0-9]+([.,][0-9]+)?).*/\1/p' | tr ',' '.')"
      tests="$(printf '%s' "$summary" | sed -nE 's/.*Tests run: ([0-9]+).*/\1/p')"
      [[ -n "$seconds" && -n "$tests" ]] || continue

      printf '%s,%s,%s,%s,%s\n' "$seconds" "$tests" "$layer" "$module" "$local_class"
    done
  done
}

RECORDS="$(collect)"

if [[ -z "$RECORDS" ]]; then
  echo "No Surefire/Failsafe reports found under '$ROOT'." >&2
  echo "Run a build first, e.g. 'mvn verify -DskipDependencyCheck=true'." >&2
  exit 1
fi

if [[ "$FORMAT" == "csv" ]]; then
  echo "seconds,tests,layer,module,class"
  printf '%s\n' "$RECORDS" | sort -t, -k1 -rn
  exit 0
fi

printf '%s\n' "$RECORDS" | sort -t, -k1 -rn | awk -F',' -v top="$TOP" '
{
  seconds[NR] = $1; tests[NR] = $2; layer[NR] = $3; module[NR] = $4; klass[NR] = $5
  total          += $1
  totalTests     += $2
  byLayer[$3]    += $1
  classesLayer[$3]++
  byModule[$4]   += $1
}
END {
  printf "Slowest %d test classes\n", (NR < top ? NR : top)
  printf "%9s %7s  %-12s %s\n", "SECONDS", "TESTS", "LAYER", "CLASS"
  for (i = 1; i <= NR && i <= top; i++)
    printf "%9.1f %7d  %-12s %s\n", seconds[i], tests[i], layer[i], klass[i]

  printf "\nPer module\n"
  printf "%9s  %s\n", "SECONDS", "MODULE"
  n = 0
  for (m in byModule) { n++; mods[n] = m }
  for (i = 1; i <= n; i++)
    for (j = i + 1; j <= n; j++)
      if (byModule[mods[j]] > byModule[mods[i]]) { t = mods[i]; mods[i] = mods[j]; mods[j] = t }
  for (i = 1; i <= n; i++)
    printf "%9.1f  %s\n", byModule[mods[i]], mods[i]

  printf "\nTotals\n"
  for (l in byLayer)
    printf "%12s: %8.1f s (%.1f min) across %d classes\n", l, byLayer[l], byLayer[l] / 60, classesLayer[l]
  printf "%12s: %8.1f s (%.1f min) across %d classes, %d tests\n", "ALL", total, total / 60, NR, totalTests
}
'
