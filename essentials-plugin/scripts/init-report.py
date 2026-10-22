#!/usr/bin/env python3
"""init-report — the preset `/essentials:init` reads and the report it writes for another plugin.

Why this exists
---------------
`/essentials:init --preset F --report R` is the entry point another plugin's agent drives (sdd hands
over to it inline): the preset answers some questions up front, the report says what was rendered.
Both are a contract, so neither is left to the model. `preset` decides which preset keys are usable
— against the very checks `scripts/init-render.py` applies to `answers.json`, imported from it rather
than restated — and `write` assembles the report with the exact keys, types and value sets the
contract names. A person running `/essentials:init` with no arguments never reaches this script.

Usage
-----
    init-report.py preset --preset F --workspace W
        Print {"preset": {key: value}, "ignored": {key: reason}, "ask": [key, …], "target": DIR|null}.
        `preset` holds the usable keys (`targetDir` resolved to an absolute path), `ignored` every
        other key with the reason, `ask` the keys the command still asks — Step 1's `targetMode`
        first when no usable target key was given, then every answer key the preset lacks — and
        `target` the target directory as far as the preset decides it. Never fails on a preset: an
        unreadable or malformed file is reported as ignored key `(preset)` and everything is asked.

    init-report.py write --report R --status rendered|stopped [--stopped-at STEP] --workspace W
                         [--preset F] [--answers A] [--target DIR] [--asked k,k,…]
                         [--stack-lint passed|failed|not-run]
                         [--smoke-build passed|compiled-only|failed|not-run]
        Write the report to R. `--answers` is the answers.json Step 10 wrote; with `rendered` it is
        required and must pass the renderer's own validation. `--asked` defaults, for `rendered`, to
        `targetMode` (when no usable target key was preset) plus every answer key the preset did not
        supply, and to nothing for `stopped`. A key in `--asked` is never also listed under `preset`
        (a preset value the user changed at the Step 7 confirmation was asked). `--target` defaults
        to what the preset and the answers decide.

The report (`contractVersion` 1 grows only by addition):

    {"contractVersion": 1, "essentialsVersion": <the essentials.version pin>,
     "pluginVersion": <.claude-plugin/plugin.json version>, "status": "rendered" | "stopped",
     "stoppedAt": "<step>" (stopped only), "target": <absolute dir or null>, "workspace": <absolute dir>,
     "answers": {<the answer keys as rendered>}, "preset": [key, …], "asked": [key, …],
     "ignoredPreset": {key: reason}, "stackLint": …, "smokeBuild": …}

Preset keys: the answer keys `init-render.py` takes (`ENUMS`, `METADATA`, `OPTIONAL`), plus
`targetMode` (`current` | `subdirectory`, Step 1's two options) and `targetDir` (absolute, or relative
to the workspace; supersedes `targetMode`, which is then ignored).

Exit codes
----------
    0   printed (preset) / written (write)
    2   could not run (bad arguments, `--answers` that the renderer would reject, an unwritable report)

Standard library only; Python 3.11+.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import sys
from pathlib import Path

PLUGIN_ROOT = Path(__file__).resolve().parent.parent
PLUGIN_JSON = PLUGIN_ROOT / ".claude-plugin" / "plugin.json"

_spec = importlib.util.spec_from_file_location("init_render", PLUGIN_ROOT / "scripts" / "init-render.py")
if _spec is None or _spec.loader is None:
    raise ImportError("cannot load scripts/init-render.py")
ir = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = ir  # its dataclasses resolve their module through sys.modules
_spec.loader.exec_module(ir)

CONTRACT_VERSION = 1
ANSWER_KEYS = (*ir.ENUMS, *ir.METADATA, *ir.OPTIONAL)
TARGET_MODES = ("current", "subdirectory")
TARGET_KEYS = ("targetMode", "targetDir")
PRESET_KEYS = (*ANSWER_KEYS, *TARGET_KEYS)
STATUSES = ("rendered", "stopped")
STACK_LINT = ("passed", "failed", "not-run")
SMOKE_BUILD = ("passed", "compiled-only", "failed", "not-run")
FILE_KEY = "(preset)"

# A complete, valid answer set: one key at a time is swapped in and run through the renderer's own
# normalize_answers, so a preset value is usable exactly when the renderer would accept it. Its explicit
# packagePath keeps that per-key pass from deriving a package; _derived_package_reason checks the one
# pair the renderer combines, groupId and artifactId, when the preset names no usable packagePath.
_PROBE = {
    **{key: values[0] for key, values in ir.ENUMS.items()},
    "projectName": "Probe",
    "groupId": "com.example",
    "artifactId": "probe",
    "packagePath": "com.example.probe",
}


class ReportError(Exception):
    """The script cannot run (exit 2)."""


def _answer_reason(key: str, value) -> str | None:
    """Why the renderer would reject `value` for `key`, or None when it accepts it."""
    if key == "packagePath" and (not isinstance(value, str) or not value.strip()):
        # normalize_answers derives the package for an empty one; a preset must name one.
        return "answer 'packagePath' must be a non-empty string"
    try:
        ir.normalize_answers({**_PROBE, key: value})
    except ir.RenderError as exc:
        if key in ir.ENUMS:  # the renderer's message is Python repr; the reader of a report reads JSON
            return f"{key}={json.dumps(value)} is not one of {json.dumps(list(ir.ENUMS[key]))}"
        return str(exc)
    return None


def _derived_package_reason(usable: dict) -> str | None:
    """Why the renderer would refuse the package it derives from the usable artifactId, or None.

    Only without a usable packagePath: normalize_answers then derives the package from groupId and
    artifactId (the probe's groupId when the preset has none). Each passed the per-key check, so a groupId
    is a valid package already and the segment the artifactId adds is what the renderer can refuse — a
    Java/Kotlin keyword such as `class`.
    """
    if "packagePath" in usable or "artifactId" not in usable:
        return None
    answers = {key: value for key, value in _PROBE.items() if key != "packagePath"}
    answers.update({key: usable[key] for key in ("groupId", "artifactId") if key in usable})
    try:
        ir.normalize_answers(answers)
    except ir.RenderError:
        segment = ir.derive_package(answers["groupId"], answers["artifactId"]).rsplit(".", 1)[-1]
        return (f"with no packagePath the package is derived from artifactId {json.dumps(answers['artifactId'])}, "
                f"and its segment {segment!r} is not a valid Java/Kotlin identifier; preset a packagePath to keep "
                f"this artifactId")
    return None


def _target_dir(value, workspace: Path) -> tuple[str | None, str | None]:
    if not isinstance(value, str) or not value.strip():
        return None, "targetDir must be a non-empty string"
    path = Path(os.path.normpath(workspace / Path(value).expanduser()))
    if path.exists() and not path.is_dir():
        return None, f"targetDir {json.dumps(str(path))} exists and is not a directory"
    return str(path), None


def read_preset(path: Path | None, workspace: Path) -> tuple[dict, dict]:
    """(usable key -> value, ignored key -> reason), in PRESET_KEYS order then file order."""
    if path is None:
        return {}, {}
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        return {}, {FILE_KEY: f"cannot read the preset: {exc}"}
    if not isinstance(raw, dict):
        return {}, {FILE_KEY: "the preset is not a JSON object"}
    usable, ignored = {}, {}
    for key in PRESET_KEYS:
        if key not in raw:
            continue
        value = raw[key]
        if key == "targetDir":
            value, reason = _target_dir(value, workspace)
        elif key == "targetMode":
            reason = None if isinstance(value, str) and value in TARGET_MODES else \
                f"targetMode={json.dumps(value)} is not one of {json.dumps(list(TARGET_MODES))}"
        else:
            reason = _answer_reason(key, value)
        if reason is None:
            usable[key] = value
        else:
            ignored[key] = reason
    reason = _derived_package_reason(usable)
    if reason is not None:
        del usable["artifactId"]
        ignored["artifactId"] = reason
        ignored = {key: ignored[key] for key in PRESET_KEYS if key in ignored}
    if "targetDir" in usable and "targetMode" in usable:
        del usable["targetMode"]
        ignored["targetMode"] = "superseded by targetDir"
    for key in raw:
        if key not in PRESET_KEYS:
            ignored[key] = "unknown key"
    return usable, ignored


def to_ask(usable: dict) -> list[str]:
    target = [] if any(k in usable for k in TARGET_KEYS) else ["targetMode"]
    return target + [k for k in ANSWER_KEYS if k not in usable]


def resolve_target(usable: dict, answers: dict, workspace: Path) -> str | None:
    if "targetDir" in usable:
        return usable["targetDir"]
    if usable.get("targetMode") == "current":
        return str(workspace)
    artifact = answers.get("artifactId", usable.get("artifactId"))
    if usable.get("targetMode") == "subdirectory" and isinstance(artifact, str):
        return str(workspace / artifact)
    return None


def essentials_version() -> str:
    try:
        return ir.load_pins(ir.DEFAULT_PINS)["essentials.version"]
    except (ir.RenderError, KeyError) as exc:
        raise ReportError(f"cannot read the essentials.version pin: {exc}")


def plugin_version() -> str:
    try:
        return json.loads(PLUGIN_JSON.read_text(encoding="utf-8"))["version"]
    except (OSError, json.JSONDecodeError, KeyError, TypeError) as exc:
        raise ReportError(f"cannot read the version from {PLUGIN_JSON}: {exc}")


def read_answers(path: Path | None, status: str) -> dict:
    if path is None:
        if status == "rendered":
            raise ReportError("--answers is required with --status rendered")
        return {}
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ReportError(f"cannot read answers {path}: {exc}")
    if status == "rendered":
        try:
            return ir.normalize_answers(raw)
        except ir.RenderError as exc:
            raise ReportError(f"answers {path}: {exc}")
    if not isinstance(raw, dict):
        raise ReportError(f"answers {path} is not a JSON object")
    # Stopped: whatever had been decided, as long as it is one of the keys and a scalar.
    return {k: raw[k] for k in ANSWER_KEYS if isinstance(raw.get(k), (str, bool))}


def parse_asked(value: str | None, usable: dict, status: str) -> list[str]:
    if value is None:
        return to_ask(usable) if status == "rendered" else []
    asked = [k.strip() for k in value.split(",") if k.strip()]
    unknown = [k for k in asked if k not in PRESET_KEYS]
    if unknown:
        raise ReportError(f"--asked names unknown keys: {', '.join(unknown)}")
    return list(dict.fromkeys(asked))


def build_report(args) -> dict:
    workspace = Path(os.path.abspath(args.workspace))
    usable, ignored = read_preset(args.preset, workspace)
    answers = read_answers(args.answers, args.status)
    asked = parse_asked(args.asked, usable, args.status)
    if args.status == "stopped" and not args.stopped_at:
        raise ReportError("--stopped-at is required with --status stopped")
    if args.status == "rendered" and args.stopped_at:
        raise ReportError("--stopped-at goes only with --status stopped")
    if args.target is not None:
        target = os.path.normpath(workspace / Path(args.target).expanduser())
    else:
        target = resolve_target(usable, answers, workspace)
    report = {
        "contractVersion": CONTRACT_VERSION,
        "essentialsVersion": essentials_version(),
        "pluginVersion": plugin_version(),
        "status": args.status,
    }
    if args.status == "stopped":
        report["stoppedAt"] = args.stopped_at
    report.update({
        "target": target,
        "workspace": str(workspace),
        "answers": answers,
        "preset": [k for k in usable if k not in asked],
        "asked": asked,
        "ignoredPreset": ignored,
        "stackLint": args.stack_lint,
        "smokeBuild": args.smoke_build,
    })
    return report


def write_report(path: Path, report: dict) -> None:
    text = json.dumps(report, indent=2, ensure_ascii=False) + "\n"
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_name(path.name + ".tmp")
        tmp.write_text(text, encoding="utf-8")
        tmp.replace(path)
    except OSError as exc:
        raise ReportError(f"cannot write the report {path}: {exc}")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="The /essentials:init preset and report.")
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("preset", help="which preset keys are usable, which are ignored, what is asked")
    p.add_argument("--preset", type=Path, required=True)
    p.add_argument("--workspace", type=Path, required=True)
    w = sub.add_parser("write", help="write the report")
    w.add_argument("--report", type=Path, required=True)
    w.add_argument("--status", choices=STATUSES, required=True)
    w.add_argument("--stopped-at")
    w.add_argument("--workspace", type=Path, required=True)
    w.add_argument("--preset", type=Path)
    w.add_argument("--answers", type=Path)
    w.add_argument("--target", type=Path)
    w.add_argument("--asked")
    w.add_argument("--stack-lint", choices=STACK_LINT, default="not-run")
    w.add_argument("--smoke-build", choices=SMOKE_BUILD, default="not-run")
    args = ap.parse_args(argv)
    try:
        if args.cmd == "preset":
            workspace = Path(os.path.abspath(args.workspace))
            usable, ignored = read_preset(args.preset, workspace)
            print(json.dumps({"preset": usable, "ignored": ignored, "ask": to_ask(usable),
                              "target": resolve_target(usable, {}, workspace)}, indent=2, ensure_ascii=False))
            return 0
        write_report(args.report, build_report(args))
        print(args.report)
        return 0
    except ReportError as exc:
        print(f"init-report: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
