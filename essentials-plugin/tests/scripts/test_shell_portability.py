"""Static guard: the shell scripts that run on a user's or a maintainer's Mac stay bash 3.2 and BSD-userland safe.

Run from the plugin root:  python3 -m unittest discover -s tests/scripts -p 'test_shell_portability.py'

macOS ships /bin/bash 3.2.57 and the BSD tools, and the devcontainer and CI have neither, so a bash 4 feature or a
GNU-only flag passes every other test here and fails only on a Mac. This test cannot run bash 3.2; it greps for the
constructs known to break there. A hit is either fixed or, where the line is correct on both, marked with a trailing
`# portable: <why>` comment, which this test accepts.

Scripts that deliberately need bash 4.4 (scripts/plugin-check.sh, scripts/plugin-scaffold.sh, maintainer tooling) are
not scanned; they must instead stop with a clear message on an older bash, and that guard is checked here.

Standard library only.
"""

from __future__ import annotations

import re
import shutil
import subprocess
import unittest
from pathlib import Path

PLUGIN = Path(__file__).resolve().parents[2]
REPO = PLUGIN.parent

# Run on a Mac: in user projects (doctor.sh, the rendered dev.sh and pre-commit hook, search.sh) or by a maintainer
# (the eval scaffolds, the LLM sync and its hook, the hook installer).
PORTABLE = [
    PLUGIN / "scripts" / "doctor.sh",
    PLUGIN / "skills" / "essentials-docs" / "search.sh",
    PLUGIN / "references" / "init-assets" / "project" / "dev.sh.template",
    PLUGIN / "references" / "init-assets" / "project" / ".githooks" / "pre-commit.template",
    PLUGIN / "evals" / "_lib" / "stage.sh",
    *sorted((PLUGIN / "evals").glob("*/scaffold.sh")),
    REPO / "scripts" / "sync-plugin-llm.sh",
    REPO / "scripts" / "install-git-hooks.sh",
    REPO / ".githooks" / "pre-commit",
]

# Scripts that need bash 4.4 and must say so instead of misbehaving.
NEEDS_BASH_44 = [REPO / "scripts" / "plugin-check.sh", REPO / "scripts" / "plugin-scaffold.sh"]

# (pattern, why) for every bash script. Matched against a line with its comment and single-quoted text stripped.
BASH_FORBIDDEN = [
    (r"\bdeclare\s+-[a-zA-Z]*[AngG]", "declare -A/-n/-g: bash 4+"),
    (r"\blocal\s+-[a-zA-Z]*n", "local -n nameref: bash 4.3+"),
    (r"\b(mapfile|readarray)\b", "mapfile/readarray: bash 4+"),
    (r"\$\{[#!]?[A-Za-z_][A-Za-z0-9_]*(\[[^]]*\])?(,,?|\^\^?|@[QEPAaUuLK])\}", "case modification / @ transform: bash 4+"),
    (r"\[\[\s+-v\s", "[[ -v var ]]: bash 4.2+"),
    (r"\$\{[A-Za-z_][A-Za-z0-9_]*\[-[0-9]+\]\}", "negative array index: bash 4.3+"),
    (r"&>>", "&>>: bash 4+"),
    (r"\|&", "|&: bash 4+"),
    (r"\bcoproc\b", "coproc: bash 4+"),
    (r"\bwait\s+-n\b", "wait -n: bash 4.3+"),
    (r"\b(globstar|lastpipe|inherit_errexit)\b", "shopt option: bash 4+"),
    (r"\bEPOCH(SECONDS|REALTIME)\b", "$EPOCHSECONDS/$EPOCHREALTIME: bash 5+"),
    (r"%\([^)]*\)T", "printf %(...)T: bash 4.2+"),
    (r"\bread\b[^|;&]*\s-i\b", "read -i: bash 4+"),
    (r";;&|;&\s*$", "case fall-through: bash 4+"),
]

# GNU-only tools and flags, for every script.
TOOL_FORBIDDEN = [
    (r"\bsed\s+(-[a-zA-Z]*\s+)*-i(\s|$)", "sed -i with no suffix: BSD sed takes the next argument as the suffix"),
    (r"\bsed\s+(-[a-zA-Z]*\s+)*-r\b", "sed -r: GNU only; use -E"),
    (r"\bgrep\s+(-[a-zA-Z]*\s+)*-[a-zA-Z]*P", "grep -P: GNU only"),
    (r"\breadlink\s+-f\b", "readlink -f: absent before macOS 12.3"),
    (r"\brealpath\b", "realpath: absent before macOS 13"),
    (r"\bdate\s+(-[a-zA-Z]*\s+)*-d\b", "date -d: GNU only"),
    (r"\bstat\s+(-[a-zA-Z]*\s+)*-c\b", "stat -c: GNU only (BSD: stat -f)"),
    (r"-printf\b", "find -printf: GNU only"),
    (r"\bxargs\s+(-[a-zA-Z0-9]*\s+)*-[a-zA-Z]*r\b", "xargs -r: GNU only"),
    (r"\bxargs\s+(-[a-zA-Z0-9]*\s+)*-d\b", "xargs -d: GNU only"),
    (r"\btimeout\s", "timeout: not on macOS"),
    (r"\btac\b", "tac: not on macOS"),
    (r"\bnproc\b", "nproc: not on macOS (sysctl -n hw.ncpu, getconf _NPROCESSORS_ONLN)"),
    (r"\bhead\s+(-[a-zA-Z]*\s+)*-n\s*-[0-9]", "head -n -N: GNU only"),
    (r"\bdu\s+(-[a-zA-Z]*\s+)*-[a-zA-Z]*b", "du -b: GNU only"),
    (r"\bbase64\s+(-[a-zA-Z]*\s+)*-w\b", "base64 -w: GNU only"),
    (r"\b(md5sum|sha1sum|sha256sum)\b", "*sum tools: not on macOS (shasum -a 256)"),
    (r"\bln\s+(-[a-zA-Z]*\s+)*-[a-zA-Z]*T", "ln -T: GNU only"),
    (r"\bgetopt\s", "getopt: BSD getopt has no long options"),
    (r"\bsort\s+(-[a-zA-Z]*\s+)*-[a-zA-Z]*V", "sort -V: missing on older macOS"),
]

# Bashisms a #!/bin/sh script must not use (dash and macOS's sh, which is bash in POSIX mode, differ on them).
SH_FORBIDDEN = [
    (r"\[\[", "[[ ]]: not POSIX"),
    (r"^\s*[A-Za-z_][A-Za-z0-9_]*\+?=\(", "arrays: not POSIX"),
    (r"\$'", "$'...' quoting: not POSIX"),
    (r"<<<", "here-string: not POSIX"),
    (r"<\(|>\(", "process substitution: not POSIX"),
    (r"^\s*function\s", "function keyword: not POSIX"),
    (r"&>", "&>: not POSIX"),
]

ALLOW = re.compile(r"#\s*portable:")
HEREDOC = re.compile(r"<<-?\s*(['\"]?)([A-Za-z_]+)\1")


def code_lines(path: Path) -> list[tuple[int, str]]:
    """(number, line) for each line that is code: comments, here-document bodies and template directives left out."""
    out: list[tuple[int, str]] = []
    heredoc: str | None = None
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if heredoc is not None:
            if raw.strip() == heredoc:
                heredoc = None
            continue
        match = HEREDOC.search(raw)
        if match:
            heredoc = match.group(2)
        if ALLOW.search(raw) or raw.lstrip().startswith(("#", "<!--")):
            continue
        line = re.sub(r"(^|\s)#.*$", "", raw)
        line = re.sub(r"'[^']*'", "''", line)  # a single-quoted sed/grep/jq program holds no command
        out.append((number, line))
    return out


def shebang(path: Path) -> str:
    return path.read_text(encoding="utf-8").split("\n", 1)[0]


def hits(path: Path, rules: list[tuple[str, str]]) -> list[str]:
    found = []
    for number, line in code_lines(path):
        for pattern, why in rules:
            if re.search(pattern, line):
                found.append(f"{path.relative_to(REPO)}:{number}: {why}: {line.strip()}")
    return found


def empty_array_expansions(path: Path) -> list[str]:
    """`"${a[@]}"` of an array the script initialises empty (`a=()`): unbound under `set -u` in bash before 4.4."""
    text = path.read_text(encoding="utf-8")
    if not re.search(r"^\s*set\s+-[a-zA-Z]*u", text, re.MULTILINE):
        return []
    empty = set(re.findall(r"(?:^|\s)([A-Za-z_][A-Za-z0-9_]*)=\(\)", text, re.MULTILINE))
    found = []
    for number, line in code_lines(path):
        for name in sorted(empty):
            guarded = re.search(r"\$\{" + name + r"\[[@*]\]\+", line)
            if re.search(r"\$\{" + name + r"\[[@*]\]\}", line) and not guarded:
                found.append(f"{path.relative_to(REPO)}:{number}: empty array '{name}' expanded under set -u "
                             "(unbound in bash < 4.4; use ${a[@]+\"${a[@]}\"})")
    return found


class ShellPortabilityTest(unittest.TestCase):
    def test_the_scripts_exist(self) -> None:
        for path in PORTABLE + NEEDS_BASH_44:
            self.assertTrue(path.is_file(), path)

    def test_no_bash_4_feature_or_gnu_only_tool(self) -> None:
        problems: list[str] = []
        for path in PORTABLE:
            first = shebang(path)
            if first == "#!/bin/sh":
                problems += hits(path, SH_FORBIDDEN + TOOL_FORBIDDEN)
            else:
                self.assertIn(first, ("#!/usr/bin/env bash", "#!/bin/bash"), path)
                problems += hits(path, BASH_FORBIDDEN + TOOL_FORBIDDEN) + empty_array_expansions(path)
        self.assertEqual(problems, [], "\n".join(problems))

    def test_bash_44_scripts_refuse_an_older_bash(self) -> None:
        for path in NEEDS_BASH_44:
            with self.subTest(script=path.name):
                text = path.read_text(encoding="utf-8")
                guard = text.find('"${BASH_VERSINFO[0]}" -lt 4')
                self.assertGreater(guard, 0, f"{path.name}: no bash version guard")
                first_use = re.search(r"^(?![ \t]*#).*\b(declare -A|mapfile)\b", text, re.MULTILINE)
                assert first_use, f"{path.name}: no bash 4 construct left; move it to PORTABLE"
                first_use = first_use.start()
                self.assertLess(guard, first_use, f"{path.name}: the guard comes after the first bash 4 construct")
                self.assertIn("brew install bash", text, f"{path.name}: the guard names no fix for macOS")

    def test_the_rules_catch_what_they_name(self) -> None:
        samples = {
            "declare -A x=()": BASH_FORBIDDEN, "mapfile -t a < f": BASH_FORBIDDEN, 'echo "${v,,}"': BASH_FORBIDDEN,
            "wait -n": BASH_FORBIDDEN, 'sed -i "s/a/b/" f': TOOL_FORBIDDEN, "readlink -f x": TOOL_FORBIDDEN,
            "find . -printf x": TOOL_FORBIDDEN, "[[ -n x ]]": SH_FORBIDDEN,
        }
        for sample, rules in samples.items():
            with self.subTest(sample=sample):
                self.assertTrue(any(re.search(p, sample) for p, _ in rules), sample)
        for fine in ("sed -i.bak s/a/b/ f", "sed -n s/a/b/p f", "grep -E x", "head -n 1", "sort -t, -k1 -rn"):
            with self.subTest(fine=fine):
                self.assertFalse(any(re.search(p, fine) for p, _ in BASH_FORBIDDEN + TOOL_FORBIDDEN), fine)

    @unittest.skipIf(shutil.which("bash") is None, "bash not on PATH")
    def test_every_script_parses(self) -> None:
        for path in PORTABLE + NEEDS_BASH_44:
            if path.suffix == ".template":
                continue  # holds <!-- IF --> directives
            with self.subTest(script=path.name):
                result = subprocess.run(["bash", "-n", str(path)], capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
