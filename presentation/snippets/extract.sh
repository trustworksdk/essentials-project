#!/usr/bin/env bash
# Extracts every code snippet used by presentation/deck.html verbatim from its real source.
#
# Re-run this before the talk. If a source file has moved or been renamed the script fails loudly
# instead of leaving a stale snippet on a slide.
#
# Usage:  presentation/snippets/extract.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="$REPO_ROOT/presentation/snippets"
DEMO="$REPO_ROOT/examples/essentials-trading-demo/src/main/java/dk/trustworks/essentials/examples/trading"

copy() {
  local src="$1" dst="$2"
  if [[ ! -f "$src" ]]; then
    echo "MISSING SOURCE: $src" >&2
    exit 1
  fi
  cp "$src" "$OUT/$dst"
  echo "  $dst  <-  ${src#"$REPO_ROOT"/}"
}

echo "Extracting verbatim snippets into presentation/snippets/"

# --- Act 1: the register_instrument slice ------------------------------------
copy "$DEMO/market_data/use_cases/register_instrument/slice.yaml"                 "01-register_instrument.slice.yaml"
copy "$DEMO/market_data/use_cases/register_instrument/RegisterInstrument.java"    "02-RegisterInstrument.java"
copy "$DEMO/market_data/use_cases/register_instrument/RegisterInstrumentHandler.java" "03-RegisterInstrumentHandler.java"
copy "$DEMO/market_data/use_cases/register_instrument/RegisterInstrumentAPI.java" "04-RegisterInstrumentAPI.java"

# --- Act 2: the aggregate ----------------------------------------------------
copy "$DEMO/market_data/aggregates/Instrument.java"                              "05-Instrument.java"
copy "$DEMO/market_data/events/InstrumentRegistered.java"                         "06-InstrumentRegistered.java"

# --- Act 3: components ------------------------------------------------------
copy "$DEMO/market_data/types/InstrumentId.java"                                 "07-InstrumentId.java"
copy "$DEMO/market_data/types/Symbol.java"                                        "08-Symbol.java"
copy "$DEMO/market_data/aggregates/Instruments.java"                              "09-Instruments.java"
copy "$DEMO/market_data/aggregates/InstrumentPrice.java"                          "10-InstrumentPrice.java"
copy "$DEMO/market_data/config/MarketDataConfiguration.java"                      "11-MarketDataConfiguration.java"
copy "$DEMO/market_data/views/instrument_details/InstrumentDetailsProjection.java" "12-InstrumentDetailsProjection.java"
copy "$DEMO/market_data/views/latest_price/LatestPriceQuery.java"                 "13-LatestPriceQuery.java"
copy "$DEMO/brokerage/types/TradingAccountGenerationId.java"                      "14-TradingAccountGenerationId.java"
copy "$DEMO/market_data/types/MarketDataAggregateTypes.java"                      "15-MarketDataAggregateTypes.java"

echo
echo "Framework-level snippets (UnitOfWork, DurableQueues, FencedLock, Outbox, Decider)"
echo "are maintained by hand in 90-framework-snippets.md, quoting LLM/LLM-foundation.md"
echo "and LLM/LLM-eventsourced-aggregates.md. Re-check them against those docs."
echo
echo "Done. See MANIFEST.md for the snippet-to-slide mapping."
