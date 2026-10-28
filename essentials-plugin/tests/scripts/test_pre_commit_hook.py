#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["pyyaml==6.0.3", "jsonschema==4.26.0"]
# ///
"""Behaviour tests for the pre-commit hook /essentials:init installs (lintGate=hook).

    uv run --script tests/scripts/test_pre_commit_hook.py

Each case builds a throwaway git repository with the project in a SUBDIRECTORY of it — the layout
where a hook that assumed it ran at the repository top level linted the wrong tree, or never ran —
installs the hook template, the slice-lint script and its schema exactly where the init manifest
puts them, activates the hook with the README's command, and commits. The hook runs on a PATH of
stubs written here, so the result does not depend on whether this machine has uv: `python3` is the
interpreter running this file, which carries pyyaml and jsonschema.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

PLUGIN = Path(__file__).resolve().parents[2]
HOOK = PLUGIN / "references" / "init-assets" / "project" / ".githooks" / "pre-commit.template"
LINT = PLUGIN / "scripts" / "slice-lint.py"
SCHEMA = PLUGIN / "references" / "slice" / "slice-yaml.schema.json"
FIXTURE = PLUGIN / "tests" / "fixtures" / "service-entity"

# The one authoring mistake that makes a manifest unparseable: a braced path unquoted in a flow mapping.
BROKEN = """slice: shipping.broken
kind: command
bc: shipping
status: live
endpoints:
  - { method: POST, path: /api/shipping/{id}/broken, auth: user }
"""

# The activation command the project README gives, run from the project directory.
ACTIVATE = 'git config core.hooksPath "$(git rev-parse --show-prefix).githooks"'


class PreCommitHook(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        tmp = Path(self._tmp.name)
        self.repo = tmp / "repo"
        self.project = self.repo / "orders-service"
        self.bin = tmp / "bin"
        self.bin.mkdir()
        for tool in ("bash", "git", "dirname", "grep"):
            found = shutil.which(tool)
            assert found, f"{tool} not on PATH"
            (self.bin / tool).symlink_to(found)
        # A wrapper, not a symlink: a venv interpreter reached through a symlink elsewhere loses its venv.
        self.stub("python3", f'exec "{sys.executable}" "$@"')
        self.env = {
            "PATH": str(self.bin),
            "HOME": str(tmp),
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@example.com",
            "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@example.com",
        }

        self.project.mkdir(parents=True)
        self.assertEqual(self.git("init", "-q", "-b", "main").returncode, 0)
        shutil.copytree(FIXTURE / "src", self.project / "src")
        (self.project / "scripts").mkdir()
        shutil.copy(LINT, self.project / "scripts" / "slice-lint.py")
        shutil.copy(SCHEMA, self.project / "scripts" / "slice-yaml.schema.json")
        (self.project / ".githooks").mkdir()
        shutil.copy(HOOK, self.project / ".githooks" / "pre-commit")
        (self.project / ".githooks" / "pre-commit").chmod(0o755)

    def stub(self, name, body):
        path = self.bin / name
        path.unlink(missing_ok=True)
        path.write_text(f"#!/bin/sh\n{body}\n")
        path.chmod(0o755)

    def tearDown(self):
        self._tmp.cleanup()

    def run_in(self, cwd, *cmd):
        return subprocess.run(cmd, cwd=cwd, env=self.env, capture_output=True, text=True, timeout=120)

    def git(self, *args):
        return self.run_in(self.repo, str(self.bin / "git"), *args)

    def activate(self):
        r = self.run_in(self.project, str(self.bin / "bash"), "-c", ACTIVATE)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.git("config", "core.hooksPath").stdout.strip(), "orders-service/.githooks")

    def commit_all(self):
        self.assertEqual(self.git("add", "-A").returncode, 0)
        return self.git("commit", "-q", "-m", "c")

    def break_a_manifest(self):
        manifest = self.project / "src" / "broken" / "slice.yaml"
        manifest.parent.mkdir(parents=True)
        manifest.write_text(BROKEN)

    def test_valid_project_commits_and_a_broken_manifest_is_blocked(self):
        self.activate()
        r = self.commit_all()
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.break_a_manifest()
        r = self.commit_all()
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("UNPARSEABLE MANIFESTS", r.stdout + r.stderr)  # git sends hook stdout to stderr
        self.assertIn("src/broken/slice.yaml", r.stderr)

    def test_staged_manifest_outside_the_project_does_not_run_it(self):
        self.activate()
        self.assertEqual(self.commit_all().returncode, 0)
        # A broken manifest elsewhere in the repository is not this project's to lint.
        other = self.repo / "other-service" / "slice.yaml"
        other.parent.mkdir()
        other.write_text(BROKEN)
        r = self.commit_all()
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertNotIn("slice-lint", r.stdout + r.stderr)

    def test_missing_script_is_a_hard_error(self):
        self.activate()
        (self.project / "scripts" / "slice-lint.py").unlink()
        r = self.commit_all()
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("scripts/slice-lint.py or scripts/slice-yaml.schema.json is missing", r.stderr)

    def test_could_not_run_blocks(self):
        self.activate()
        self.stub("python3", "exit 2")
        r = self.commit_all()
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("could not validate", r.stderr)

    def test_no_interpreter_fails_open(self):
        self.activate()
        (self.bin / "python3").unlink()
        self.break_a_manifest()
        r = self.commit_all()
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("neither uv nor python3", r.stderr)

    def test_forwarder_from_an_existing_hook(self):
        # core.hooksPath left unset because .git/hooks/pre-commit already exists: the README's
        # forwarder line, run from the existing hook, reaches the project's hook.
        hooks = self.repo / ".git" / "hooks"
        hooks.mkdir(exist_ok=True)
        forwarder = hooks / "pre-commit"
        forwarder.write_text('#!/usr/bin/env bash\n'
                             '"$(git rev-parse --show-toplevel)/orders-service/.githooks/pre-commit" || exit 1\n')
        forwarder.chmod(0o755)
        self.assertEqual(self.commit_all().returncode, 0)
        self.break_a_manifest()
        r = self.commit_all()
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("UNPARSEABLE MANIFESTS", r.stdout + r.stderr)  # git sends hook stdout to stderr


if __name__ == "__main__":
    if os.name == "nt":  # pragma: no cover
        raise SystemExit("the hook is a bash script; run this on Linux or macOS")
    unittest.main()
