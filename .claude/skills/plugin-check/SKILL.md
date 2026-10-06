---
name: plugin-check
description: Run and verify the essentials plugin's checks before a commit, a pull request or a release — the deterministic plugin-docs steps, the scaffold builds of the init cells and slice fixtures, and the claude plugin eval suite. Use when asked to test, check, verify or validate anything under essentials-plugin/ or LLM/ (fixtures, goldens, graders, evals, scaffold), or before committing, opening a PR or releasing plugin work. Optional argument, a mode - changed, quick, manual, scaffold, evals, release.
argument-hint: "[changed|quick|manual|scaffold|evals|release]"
---

# plugin-check

`scripts/plugin-check.sh` is the single source of the plugin's checks: the step list, the commands, the CI
equivalence and the eval flags live there and nowhere else. This skill decides *when* to run which mode and what a
failure means. It never restates a command the script runs; `scripts/plugin-check.sh --help` and `list` are the
reference. Every command below runs from the repository root.

## Procedure

### 1. Plan and run the cheap part

- **No argument:** run `scripts/plugin-check.sh changed` and show its `Needs:` and `Run next:` sections to the user
  (keep the `Why` section for diagnosis, do not paste all of it). Then run `scripts/plugin-check.sh quick` straight
  away, and `manual` when `changed` lists it — both are cheap and need nothing but git and uv.
- **A mode argument:** run that mode. For `scaffold`, `evals` and `release`, go through step 2 first.
- `changed` takes an optional BASE (default: the merge base with `origin/main`). When the user names a ref or says
  "the last commit", pass it (`HEAD~1`).

Read the `quick` table: PASS, FAIL or SKIPPED. A SKIPPED `render-check` means no headless Chrome was found and
nothing was checked; say so plainly — it is not a pass, and a release needs it with `--chrome PATH`.

### 2. Ask before the expensive parts

Ask with AskUserQuestion, one grouped question per expensive mode `changed` called for, recommended option first:

- **scaffold** (Docker, a full reactor install, minutes per mode): offer the narrowest set `changed` printed (for
  example `scaffold slices --case fixture-multi-lane`) first, then `scaffold all`, then skip. Before running, check
  nothing else is building in this checkout (another agent, a terminal): the script refuses the install while another
  Maven process runs, and `--force` is the user's call, never yours.
- **evals** (real tokens on the user's account, the model is not deterministic): run
  `scripts/plugin-check.sh evals --changed [BASE] --dry-run` first and show its case list and the cost note it prints
  from `evals/README.md`. Recommend `--changed` (one run per changed case); offer `--runs 1` as the cheap probe, the
  full default runs, or skip. Name the wider cases `changed` listed as optional, never as required.

Run only what the user picked.

### 3. On a failure, fix the cause, not the check

Map each FAIL to the row of the companion table in `essentials-plugin/CLAUDE.md` (§ Companion documents) that
governs the file, and fix what that row says. The common ones:

| Failure | Fix |
|---|---|
| `llm-sync`: `references/llm/` out of step | Edit `LLM/` only, run `scripts/sync-plugin-llm.sh`, commit both directories |
| `check-expected`: an anchor moved | Fix the fixture's `expected.yaml` lines it names and keep `TEST-GUIDE.md` in step, then `uv run --script essentials-plugin/evals/build.py` and commit the regenerated graders |
| `eval-graders`: graders drifted | Run `evals/build.py` (no `--check`) and commit; never hand-edit a `gen-*` grader or a `change-*` case |
| `render-slice`, `init-render`, `slice-source`, `slice-index`: a golden differs | Read the diff. Regenerate with that script's `--update-golden` (or `render-slice.py update-golden`) only when the change was intended, and review the regenerated diff |
| `ess-ids` | An id vanished or was reused: restore it, or retire it under `## Retired ids`; never renumber |
| `plugin-version` | Move `plugin.json`'s `version`: the `essentials.version` pin, or raise its `-N` suffix |
| `check-patches` | Re-cut the patch that no longer applies, and move its `expected.yaml` lines |
| scaffold build red | Open the log path the summary prints; a context-startup failure usually means an S2.1 dependency, and the fix goes in the contract first, then the tree |
| an eval case below threshold | Read the report's judge evidence first; a wrong oracle is fixed in `expected.yaml` / `cases.yaml` and regenerated, a real regression in the plugin. One red run of three is noise until it repeats |

**Never regenerate a golden or a grader just to turn a check green.** `essentials-plugin/CLAUDE.md` § Design
invariants: "Goldens are committed and byte-diffed; regenerating one is a review, not a fix … A golden regenerated to
make a red check green has stopped being an oracle." When the right fix is unclear, stop and ask.

After a fix, rerun the failing step alone (`scripts/plugin-check.sh step <name>`), then `quick` once more.

### 4. Hand off

Close with two lists:

- **Decide** — anything that needs the user's call: an expensive mode `changed` asked for that was skipped, a golden
  diff waiting for review, an eval red in one run, a `--force` over a running build.
- **FYI** — what ran and its result (one line per mode), SKIPPED checks and why, the wider eval cases left out.

End with the one next command to run.
