# Essentials — Next Release (unreleased)

_Working draft. Collects every change since the last release tag, **`0.60.1`**, that a user of the framework,
the `essentials` plugin or the examples would notice. The version number is decided at release time; this file
then becomes `docs/RELEASE-NOTES-<version>.md` (same structure as
[`RELEASE-NOTES-0.60.1.md`](RELEASE-NOTES-0.60.1.md)) and is reset to an empty skeleton._

_Add an entry in the same commit as the change. Cite the commit or PR. Write it for the person upgrading: what
changes for them, and what they must do, if anything. Purely internal refactors, test-only changes and CI
plumbing that no user sees go in §7 at most, as one line._

_A bug-fix entry also says how to observe the fix: the call, and what it returns, throws or logs before the fix
and after it. A project that worked around the bug can then check whether its workaround is still needed and
remove it, instead of keeping it because nobody can tell._

**So far:** plugin `0.60.1-1` (§4).

---

## Table of contents

1. [Breaking — what you MUST apply](#1-breaking--what-you-must-apply)
   - [1.1 ⚠️ Silent behaviour changes](#11-️-silent-behaviour-changes)
2. [New features](#2-new-features)
3. [Bug fixes](#3-bug-fixes)
4. [The `essentials` Claude Code plugin](#4-the-essentials-claude-code-plugin)
5. [Examples](#5-examples)
6. [Documentation](#6-documentation)
7. [Build and tooling](#7-build-and-tooling)

---

## 1. Breaking — what you MUST apply

None so far.

### 1.1 ⚠️ Silent behaviour changes

None so far.

---

## 2. New features

None so far.

---

## 3. Bug fixes

| Area | Fix | Commit |
|---|---|---|

---

## 4. The `essentials` Claude Code plugin

0.60.1 shipped plugin `0.60.1`. Plugin releases that follow it are listed here, newest first;
the full entries are in [`essentials-plugin/CHANGELOG.md`](../essentials-plugin/CHANGELOG.md).

- **0.60.1-1** — `/essentials:slice-check` grades findings by consequence and names their impact. Gate 8 and new
  write-site facts run in `slice-source.py`. A project-owned `slice-check-accepted.yaml` records settled deviations.
  `--fail-on` lets CI gate on a severity. The law gains sections on cross-context writes, dual writes and
  `DocumentDbRepository` read sides. `/essentials:upgrade` checks that the pre-commit hook is executable and runs,
  prefers the forwarder when other tools own hooks, and lists the views that will log the 0.60.1
  unhandled-event-types warning.

---

## 5. Examples

Not published; listed because they are the reference for how to use the framework.

---

## 6. Documentation

None so far.

---

## 7. Build and tooling

None so far.
