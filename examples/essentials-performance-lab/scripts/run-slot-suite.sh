#!/usr/bin/env bash
# Runs the full slot-validation scenario suite back-to-back and emits one combined
# summary listing each scenario's verdict and key signals. Intended as the single
# entry point for "validate slot handling end-to-end" — a successful run means all
# four slot invariants (lag-bounded, idle-push working, pause/recovery, poison
# isolation) hold under the configured workload.
#
# Output: target/slot-suite/<run-id>/<scenario>.json + suite-summary.{json,md}
#
# Tunable via env: PROFILE, RATE_HZ, AGGREGATE_CARDINALITY, SEED, plus per-scenario
# durations LAG_DURATION, IDLE_DURATION, PAUSE_DURATION, POISON_DURATION,
# POISON_COUNT, IDLE_PUSH_INTERVAL, METRICS_INTERVAL.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
LAB_DIR="$ROOT_DIR/examples/essentials-performance-lab"
OUT_ROOT="$LAB_DIR/target/slot-suite"
RUN_ID="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="$OUT_ROOT/$RUN_ID"
mkdir -p "$OUT_DIR"

PROFILE="${PROFILE:-compose}"
RATE_HZ="${RATE_HZ:-200}"
AGGREGATE_CARDINALITY="${AGGREGATE_CARDINALITY:-1000}"
SEED="${SEED:-42}"
PRODUCER_THREADS="${PRODUCER_THREADS:-2}"

LAG_DURATION="${LAG_DURATION:-PT60S}"
IDLE_DURATION="${IDLE_DURATION:-PT40S}"
PAUSE_DURATION="${PAUSE_DURATION:-PT60S}"
POISON_DURATION="${POISON_DURATION:-PT30S}"
POISON_COUNT="${POISON_COUNT:-100}"

IDLE_PUSH_INTERVAL="${IDLE_PUSH_INTERVAL:-PT5S}"
METRICS_INTERVAL="${METRICS_INTERVAL:-PT2S}"
SLOT_LAG_MAX_BYTES="${SLOT_LAG_MAX_BYTES:-104857600}"
SLOT_LAG_SAMPLE_INTERVAL="${SLOT_LAG_SAMPLE_INTERVAL:-PT2S}"

echo "############# [perf-lab] slot suite start #############"
echo "[perf-lab] run_id=$RUN_ID profile=$PROFILE output=$OUT_DIR"
echo "[perf-lab] rate_hz=$RATE_HZ threads=$PRODUCER_THREADS card=$AGGREGATE_CARDINALITY seed=$SEED"
echo "[perf-lab] idle_push=$IDLE_PUSH_INTERVAL metrics=$METRICS_INTERVAL"

run_scenario() {
  local id="$1"
  local scenario="$2"
  local duration="$3"
  local extra_args="$4"

  local json_file="$OUT_DIR/$id.json"
  local log_file="/tmp/perf-lab-slot-suite-$RUN_ID-$id.log"

  echo "[perf-lab] running scenario=$scenario id=$id duration=$duration"
  mvn -q -pl examples/essentials-performance-lab \
    -DskipTests \
    -Dspring-boot.run.profiles="$PROFILE" \
    -Dspring-boot.run.arguments="--essentials.lab.scenario=$scenario \
--essentials.eventstore.cdc.enabled=true \
--essentials.eventstore.cdc.wal-replication-tailer.idle-lsn-push-interval=$IDLE_PUSH_INTERVAL \
--essentials.eventstore.cdc.slot.metrics-interval=$METRICS_INTERVAL \
--essentials.lab.duration=$duration \
--essentials.lab.producer-threads=$PRODUCER_THREADS \
--essentials.lab.producer-rate-hz=$RATE_HZ \
--essentials.lab.aggregate-cardinality=$AGGREGATE_CARDINALITY \
--essentials.lab.random-seed=$SEED \
--essentials.lab.metrics-output-file=$json_file \
$extra_args" \
    spring-boot:run > "$log_file" 2>&1
  echo "[perf-lab]   verdict $(python3 -c "import json,sys;print(json.load(open(sys.argv[1])).get('verdict','?'))" "$json_file" 2>/dev/null || echo '?')"
}

run_scenario "slot-lag-bounded"       slot-lag-bounded       "$LAG_DURATION"    "--essentials.lab.slot-lag-max-bytes=$SLOT_LAG_MAX_BYTES --essentials.lab.slot-lag-sample-interval=$SLOT_LAG_SAMPLE_INTERVAL"
run_scenario "slot-idle-push"         slot-idle-push         "$IDLE_DURATION"   ""
run_scenario "consumer-pause-recovery" consumer-pause-recovery "$PAUSE_DURATION" ""
run_scenario "poison-flood"           poison-flood           "$POISON_DURATION" "--essentials.lab.poison-flood-count=$POISON_COUNT"

OUT_DIR="$OUT_DIR" python3 - <<'PY'
import json
import os
import pathlib

out_dir = pathlib.Path(os.environ["OUT_DIR"])

# Each scenario's JSON has its own shape; pick out the highest-signal fields per scenario for
# the summary so the operator gets one screen with everything they need.
def load(name):
    p = out_dir / f"{name}.json"
    if not p.exists():
        return None
    return json.loads(p.read_text())

scenarios = {
    "slot-lag-bounded":       load("slot-lag-bounded"),
    "slot-idle-push":         load("slot-idle-push"),
    "consumer-pause-recovery": load("consumer-pause-recovery"),
    "poison-flood":           load("poison-flood"),
}

def fmt_bytes(n):
    if n is None: return "-"
    units = ["B","KiB","MiB","GiB"]
    f = float(n)
    for u in units:
        if f < 1024.0: return f"{f:.1f} {u}"
        f /= 1024.0
    return f"{f:.1f} TiB"

rows = []
for name, data in scenarios.items():
    if data is None:
        rows.append({"scenario": name, "verdict": "MISSING", "summary": "scenario JSON not produced"})
        continue
    verdict = data.get("verdict", "?")
    if name == "slot-lag-bounded":
        summary = (f"lagMax={fmt_bytes(data.get('lagBytesMax'))}, "
                   f"lagEnd={fmt_bytes(data.get('lagBytesEnd'))}, "
                   f"walStatus={data.get('walStatusEnd')}, "
                   f"drift={data.get('frameworkVsPgDriftPct'):.2f}%" if isinstance(data.get('frameworkVsPgDriftPct'), (int,float)) else "drift=n/a")
    elif name == "slot-idle-push":
        summary = (f"preLsn={data.get('pre',{}).get('confirmedFlushLsn')}, "
                   f"postLsn={data.get('post',{}).get('confirmedFlushLsn')}, "
                   f"advanced={data.get('confirmedFlushLsnAdvanced')}, "
                   f"idlePushObserved={data.get('idlePushObserved')}")
    elif name == "consumer-pause-recovery":
        summary = (f"backlogPauseStart={data.get('backlogAtPauseStart')}, "
                   f"peak={data.get('peakBacklog')}, "
                   f"final={data.get('finalBacklog')}, "
                   f"dispatcherRestarted={data.get('dispatcherRestartedCleanly')}")
    elif name == "poison-flood":
        summary = (f"injected={data.get('poisonInjected')}, "
                   f"poisonRows={data.get('poisonRowsAtEnd')}, "
                   f"validProduced={data.get('producedValidEvents')}, "
                   f"validDelivered={data.get('deliveredValidEvents')}")
    else:
        summary = "n/a"
    rows.append({"scenario": name, "verdict": verdict, "summary": summary})

(out_dir / "suite-summary.json").write_text(json.dumps(rows, indent=2) + "\n")

lines = ["# Slot Validation Suite Summary", ""]

all_pass = all(r["verdict"] == "PASS" for r in rows)
if all_pass:
    lines.append("✅ **All four slot scenarios PASSED.** Slot handling validated end-to-end.")
else:
    lines.append("❌ **One or more scenarios failed or did not complete.** See the table below.")
lines.append("")
lines.append("| scenario | verdict | summary |")
lines.append("|---|---|---|")
for r in rows:
    lines.append(f"| `{r['scenario']}` | {r['verdict']} | {r['summary']} |")

(out_dir / "suite-summary.md").write_text("\n".join(lines) + "\n")

print(f"[perf-lab] wrote {out_dir / 'suite-summary.json'}")
print(f"[perf-lab] wrote {out_dir / 'suite-summary.md'}")
PY

echo "############# [perf-lab] slot suite done #############"
echo "[perf-lab] output=$OUT_DIR"
