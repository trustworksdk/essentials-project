#!/usr/bin/env python3
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
"""check-release-exclusions — no example module reaches Maven Central.

Why this exists
---------------
The release profile publishes through central-publishing-maven-plugin, which replaces maven-deploy-plugin and never
reads `maven.deploy.skip`. The property the example POMs set kept nothing out, and 0.50.0 published every example
module. They are now kept out twice, and each list has to name every example module:

- the release profile's `excludeArtifacts` in the root pom.xml (matched on artifactId alone), and
- the `-pl '!:<artifactId>,…'` exclusions on the deploy in .github/workflows/release-to-maven-central.yml.

Nothing fails when a new example module is added to the reactor and to neither list: the next release just publishes
it, and a Maven Central release cannot be taken back. This script fails instead. It walks the reactor from the root
pom.xml's <modules> (profiles included), takes every module whose directory is under examples/, and requires both
lists to equal that set - so a stale entry for a removed or renamed module fails too.

Standard library only. Usage: scripts/check-release-exclusions.py [--repo DIR]
"""

import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
WORKFLOW = Path(".github/workflows/release-to-maven-central.yml")
CENTRAL_PLUGIN = "central-publishing-maven-plugin"


def error(message: str, file: Path | None = None) -> None:
    if os.environ.get("GITHUB_ACTIONS") == "true":
        print(f"::error{' file=' + str(file) if file else ''}::{message}")
    else:
        print(f"ERROR: {message}" + (f" ({file})" if file else ""), file=sys.stderr)


def text(element: ET.Element) -> str:
    return (element.text or "").strip()


def parse_pom(path: Path) -> ET.Element:
    return ET.parse(path).getroot()


def module_names(project: ET.Element) -> list[str]:
    names = [text(m) for m in project.findall("m:modules/m:module", NS)]
    names += [text(m) for m in project.findall("m:profiles/m:profile/m:modules/m:module", NS)]
    return names


def reactor_modules(repo: Path) -> dict[Path, str]:
    """Every module reachable from the root pom.xml, as directory (relative to repo) -> artifactId."""
    found: dict[Path, str] = {}
    pending = [Path(".")]
    while pending:
        directory = pending.pop()
        project = parse_pom(repo / directory / "pom.xml")
        artifact_id = project.find("m:artifactId", NS)
        if artifact_id is None:
            raise ValueError(f"{directory / 'pom.xml'} has no <artifactId> of its own")
        found[directory] = text(artifact_id)
        for name in module_names(project):
            child = Path(os.path.normpath(directory / name))
            if child not in found:
                pending.append(child)
    return found


def pom_exclusions(repo: Path) -> list[str] | None:
    project = parse_pom(repo / "pom.xml")
    for profile in project.findall("m:profiles/m:profile", NS):
        if profile.findtext("m:id", namespaces=NS) != "release":
            continue
        for plugin in profile.findall("m:build/m:plugins/m:plugin", NS):
            if plugin.findtext("m:artifactId", namespaces=NS) == CENTRAL_PLUGIN:
                return [text(a) for a in plugin.findall("m:configuration/m:excludeArtifacts/m:artifact", NS)]
    return None


def workflow_exclusions(repo: Path) -> list[str] | None:
    text = (repo / WORKFLOW).read_text(encoding="utf-8")
    selections = re.findall(r"-pl\s+'([^']*)'", text)
    if len(selections) != 1:
        return None
    entries = [e.strip() for e in selections[0].split(",") if e.strip()]
    malformed = [e for e in entries if not re.fullmatch(r"!:[A-Za-z0-9_.-]+", e)]
    if malformed:
        raise ValueError(f"{WORKFLOW}: -pl entries must have the form '!:<artifactId>', got {malformed}")
    return [e[2:] for e in entries]


def compare(name: str, file: Path, listed: list[str], expected: set[str]) -> bool:
    ok = True
    duplicates = sorted({a for a in listed if listed.count(a) > 1})
    if duplicates:
        error(f"{name} lists {duplicates} more than once", file)
        ok = False
    missing = sorted(expected - set(listed))
    if missing:
        error(f"{name} is missing example module(s) {missing} - the next release would publish them to Maven Central", file)
        ok = False
    extra = sorted(set(listed) - expected)
    if extra:
        error(f"{name} lists {extra}, which is not an example module in the reactor - remove it, or if it was renamed, "
              f"list the new artifactId", file)
        ok = False
    return ok


def main() -> int:
    parser = argparse.ArgumentParser(description="Fail when an example module is not excluded from the Maven Central release.")
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parent.parent,
                        help="repository root (default: this script's repository)")
    repo = parser.parse_args().repo

    try:
        modules = reactor_modules(repo)
        in_pom = pom_exclusions(repo)
        in_workflow = workflow_exclusions(repo)
    except (OSError, ET.ParseError, ValueError) as e:
        error(str(e))
        return 2

    examples = {artifact for directory, artifact in modules.items() if directory.parts[:1] == ("examples",)}
    if not examples:
        error("found no reactor module under examples/ - the reactor walk is broken, not the exclusions")
        return 2
    if in_pom is None:
        error(f"no <excludeArtifacts> list found: expected one on {CENTRAL_PLUGIN} in the 'release' profile", Path("pom.xml"))
        return 1
    if in_workflow is None:
        error("expected exactly one -pl '<selection>' (on the deploy command)", WORKFLOW)
        return 1

    ok = compare("release profile <excludeArtifacts>", Path("pom.xml"), in_pom, examples)
    ok &= compare("release workflow -pl", WORKFLOW, in_workflow, examples)
    if ok:
        print(f"release exclusions OK: both lists name exactly the {len(examples)} example modules {sorted(examples)}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
