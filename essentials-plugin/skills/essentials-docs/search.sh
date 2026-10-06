#!/usr/bin/env bash
# Keyword search over the LLM-tailored Trustworks Essentials docs and the plugin's design guide.
#
# Usage:
#   search.sh <query>           # search across all LLM-*.md files and references/design/
#   search.sh -l <query>        # list matching files only (no context lines)
#   search.sh -t <topic> <query>  # restrict to LLM-<topic>.md (e.g., -t foundation, -t types),
#                                 # or to the design guide with -t design
#   search.sh -- <query>        # a query that starts with - (e.g., -parameters)
#
# Prefers ripgrep; falls back to grep -R. Exits 1 if no matches.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
DOCS_DIR="${SCRIPT_DIR}/../../references/llm"
DESIGN_DIR="${SCRIPT_DIR}/../../references/design"

if [[ ! -d "$DOCS_DIR" ]]; then
  echo "search.sh: docs directory not found: $DOCS_DIR" >&2
  exit 2
fi

list_only=0
topic=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -l) list_only=1; shift ;;
    -t) topic="$2"; shift 2 ;;
    --) shift; break ;;
    -*) echo "search.sh: unknown flag $1" >&2; exit 2 ;;
    *) break ;;
  esac
done

if [[ $# -lt 1 ]]; then
  echo "Usage: search.sh [-l] [-t <topic>] <query>" >&2
  echo "  -l            list matching files only" >&2
  echo "  -t <topic>    restrict to LLM-<topic>.md (e.g., foundation, types, postgresql-event-store)," >&2
  echo "                or to the design guide with -t design" >&2
  exit 2
fi

query="$*"

if [[ "$topic" == "design" ]]; then
  target="${DESIGN_DIR}/essentials-design.md"
  if [[ ! -f "$target" ]]; then
    echo "search.sh: design guide not found: $target" >&2
    exit 2
  fi
  paths=("$target")
elif [[ -n "$topic" ]]; then
  target="${DOCS_DIR}/LLM-${topic}.md"
  if [[ ! -f "$target" ]]; then
    echo "search.sh: no doc file for topic '$topic' (looked for $target)" >&2
    echo "Available topics:" >&2
    ls "$DOCS_DIR" | sed -n 's/^LLM-\(.*\)\.md$/  \1/p' >&2
    echo "  design" >&2
    exit 2
  fi
  paths=("$target")
else
  paths=("$DOCS_DIR")
  if [[ -d "$DESIGN_DIR" ]]; then
    paths+=("$DESIGN_DIR")
  fi
fi

if command -v rg >/dev/null 2>&1; then
  if [[ $list_only -eq 1 ]]; then
    rg --files-with-matches --no-ignore-vcs --color=never -i -e "$query" "${paths[@]}"
  else
    rg --no-heading --line-number --color=never --max-count=10 -i -C 2 -e "$query" "${paths[@]}"
  fi
else
  if [[ $list_only -eq 1 ]]; then
    grep -RIl --color=never -i -e "$query" "${paths[@]}"
  else
    grep -RIn --color=never -i -C 2 -e "$query" "${paths[@]}" | sed -n '1,200p'
  fi
fi
