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
#
# A copy rather than a symlink because Git for Windows checks a symlink out as a text file by default, which
# would leave the installed plugin with no docs and no error.
#
# Usage:
#   scripts/sync-plugin-llm.sh    # from anywhere inside the repository
#
# The pre-commit hook (.githooks/pre-commit) does the same from the index on every commit that touches LLM/,
# and the CI drift gate in .github/workflows/maven.yml runs this script and fails on any difference.
# POSIX sh on purpose: the build runs on a JVM alone, and this also has to run in Git Bash on Windows.

set -eu

cd "$(git rev-parse --show-toplevel)"
src=LLM
dst=essentials-plugin/references/llm

[ -d "$src" ] || { echo "sync-plugin-llm: no $src/ directory" >&2; exit 1; }
mkdir -p "$dst"

find "$src" -type f | while IFS= read -r file; do
    target="$dst/${file#"$src"/}"
    if ! cmp -s "$file" "$target"; then
        mkdir -p "$(dirname "$target")"
        cp "$file" "$target"
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
