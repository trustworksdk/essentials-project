#!/usr/bin/env bash
# The fixture as the base commit, then tests/review/judgement/decider-lane.patch as the change under review.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bash "$here/../_lib/stage.sh" tests/fixtures/worked-example --git
git apply "$here/../../tests/review/judgement/decider-lane.patch"
git add -A
git -c user.name=eval -c user.email=eval@example.invalid commit -q -m "Expedite orders"
