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
# Switches on the repository's tracked git hooks (.githooks/) for this clone. Run once per clone; safe to re-run.
# The devcontainer runs it from post-create.sh.
#
# Writes a small .git/hooks/<name> that runs the tracked .githooks/<name>, instead of pointing core.hooksPath at
# .githooks/: graphify follows core.hooksPath, and `graphify hook install` would then drop its machine-specific
# post-commit/post-checkout hooks into the tracked directory. This way both sets of hooks live side by side.
#
# A hook of the same name that this script did not write is left alone and reported; merge it by hand.

set -eu

root=$(git rev-parse --show-toplevel)
hooks=$(git rev-parse --git-path hooks)
marker="# installed by scripts/install-git-hooks.sh"
mkdir -p "$hooks"

for tracked in "$root"/.githooks/*; do
    name=$(basename "$tracked")
    hook="$hooks/$name"
    if [ -e "$hook" ] && ! grep -qF "$marker" "$hook"; then
        echo "install-git-hooks: $hook exists and was not written by this script; left unchanged" >&2
        continue
    fi
    cat > "$hook" <<EOF
#!/bin/sh
$marker
exec sh "\$(git rev-parse --show-toplevel)/.githooks/$name" "\$@"
EOF
    chmod +x "$hook"
    echo "install-git-hooks: $hook -> .githooks/$name"
done
