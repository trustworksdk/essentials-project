#!/usr/bin/env bash
# The new event store engine (design phase 8, unit 8b-E) beside the old engine's polling, notify-polling, CDC inbox and
# CDC direct, over the same four load profiles as run-baseline-matrix.sh. Each case runs the opt-in
# event-log-vs-polling-vs-cdc-compare scenario (six child legs) and writes one comparison JSON; the summary table
# lists every leg of every case.
# The scenario arguments include --spring.main.web-application-type=none: the lab carries spring-boot-starter-web for
# the chaos profile, and its Tomcat would keep the JVM alive after the scenario, so the next case would never start.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
LAB_DIR="$ROOT_DIR/examples/essentials-performance-lab"
OUT_DIR="${OUT_DIR:-$LAB_DIR/target/event-log-matrix}"
mkdir -p "$OUT_DIR"

WARMUP="${WARMUP:-PT5S}"
DURATION="${DURATION:-PT20S}"
PRODUCER_THREADS="${PRODUCER_THREADS:-4}"
SEED="${SEED:-42}"
PROFILE="${PROFILE:-compose}"
MVN_OPTS="${MVN_OPTS:-}"
# extra scenario arguments, e.g. EXTRA_ARGS="--essentials.lab.event-log.pool-size=10"
EXTRA_ARGS="${EXTRA_ARGS:-}"

# id|subscriber_count|aggregate_cardinality (the profiles of run-baseline-matrix.sh)
CASES=(
  "s1_card5000|1|5000"
  "s1_card50|1|50"
  "s5_card5000|5|5000"
  "s5_card50|5|50"
)
# CUSTOM_CASES="id|subs|card;id|subs|card" replaces the list
if [[ -n "${CUSTOM_CASES:-}" ]]; then
  IFS=';' read -r -a CASES <<< "$CUSTOM_CASES"
fi

printf "[perf-lab] event-log matrix start warmup=%s duration=%s producer_threads=%s seed=%s\n" "$WARMUP" "$DURATION" "$PRODUCER_THREADS" "$SEED"

cd "$ROOT_DIR"
for c in "${CASES[@]}"; do
  IFS='|' read -r ID SUBS CARD <<< "$c"
  JSON_FILE="$OUT_DIR/$ID.json"
  LOG_FILE="$OUT_DIR/$ID.log"
  printf "[perf-lab] case=%s subs=%s card=%s\n" "$ID" "$SUBS" "$CARD"
  # shellcheck disable=SC2086
  mvn -q $MVN_OPTS -pl examples/essentials-performance-lab \
    -DskipTests \
    -Dspring-boot.run.profiles="$PROFILE" \
    -Dspring-boot.run.arguments="--spring.main.web-application-type=none --essentials.lab.event-log.enabled=true --essentials.lab.scenario=event-log-vs-polling-vs-cdc-compare --essentials.eventstore.cdc.enabled=false --essentials.lab.subscriber-count=$SUBS --essentials.lab.aggregate-cardinality=$CARD --essentials.lab.producer-threads=$PRODUCER_THREADS --essentials.lab.random-seed=$SEED --essentials.lab.warmup=$WARMUP --essentials.lab.duration=$DURATION --essentials.lab.metrics-output-file=$JSON_FILE $EXTRA_ARGS" \
    spring-boot:run > "$LOG_FILE" 2>&1
done

OUT_DIR="$OUT_DIR" python3 - <<'PY'
import json, os, pathlib
out_dir = pathlib.Path(os.environ["OUT_DIR"])
legs = ["polling", "notifyPolling", "cdcInbox", "cdcDirect", "eventLogReactor", "eventLogProjection"]
lines = ["| case | leg | mode | append eps | delivery eps | p50 ms | p95 ms | p99 ms | catch-up ms | lag end | selects/s | engine stmts/delivery |",
         "|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
for p in sorted(out_dir.glob("*.json")):
    if p.name == "summary.json":
        continue
    d = json.loads(p.read_text())
    for leg in legs:
        r = d.get(leg, {})
        stmts = r.get("engineStatementsPerDeliveredEvent")
        lines.append(f"| {p.stem} | {leg} | {r.get('mode')} | {r.get('appendEventsPerSecond', 0):.1f} | {r.get('deliveredEventsPerSecond', 0):.1f} | "
                     f"{r.get('p50LatencyMs', 0):.1f} | {r.get('p95LatencyMs', 0):.1f} | {r.get('p99LatencyMs', 0):.1f} | {r.get('timeToCatchUpMs')} | "
                     f"{r.get('deliveryLagEventsEnd')} | {r.get('eventStoreSelectsPerSecond', 0):.1f} | {'' if stmts is None else f'{stmts:.2f}'} |")
(out_dir / "summary.md").write_text("\n".join(lines) + "\n")
print("\n".join(lines))
print("[perf-lab] wrote", out_dir / "summary.md")
PY
echo "[perf-lab] event-log matrix complete"
