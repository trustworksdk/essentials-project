#!/usr/bin/env python3
"""Write a Markdown summary of Maven Surefire/Failsafe JUnit XML reports.

Replaces the third-party test-summary action (unofficial actions are not allowed).
Appends to $GITHUB_STEP_SUMMARY when set, and always prints to stdout, so it can be run
locally after `mvn test` / `mvn verify`. Never fails the step: a missing or unreadable
report is reported in the summary instead.
"""
import argparse
import html
import os
from collections import Counter
from pathlib import Path
from xml.etree import ElementTree as ET

# GitHub rejects a step summary above 1 MiB; stay well clear of it.
MAX_FAILURES_LISTED = 200
MAX_MESSAGE_CHARS = 300

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--type", choices=["surefire", "failsafe"], required=True)
parser.add_argument("--title", required=True)
args = parser.parse_args()

pattern = f"**/target/{args.type}-reports/TEST-*.xml"
reports = sorted(Path(".").glob(pattern))

counts = Counter()
failures = []
unreadable = []


def inline_code(text):
    # A backtick inside would close the span early; GitHub has no escape for it inside code.
    return "`" + text.replace("`", "'") + "`"


for report in reports:
    try:
        root = ET.parse(report).getroot()
    except ET.ParseError as e:
        # A fork killed mid-write (OOM, timeout) leaves a truncated report.
        unreadable.append((report, str(e)))
        continue

    for case in root.iter("testcase"):
        counts["total"] += 1
        failure = case.find("failure")
        detail = failure if failure is not None else case.find("error")

        if detail is not None:
            counts["failed" if failure is not None else "errors"] += 1
            failures.append((case.get("classname", ""), case.get("name", ""),
                             detail.get("message") or detail.get("type") or ""))
        elif case.find("skipped") is not None:
            counts["skipped"] += 1
        else:
            counts["passed"] += 1

status = "❌" if failures or unreadable else ("⚠️" if not reports else "✅")
lines = [
    f"## {status} {args.title}",
    "",
    "| Total | Passed | Failed | Errors | Skipped |",
    "|---:|---:|---:|---:|---:|",
    f"| {counts['total']} | {counts['passed']} | {counts['failed']} | "
    f"{counts['errors']} | {counts['skipped']} |",
    "",
]

if not reports:
    lines.append(f"⚠️ No reports found matching {inline_code(pattern)}")
    lines.append("")

if unreadable:
    lines.extend(["### Unreadable reports", ""])
    for report, err in unreadable:
        lines.append(f"- ⚠️ {inline_code(str(report))}: {html.escape(err)}")
    lines.append("")

if failures:
    lines.extend(["### Failed tests", ""])
    for cls, name, message in failures[:MAX_FAILURES_LISTED]:
        lines.append(f"- ❌ {inline_code(f'{cls}.{name}')}")
        first_line = message.strip().splitlines()[0] if message.strip() else ""
        if first_line:
            if len(first_line) > MAX_MESSAGE_CHARS:
                first_line = first_line[:MAX_MESSAGE_CHARS] + "…"
            lines.append(f"  - {html.escape(first_line)}")
    if len(failures) > MAX_FAILURES_LISTED:
        lines.append(f"- … and {len(failures) - MAX_FAILURES_LISTED} more "
                     "(see the uploaded JUnit results artifact)")
    lines.append("")

summary = "\n".join(lines) + "\n"

summary_file = os.environ.get("GITHUB_STEP_SUMMARY")
if summary_file:
    with open(summary_file, "a", encoding="utf-8") as f:
        f.write(summary)

print(summary)
