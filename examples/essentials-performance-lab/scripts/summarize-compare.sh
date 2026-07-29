#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
DEFAULT_JSON="$(ls -t "$ROOT_DIR"/examples/essentials-performance-lab/target/baseline-compare*.json 2>/dev/null | head -n 1 || true)"
JSON_FILE="${1:-$DEFAULT_JSON}"

if [[ -z "${JSON_FILE:-}" || ! -f "$JSON_FILE" ]]; then
  echo "Usage: $0 <compare-json-file>"
  echo "Example: $0 examples/essentials-performance-lab/target/baseline-compare-3way-bytes-long-after-directfix.json"
  exit 1
fi

python3 - "$JSON_FILE" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1]).resolve()
data = json.loads(path.read_text())

polling = data.get("polling", {})
inbox = data.get("cdcInbox", data.get("cdc", {}))
direct = data.get("cdcDirect", {})
delta_inbox = data.get("deltaInbox", data.get("delta", {}))
delta_direct = data.get("deltaDirect", {})

def f(value, digits=2):
    try:
        return f"{float(value):.{digits}f}"
    except Exception:
        return "n/a"

def num(value):
    try:
        return float(value)
    except Exception:
        return float("nan")

def mode_of(x):
    return str(x.get("mode", "unknown"))

def cdc_state_of(x):
    cdc = x.get("cdc") or {}
    return str(cdc.get("state", "n/a"))

print("############# [perf-lab] Compare Summary #############")
print(f"[perf-lab] file={path}")
print("[perf-lab] metrics: append_eps=producer throughput, delivery_eps=subscriber throughput, p95_ms=append->delivery p95 latency (lower is better)")
print(f"[perf-lab] polling   append_eps={f(polling.get('appendEventsPerSecond'))} delivery_eps={f(polling.get('deliveredEventsPerSecond'))} p95_ms={f(polling.get('p95LatencyMs'))}")
print(f"[perf-lab] cdcInbox  append_eps={f(inbox.get('appendEventsPerSecond'))} delivery_eps={f(inbox.get('deliveredEventsPerSecond'))} p95_ms={f(inbox.get('p95LatencyMs'))}")
print(f"[perf-lab] cdcDirect append_eps={f(direct.get('appendEventsPerSecond'))} delivery_eps={f(direct.get('deliveredEventsPerSecond'))} p95_ms={f(direct.get('p95LatencyMs'))}")
print(f"[perf-lab] polling   sla_1000ms_pct={f(polling.get('slaUnder1000msPct'))} first_delivery_ms={f(polling.get('timeToFirstDeliveryMs'), 0)} catchup_ms={f(polling.get('timeToCatchUpMs'), 0)}")
print(f"[perf-lab] cdcInbox  sla_1000ms_pct={f(inbox.get('slaUnder1000msPct'))} first_delivery_ms={f(inbox.get('timeToFirstDeliveryMs'), 0)} catchup_ms={f(inbox.get('timeToCatchUpMs'), 0)}")
print(f"[perf-lab] cdcDirect sla_1000ms_pct={f(direct.get('slaUnder1000msPct'))} first_delivery_ms={f(direct.get('timeToFirstDeliveryMs'), 0)} catchup_ms={f(direct.get('timeToCatchUpMs'), 0)}")
print(f"[perf-lab] deltaInbox  append_eps={f(delta_inbox.get('appendEventsPerSecondDiff'))} delivery_eps={f(delta_inbox.get('deliveredEventsPerSecondDiff'))} p95_ms={f(delta_inbox.get('p95LatencyMsDiff'))}")
print(f"[perf-lab] deltaDirect append_eps={f(delta_direct.get('appendEventsPerSecondDiff'))} delivery_eps={f(delta_direct.get('deliveredEventsPerSecondDiff'))} p95_ms={f(delta_direct.get('p95LatencyMsDiff'))}")
print(f"[perf-lab] deltaInbox  sla_1000ms_pct={f(delta_inbox.get('slaUnder1000msPctDiff'))} first_delivery_ms={f(delta_inbox.get('timeToFirstDeliveryMsDiff'), 0)} catchup_ms={f(delta_inbox.get('timeToCatchUpMsDiff'), 0)}")
print(f"[perf-lab] deltaDirect sla_1000ms_pct={f(delta_direct.get('slaUnder1000msPctDiff'))} first_delivery_ms={f(delta_direct.get('timeToFirstDeliveryMsDiff'), 0)} catchup_ms={f(delta_direct.get('timeToCatchUpMsDiff'), 0)}")

invalid_reasons = []
if mode_of(inbox) != "cdc-active":
    invalid_reasons.append(f"cdcInbox mode={mode_of(inbox)}")
if mode_of(direct) != "cdc-active":
    invalid_reasons.append(f"cdcDirect mode={mode_of(direct)}")
if cdc_state_of(inbox) not in ("ACTIVE", "n/a"):
    invalid_reasons.append(f"cdcInbox state={cdc_state_of(inbox)}")
if cdc_state_of(direct) not in ("ACTIVE", "n/a"):
    invalid_reasons.append(f"cdcDirect state={cdc_state_of(direct)}")

if invalid_reasons:
    print("############# [perf-lab] INVALID RUN #############")
    for reason in invalid_reasons:
        print(f"[perf-lab] reason={reason}")
    print("############# [perf-lab] #########################")
    sys.exit(2)

append = {
    "polling": num(polling.get("appendEventsPerSecond")),
    "cdcInbox": num(inbox.get("appendEventsPerSecond")),
    "cdcDirect": num(direct.get("appendEventsPerSecond")),
}
delivery = {
    "polling": num(polling.get("deliveredEventsPerSecond")),
    "cdcInbox": num(inbox.get("deliveredEventsPerSecond")),
    "cdcDirect": num(direct.get("deliveredEventsPerSecond")),
}
latency = {
    "polling": num(polling.get("p95LatencyMs")),
    "cdcInbox": num(inbox.get("p95LatencyMs")),
    "cdcDirect": num(direct.get("p95LatencyMs")),
}

best_append = max(append, key=append.get)
best_delivery = max(delivery, key=delivery.get)
best_latency = min(latency, key=latency.get)
print(f"[perf-lab] winner append_eps={best_append} delivery_eps={best_delivery} p95_ms={best_latency}")
print("############# [perf-lab] ################################")
PY
