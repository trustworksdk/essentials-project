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
# Runs the same tests as `mvn verify`, in two phases, so that a unit-test failure anywhere in
# the reactor is known in minutes instead of after every upstream integration test.
#
# WHY THIS EXISTS
#   `mvn verify` runs each module's `test` and then its `integration-test` before moving on, so a
#   downstream module's unit tests wait behind every Testcontainers suite upstream of it. Phase 1
#   here runs only the unit tests, across the whole reactor and in parallel (no Docker needed);
#   phase 2 runs only the integration tests, with the default serial reactor and
#   failsafe.forkCount. Phase 2 does not start unless phase 1 passed.
#
# Usage:
#   scripts/fast-verify.sh                              # phase 1 (unit) then phase 2 (integration)
#   scripts/fast-verify.sh -DskipDependencyCheck=true   # any extra args go to both mvn phases
#   scripts/fast-verify.sh -pl components/foundation -am
#   FAST_VERIFY_UNIT_THREADS=4 scripts/fast-verify.sh   # phase 1 reactor threads (default 1C)
#   scripts/fast-verify.sh --help
#
# Phase 1 is `mvn -T <threads> test`; phase 2 is `mvn verify -DskipUTs` (see skipUTs in the root
# pom.xml). Afterwards scripts/test-timings.sh reads the reports of both phases as usual.

set -euo pipefail

case "${1:-}" in
  -h|--help)
    sed -n '17,35p' "$0" | sed 's/^# \{0,1\}//'
    exit 0 ;;
esac

cd "$(dirname "$0")/.."

THREADS="${FAST_VERIFY_UNIT_THREADS:-1C}"

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
elapsed() { local s=$1; printf '%dm%02ds' $((s / 60)) $((s % 60)); }

START=$SECONDS

say "fast-verify phase 1/2: unit tests (mvn -T $THREADS test $*)"
if ! mvn -T "$THREADS" test "$@"; then
  say "fast-verify: phase 1 (unit tests) FAILED after $(elapsed $((SECONDS - START))); integration tests not run."
  exit 1
fi
PHASE1=$((SECONDS - START))

say "fast-verify phase 2/2: integration tests (mvn verify -DskipUTs $*)"
PHASE2_START=$SECONDS
if ! mvn verify -DskipUTs "$@"; then
  say "fast-verify: phase 2 (integration tests) FAILED; unit $(elapsed $PHASE1), integration $(elapsed $((SECONDS - PHASE2_START)))."
  exit 1
fi
PHASE2=$((SECONDS - PHASE2_START))

say "fast-verify: OK. unit $(elapsed $PHASE1), integration $(elapsed $PHASE2), total $(elapsed $((SECONDS - START)))."
