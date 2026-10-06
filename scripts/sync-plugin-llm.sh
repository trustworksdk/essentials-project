#!/bin/sh
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
# Mirrors LLM/ into essentials-plugin/references/llm/, the plugin's generated copy of the framework docs.
# LLM/ is the only place the docs are edited; files that no longer exist there are deleted from the copy.
# Links that leave LLM/ (`](../components/…)`, `](../docs/…)`) are rewritten to GitHub URLs on the way: the installed
# plugin holds only essentials-plugin/, so a relative link out of the copy would resolve to nothing. The URLs point at
# the release tag the plugin targets — the `essentials.version` pin in stack-pins.md (tags are bare versions, `0.60.0`) —
# not at main, which may not have a file yet or may have moved past the release. Until that tag is pushed they 404.
#
# A copy rather than a symlink because Git for Windows checks a symlink out as a text file by default, which
# would leave the installed plugin with no docs and no error.
#
# Usage:
#   scripts/sync-plugin-llm.sh             # from anywhere inside the repository
#   scripts/sync-plugin-llm.sh --filter    # stdin -> stdout, the per-file rewrite only (used by the hook); reads
#                                          # the pin from the index, as the hook regenerates what is being committed
#
# The pre-commit hook (.githooks/pre-commit) does the same from the index on every commit that touches LLM/,
# and the CI drift gate in .github/workflows/maven.yml runs this script and fails on any difference.
# POSIX sh on purpose: the build runs on a JVM alone, and this also has to run in Git Bash on Windows.

set -eu

pins=essentials-plugin/references/stack/stack-pins.md

# The pin from stack-pins.md on stdin. Same extraction as essentials_pin in scripts/plugin-check.sh (the plugin-version step).
pin_version() {
    sed -n 's/^| `essentials.version` | \*\*\([^*]*\)\*\*.*/\1/p'
}

# Fails rather than write links to blob/ with no tag. The character check also keeps $ref safe inside the sed script.
check_ref() {
    case $ref in
        '' | *[!0-9A-Za-z.-]*) echo "sync-plugin-llm: no usable essentials.version pin in $pins: '$ref'" >&2; exit 1 ;;
    esac
}

rewrite_links() {
    sed "s#](\.\./#](https://github.com/trustworksdk/essentials-project/blob/$ref/#g"
}

if [ "${1:-}" = "--filter" ]; then
    ref=$(git show ":$pins" | pin_version)
    check_ref
    rewrite_links
    exit 0
fi

cd "$(git rev-parse --show-toplevel)"
ref=$(pin_version < "$pins")
check_ref
src=LLM
dst=essentials-plugin/references/llm

[ -d "$src" ] || { echo "sync-plugin-llm: no $src/ directory" >&2; exit 1; }
mkdir -p "$dst"

find "$src" -type f | while IFS= read -r file; do
    target="$dst/${file#"$src"/}"
    mkdir -p "$(dirname "$target")"
    rewrite_links < "$file" > "$target.sync-tmp"
    if cmp -s "$target.sync-tmp" "$target"; then
        rm -f "$target.sync-tmp"
    else
        mv "$target.sync-tmp" "$target"
        echo "updated $target"
    fi
done

find "$dst" -type f | while IFS= read -r copy; do
    if [ ! -f "$src/${copy#"$dst"/}" ]; then
        rm -f "$copy"
        echo "deleted $copy"
    fi
done
find "$dst" -mindepth 1 -type d -empty -delete
