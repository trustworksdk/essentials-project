#!/usr/bin/env bash
# Walks through the orphaned-slot lifecycle: start the app to provision a slot, append
# some events, stop the app abruptly, then verify from outside that
#   - the slot persists in pg_replication_slots
#   - active=false (no streaming consumer)
#   - inactive_since ticks forward
# This is the operator-runbook scenario from cdc.md §9. There's nothing to assert
# inside the JVM itself once the JVM is dead; the script is the test.
#
# Output: target/orphaned-slot/<run-id>/ + slot-state-{pre,post,after-grace}.json + run-summary.md
#
# Tunable via env: WORKLOAD_DURATION (default PT15S), GRACE_S (default 30),
# CONTAINER_NAME, CLEANUP (default false — when true, drops the slot at end).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
LAB_DIR="$ROOT_DIR/examples/essentials-performance-lab"
OUT_ROOT="$LAB_DIR/target/orphaned-slot"
RUN_ID="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="$OUT_ROOT/$RUN_ID"
mkdir -p "$OUT_DIR"

PROFILE="${PROFILE:-compose}"
WORKLOAD_DURATION="${WORKLOAD_DURATION:-PT15S}"
GRACE_S="${GRACE_S:-30}"
CONTAINER_NAME="${CONTAINER_NAME:-essentials-perf-lab-postgres}"
CLEANUP="${CLEANUP:-false}"

snapshot_slots() {
  local label="$1"
  local out="$OUT_DIR/slot-state-$label.json"
  docker exec "$CONTAINER_NAME" psql -U essentials -d essentials_lab -At -c "SELECT json_agg(row_to_json(s)) FROM (SELECT slot_name, slot_type, plugin, active, active_pid, restart_lsn::text, confirmed_flush_lsn::text, wal_status, EXTRACT(EPOCH FROM (now() - inactive_since))::bigint AS inactive_seconds FROM pg_replication_slots WHERE slot_name LIKE 'essentials\\_%') s" > "$out" || echo "[]" > "$out"
  echo "[perf-lab] snapshot $label -> $out"
}

echo "############# [perf-lab] orphaned-slot start #############"
echo "[perf-lab] run_id=$RUN_ID profile=$PROFILE workload_duration=$WORKLOAD_DURATION grace=${GRACE_S}s"
echo "[perf-lab] output=$OUT_DIR"

# Verify container is up.
if ! docker inspect -f '{{.State.Running}}' "$CONTAINER_NAME" 2>/dev/null | grep -q true; then
  echo "[perf-lab] ERROR: container '$CONTAINER_NAME' isn't running. Bring it up first:"
  echo "  docker compose -f $LAB_DIR/docker-compose.yml up -d --build"
  exit 1
fi

snapshot_slots pre

# Run a brief scenario to provision the slot + populate the inbox/event-store. Use
# slot-lag-bounded since it has a producer + subscriber wired and produces a comparable
# JSON record. The whole point is short — we want the JVM to stop quickly so the slot is
# orphaned with non-trivial state.
JSON_FILE="$OUT_DIR/scenario.json"
mvn -q -pl examples/essentials-performance-lab \
  -DskipTests \
  -Dspring-boot.run.profiles="$PROFILE" \
  -Dspring-boot.run.arguments="--essentials.lab.scenario=slot-lag-bounded \
--essentials.eventstore.cdc.enabled=true \
--essentials.eventstore.cdc.slot.metrics-interval=PT2S \
--essentials.lab.duration=$WORKLOAD_DURATION \
--essentials.lab.producer-threads=2 \
--essentials.lab.producer-rate-hz=200 \
--essentials.lab.aggregate-cardinality=200 \
--essentials.lab.slot-lag-sample-interval=PT2S \
--essentials.lab.metrics-output-file=$JSON_FILE" \
  spring-boot:run > "$OUT_DIR/scenario.log" 2>&1 || true

# Snapshot slots immediately after the JVM exits — the lab app's normal shutdown stops the
# tailer, which releases the advisory lock and closes the replication connection. The slot
# ITSELF persists (recreate-on-start drops it on the NEXT start, not on shutdown).
snapshot_slots post

# Wait the grace period; expect slot still present, active=false, inactive_seconds ≥ GRACE_S.
echo "[perf-lab] waiting ${GRACE_S}s to observe the orphaned slot's inactive_since growth"
sleep "$GRACE_S"
snapshot_slots after-grace

# Build a quick markdown summary so the operator can eyeball the lifecycle. The
# heredoc is single-quoted so Bash doesn't try to expand $-tokens inside f-strings;
# we pass the values we need via the environment instead.
OUT_DIR="$OUT_DIR" GRACE_S="$GRACE_S" python3 - <<'PY' > "$OUT_DIR/run-summary.md"
import json, os, pathlib
out_dir = pathlib.Path(os.environ["OUT_DIR"])
grace_s = int(os.environ["GRACE_S"])

def load(name):
    p = out_dir / f"slot-state-{name}.json"
    if not p.exists():
        return []
    raw = p.read_text().strip() or "[]"
    if raw == "[]" or raw == "":
        return []
    try:
        return json.loads(raw) or []
    except Exception:
        return []

pre   = load("pre")
post  = load("post")
after = load("after-grace")

def slot_row(snapshot):
    if not snapshot:
        return "(no essentials_* slot)"
    if isinstance(snapshot, list) and snapshot:
        s = snapshot[0]
        return (
            f"name={s.get('slot_name')} active={s.get('active')} "
            f"inactive_seconds={s.get('inactive_seconds')} wal_status={s.get('wal_status')}"
        )
    return "(unexpected snapshot shape)"

lines = ["# Orphaned-Slot Lifecycle", ""]
lines.append("| Phase | Slot state |")
lines.append("|---|---|")
lines.append(f"| pre (before JVM start) | {slot_row(pre)} |")
lines.append(f"| post (immediately after JVM exit) | {slot_row(post)} |")
lines.append(f"| after-grace ({grace_s}s later) | {slot_row(after)} |")
lines.append("")

ok_post  = bool(post)  and not post[0].get("active")
ok_after = (
    bool(after)
    and not after[0].get("active")
    and (after[0].get("inactive_seconds") or 0) >= grace_s - 5
)

lines.append("## Verdict")
lines.append("")
if ok_post and ok_after:
    lines.append(
        "Orphaned-slot lifecycle behaves as documented — slot persists, active=false, "
        "inactive_since grows."
    )
else:
    lines.append("Lifecycle didn't match expectations — investigate the snapshots above.")
lines.append("")
lines.append("## Operator action (if this slot is permanent garbage)")
lines.append("")
slot_name = post[0].get("slot_name") if post else "unknown"
lines.append("```sql")
lines.append("SELECT pg_drop_replication_slot('" + slot_name + "');")
lines.append("```")
lines.append("")
print("\n".join(lines))
PY

echo
cat "$OUT_DIR/run-summary.md"

if [[ "$CLEANUP" == "true" ]]; then
  echo "[perf-lab] CLEANUP=true — dropping any leftover essentials_* slots"
  docker exec "$CONTAINER_NAME" psql -U essentials -d essentials_lab -At -c \
    "SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots WHERE slot_name LIKE 'essentials\\_%'" || true
fi

echo "############# [perf-lab] orphaned-slot done #############"
echo "[perf-lab] output=$OUT_DIR"
