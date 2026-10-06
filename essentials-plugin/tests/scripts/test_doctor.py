"""Tests for scripts/doctor.sh. Standard library only.

Run from the plugin root:  python3 -m unittest discover -s tests/scripts -p 'test_doctor.py'

Every case runs doctor.sh on a PATH that holds only stub tools written here, so the result does not depend on what
the machine has installed: a python3 that reports a chosen version and fails the imports it is told to, a java with a
chosen major, a docker whose daemon is up or down, mvn, npm, uv and rg present or absent. JAVA_HOME is unset, so java
is found on that PATH. The JDK it needs is read from stack-pins.md here too, exactly as doctor.sh reads it.
"""

from __future__ import annotations

import json
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

PLUGIN = Path(__file__).resolve().parents[2]
SCRIPT = PLUGIN / "scripts" / "doctor.sh"
PINS = PLUGIN / "references" / "stack" / "stack-pins.md"
BASH = shutil.which("bash")
PROFILES = ("init", "review", "slice", "docs", "all")


def java_pin() -> int:
    match = re.search(r"^\|\s*`java\.version`\s*\|\s*\**(\d+)", PINS.read_text(encoding="utf-8"), re.MULTILINE)
    assert match, "stack-pins.md has no java.version row"
    return int(match.group(1))


def stub(directory: Path, name: str, body: str) -> None:
    path = directory / name
    path.write_text("#!/bin/sh\n" + body, encoding="utf-8")
    path.chmod(0o755)


def python3(version: str, yaml: bool, jsonschema: bool) -> str:
    def imp(module: str, ok: bool) -> str:
        return f'  *"import {module}"*) exit {0 if ok else 1} ;;\n'

    return (
        'case "$2" in\n'
        f'  *version_info*) echo {version} ;;\n'
        f"{imp('yaml', yaml)}{imp('jsonschema', jsonschema)}"
        "  *) exit 1 ;;\n"
        "esac\n"
    )


def java(major: int) -> str:
    return f'echo \'openjdk version "{major}.0.2" 2026-01-20\' >&2\n'


def docker(daemon: bool) -> str:
    info = "exit 0" if daemon else 'echo "Cannot connect to the Docker daemon" >&2; exit 1'
    return (
        'case "$1" in\n'
        "  --version) echo 'Docker version 28.1.1, build 4eba377' ;;\n"
        f"  info) {info} ;;\n"
        "esac\n"
    )


class Machine:
    """A temporary bin directory of stubs, and a working directory to run doctor.sh in."""

    def __init__(self, *, py: str | None, yaml: bool = False, jsonschema: bool = False, uv: bool = False,
                 java_major: int | None = None, docker_daemon: bool | None = None, mvn: bool = True,
                 npm: bool = True, rg: bool = False, git: bool = False, mvnw: bool = False) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        root = Path(self._tmp.name)
        self.bin = root / "bin"
        self.cwd = root / "work"
        self.bin.mkdir()
        self.cwd.mkdir()
        if py is not None:
            stub(self.bin, "python3", python3(py, yaml, jsonschema))
        if uv:
            stub(self.bin, "uv", "echo 'uv 0.9.0 (abc 2026-01-01)'\n")
        if java_major is not None:
            stub(self.bin, "java", java(java_major))
        if docker_daemon is not None:
            stub(self.bin, "docker", docker(docker_daemon))
        if mvn:
            stub(self.bin, "mvn", "echo 'Apache Maven 3.9.11 (3e54c93a704957b63ee3494413a2b544fd3d825b)'\n")
        if npm:
            stub(self.bin, "npm", "echo 10.9.2\n")
        if rg:
            stub(self.bin, "rg", "echo 'ripgrep 14.1.1'; echo; echo 'features:+pcre2'\n")
        if git:
            stub(self.bin, "git", "echo 'git version 2.47.1'\n")
        if mvnw:
            stub(self.cwd, "mvnw", "exit 0\n")

    def run(self, *argv: str) -> subprocess.CompletedProcess[str]:
        bash = BASH
        assert bash is not None
        env = {"PATH": str(self.bin), "HOME": str(self.cwd)}
        return subprocess.run([bash, str(SCRIPT), *argv], cwd=self.cwd, env=env, capture_output=True, text=True)

    def json(self, profile: str) -> tuple[int, dict]:
        result = self.run("--for", profile, "--json")
        return result.returncode, json.loads(result.stdout)

    def close(self) -> None:
        self._tmp.cleanup()


def requirement(document: dict, name: str) -> dict:
    found = [r for r in document["requirements"] if r["name"] == name]
    assert len(found) == 1, f"{name}: {found}"
    return found[0]


def names(document: dict) -> list[str]:
    return [r["name"] for r in document["requirements"]]


class DoctorTest(unittest.TestCase):
    pin: int

    @classmethod
    def setUpClass(cls) -> None:
        if BASH is None:
            raise unittest.SkipTest("bash not on PATH")
        cls.pin = java_pin()

    def machine(self, **kwargs) -> Machine:
        m = Machine(**kwargs)
        self.addCleanup(m.close)
        return m

    def test_the_spec_machine(self) -> None:
        """python3 3.9, no uv, java one major below the pin, docker present with its daemon down."""
        m = self.machine(py="3.9.18", java_major=self.pin - 4, docker_daemon=False)

        expected_rows = {
            "init": ["bash", "python3", "java", "maven", "docker", "npm"],
            "review": ["bash", "python3", "uv", "git"],
            "slice": ["bash", "python3", "uv"],
            "docs": ["bash", "python3", "rg"],
            "all": ["bash", "python3", "uv", "java", "maven", "docker", "npm", "git", "rg"],
        }
        for profile in PROFILES:
            with self.subTest(profile=profile):
                code, doc = m.json(profile)
                # python3 is hard for every profile, so every one blocks.
                self.assertEqual(code, 1)
                self.assertEqual(doc["schema"], 1)
                self.assertEqual(doc["profile"], profile)
                self.assertFalse(doc["ok"])
                self.assertIn("python3", doc["blocking"])
                self.assertEqual(names(doc), expected_rows[profile])
                py = requirement(doc, "python3")
                self.assertEqual((py["status"], py["found"], py["required"], py["blocking"]),
                                 ("too-old", "3.9.18", ">= 3.11", True))
                self.assertTrue(any(i["effect"] == "stop" for i in py["impacts"]))
                for impact in py["impacts"]:
                    self.assertIn(impact["profile"], expected_tags(profile))

        _, doc = m.json("all")
        self.assertEqual(requirement(doc, "uv")["status"], "missing")
        # A python3 too old to run anything has no pyyaml to fall back on, so slice-map stops: uv blocks too.
        self.assertEqual(doc["blocking"], ["python3", "uv"])
        jdk = requirement(doc, "java")
        self.assertEqual((jdk["status"], jdk["found"], jdk["required"]),
                         ("too-old", f"{self.pin - 4}.0.2", f">= {self.pin}"))
        self.assertFalse(jdk["blocking"])
        dock = requirement(doc, "docker")
        self.assertEqual(dock["status"], "not-running")
        self.assertEqual({i["effect"] for i in dock["impacts"]}, {"compile-only"})
        self.assertEqual(requirement(doc, "rg")["status"], "missing")
        self.assertEqual(requirement(doc, "maven")["status"], "ok")

        plain = m.run("--for", "init")
        self.assertEqual(plain.returncode, 1)
        lines = plain.stdout.splitlines()
        self.assertEqual(lines[0].split(" (")[0], "essentials doctor — profile init")
        self.assertEqual(len(lines), 1 + 6 + 1, plain.stdout)
        self.assertRegex(plain.stdout, r"(?m)^TOO OLD\s+python3\s+3\.9\.18 \(need >= 3\.11\) — STOP: /essentials:init stops")
        self.assertRegex(plain.stdout, rf"(?m)^TOO OLD\s+java\s+{self.pin - 4}\.0\.2 \(need >= {self.pin}\) — NOT RUN: ")
        self.assertRegex(plain.stdout, r"(?m)^NOT RUNNING\s+docker\s+28\.1\.1 \(daemon not reachable\) .*— COMPILE-ONLY: ")
        self.assertRegex(plain.stdout, r"(?m)^ok\s+maven\s+3\.9\.11 \(need any\)$")
        self.assertEqual(lines[-1], "BLOCKED: python3 — a command in profile init stops without it")
        self.assertEqual(m.run("--for", "all").stdout.splitlines()[-1],
                         "BLOCKED: python3, uv — a command in profile all stops without it")

    def test_degraded_but_nothing_blocks(self) -> None:
        """python3 3.12 with pyyaml but no jsonschema, no uv, no Docker, no npm, no rg."""
        m = self.machine(py="3.12.4", yaml=True, java_major=self.pin, docker_daemon=None, npm=False)
        for profile in PROFILES:
            with self.subTest(profile=profile):
                code, doc = m.json(profile)
                self.assertEqual(code, 0, doc)
                self.assertTrue(doc["ok"])
                self.assertEqual(doc["blocking"], [])
        _, doc = m.json("all")
        uv = requirement(doc, "uv")
        self.assertEqual(uv["status"], "partial")
        self.assertEqual({i["effect"] for i in uv["impacts"]}, {"not-run"})
        self.assertEqual(requirement(doc, "java")["status"], "ok")
        self.assertEqual(requirement(doc, "docker")["status"], "missing")
        self.assertEqual(requirement(doc, "npm")["status"], "missing")
        self.assertEqual(requirement(doc, "python3")["impacts"], [])
        # Without git /essentials:review still runs in <path> mode, so git degrades it and never blocks.
        git = requirement(doc, "git")
        self.assertEqual((git["status"], git["blocking"]), ("missing", False))
        self.assertEqual({(i["profile"], i["effect"]) for i in git["impacts"]}, {("review", "not-run")})

        plain = m.run("--for", "slice")
        self.assertEqual(plain.returncode, 0)
        self.assertRegex(plain.stdout, r"(?m)^PARTIAL\s+uv\s+no uv; python3 has pyyaml, not jsonschema .*NOT RUN: slice-lint\.py")
        self.assertRegex(plain.stdout.splitlines()[-1], r"^OK: nothing missing stops a command in profile slice; 1 ")

    def test_no_pyyaml_and_no_uv_blocks_slice_only(self) -> None:
        m = self.machine(py="3.11.0", java_major=self.pin + 1, docker_daemon=True)
        expected = {"init": 0, "review": 0, "slice": 1, "docs": 0, "all": 1}
        for profile, code in expected.items():
            with self.subTest(profile=profile):
                got, doc = m.json(profile)
                self.assertEqual(got, code, doc)
                self.assertEqual(doc["blocking"], ["uv"] if code else [])
        _, doc = m.json("review")
        self.assertEqual({i["effect"] for i in requirement(doc, "uv")["impacts"]}, {"not-run"})
        _, doc = m.json("all")
        self.assertEqual(requirement(doc, "java")["status"], "ok", "a newer JDK than the pin is fine")
        self.assertEqual(requirement(doc, "docker")["status"], "ok")

    def test_everything_present(self) -> None:
        m = self.machine(py="3.13.1", yaml=True, jsonschema=True, uv=True, java_major=self.pin,
                         docker_daemon=True, rg=True, git=True)
        code, doc = m.json("all")
        self.assertEqual(code, 0)
        self.assertTrue(all(r["status"] == "ok" and r["impacts"] == [] for r in doc["requirements"]), doc)
        self.assertEqual(requirement(doc, "uv")["found"], "0.9.0")
        self.assertEqual(requirement(doc, "rg")["found"], "14.1.1")
        self.assertEqual(requirement(doc, "git")["found"], "2.47.1")

    def test_uv_missing_falls_back_to_python3(self) -> None:
        m = self.machine(py="3.12.0", yaml=True, jsonschema=True)
        code, doc = m.json("slice")
        self.assertEqual(code, 0)
        self.assertEqual(requirement(doc, "uv")["status"], "fallback")
        self.assertEqual({i["effect"] for i in requirement(doc, "uv")["impacts"]}, {"fallback"})

    def test_missing_tools(self) -> None:
        m = self.machine(py=None, mvn=False, npm=False)
        code, doc = m.json("all")
        self.assertEqual(code, 1)
        for name in ("python3", "uv", "java", "maven", "docker", "npm", "git", "rg"):
            with self.subTest(name=name):
                r = requirement(doc, name)
                self.assertEqual(r["status"], "missing")
                self.assertIsNone(r["found"])

    def test_project_wrapper_without_mvn(self) -> None:
        m = self.machine(py="3.12.0", mvn=False, mvnw=True)
        _, doc = m.json("all")
        maven = requirement(doc, "maven")
        self.assertEqual(maven["status"], "partial")
        self.assertEqual([i["profile"] for i in maven["impacts"]], ["init"], "./mvnw serves upgrade, not init")

    def test_usage(self) -> None:
        m = self.machine(py="3.12.0")
        self.assertEqual(m.run("--for", "nonsense").returncode, 2)
        self.assertEqual(m.run("--bogus").returncode, 2)
        self.assertEqual(m.run("--for").returncode, 2)
        shown = m.run("--help")
        self.assertEqual(shown.returncode, 0)
        self.assertIn("Usage:", shown.stdout)
        _, doc = m.json("all")
        self.assertEqual(doc["profile"], "all")
        self.assertEqual(m.run("--json").returncode, m.json("all")[0], "the default profile is all")


def expected_tags(profile: str) -> set[str]:
    return {"init", "review", "slice", "docs", "upgrade"} if profile == "all" else {profile}


if __name__ == "__main__":
    unittest.main()
