#!/usr/bin/env bash
# The fixture as the base commit, then tests/review/judgement/service-entity.patch as the change under review.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bash "$here/../_lib/stage.sh" tests/fixtures/service-entity --git
git apply "$here/../../tests/review/judgement/service-entity.patch"
git add -A
git -c user.name=eval -c user.email=eval@example.invalid commit -q -m "Cancel shipments"
