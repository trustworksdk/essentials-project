# Eval suite

The plugin's model-judgement steps, scored. The deterministic halves (`slice-lint.py`, `slice-source.py`,
`slice-index.py`, `render-slice.py`, `init-render.py`, the scaffold builds) have CI goldens; what a
command does *with* their output — the gates only a reader can judge, the report, the routing of a
change request, the TODOs a slice skill fills in — can only be checked by running the model. This
directory does that with `claude plugin eval`.

**Who runs it:** the maintainer, before each release. It is not a PR gate: it costs real tokens on
the maintainer's account, and the model is not deterministic, so a single red run is a question, not a
verdict.

## Run it

From the repository root, with Claude Code 2.1.269 or newer, git 2.31 or newer, `uv` and `python3` on
the `PATH`, and on Linux `bubblewrap` and `socat` (granting `Bash` runs every command in Claude Code's
OS sandbox, which needs them):

```bash
uv run --script essentials-plugin/evals/build.py --check   # graders match their expected results (no model call)

claude plugin eval essentials-plugin --scaffold --trust-plugin \
  --allow-tools Bash Write Edit "WebFetch(domain:pypi.org)" "WebFetch(domain:files.pythonhosted.org)" \
  --ablation none --judge-model sonnet --threshold 0.8 -j 4 --no-publish
```

`scripts/plugin-check.sh evals` runs both, with these flags, after checking the prerequisites above;
`--changed [BASE]` runs one case at a time for every case directory the change touched, `--dry-run` prints the
commands. The `eval-flags` step of `plugin-check.sh quick` (and CI) fails when this command and the script's differ.

| Flag | Why |
|---|---|
| `--scaffold` | Every case stages its project with a `scaffold.sh`; without the flag the model gets an empty directory |
| `--allow-tools Bash Write Edit` | The commands run the plugin's scripts, and the change and add-slice cases edit files. Nothing is granted by default |
| `WebFetch(domain:…)` pair | The sandbox blocks the network. `uv run --script` fetches the scripts' pinned `pyyaml`/`jsonschema` from PyPI; without these two a script cannot run, the command reports its gates as not run, and the case fails for that reason |
| `--ablation none` | The prompts are this plugin's own slash commands; the no-plugin arm has no such command, so its score measures nothing and doubles the cost. For the `change-*` cases the comparison is informative — drop the flag there if you want Δ |
| `--judge-model sonnet` | The graders read long reports; the default small judge is noisy on them |
| `--threshold 0.8` | Exit 1 when a case scores below it. Tune it per release; the default, 1.0, fails on any missed grader |
| `-j 4` | Four runs at a time on your one credential. Lower it if you hit rate limits |

Subsets: `--case 'slice-check-*'`, `--case 'change-*'`, `--tag add-slice` (`--case` takes one glob; given
twice, the last one wins). One cheap probe while editing a case: `--case <name> --runs 1`.

**Cost and time.** Measured with Opus as the agent and Sonnet as the judge, one run each: a `change-*`
case $0.22–0.34 in under a minute, `add-slice-java-command` $0.79 in 1.5 minutes,
`slice-check-worked-example` $1.09 in 2 minutes; the larger fixtures (multi-lane, aggregate-lane,
service-entity) and the review cases cost more. One pass over all 28 cases is roughly $20 and 40 minutes
of agent time, so the default three runs come to about $50–90 and 30–45 minutes at `-j 4`. Pass
`--max-cost-usd` for a hard ceiling; the summary table prints the real figure.

## Read the result

The summary table has one row per case: `SCORE` is the mean over its runs of the weighted share of
graders that passed, `PASS%` the share of runs at or above the threshold, and `NOTES` the first failing
grader. The report (`evals/results/<timestamp>/report.html`, git-ignored) shows every grader's verdict,
and for an `llm` grader the judge's three votes and the text it judged.

Grader names say where their expectation lives:

| Grader | Source |
|---|---|
| `gen-find-<id>` | a `must_find` entry in the fixture's `expected.yaml` — a finding the report must contain |
| `gen-not-<id>` | a `must_not_find` entry — a trap the report must not report |
| `gen-ess-<id>` | an `ESS-*` id a deterministic finding must print verbatim |
| `gen-lanes`, `gen-skipped`, `gen-expect` | the fixture's `lanes`, `skipped` and `runs[].expect` |
| `gen-dismissed-<id>`, `gen-not-run-<id>` | a review case's `dismissed` and `not_run` entries (`tests/review/judgement/*.expected.yaml`) |
| `gen-terrain`, `gen-contexts`, `gen-slices`, `gen-ladder` | `slice-discover`'s passes in `brownfield-layered/expected.yaml` |
| `gen-map-*` | `worked-example/expected.yaml` `slice_map` |
| `gen-no-write`, `gen-no-edit`, `gen-ran-<script>` | the case's `grading.yaml` (`read_only`, `runs_scripts`) |
| `change-*/graders/gen-*` | the case in `tests/fixtures/change-router/cases.yaml` |
| anything not starting `gen-` | hand-written, in the case directory (the add-slice cases) |

A failing grader is either a plugin regression or a wrong expectation. Read the judge's evidence first; if the
report is right and the expectation is wrong, fix `expected.yaml` or `cases.yaml` and the fixture's
`TEST-GUIDE.md` together, then regenerate. A case that fails in one run of three is noise until it
repeats.

## Layout and editing

```
evals/
  build.py              generates every gen-* grader and every change-* case; --check reports drift
  _lib/stage.sh         copies a fixture into the run workspace, without its expectation files
  <case>/case.yaml      the prompt, turn and time limits, allowed tools, the scaffold script
  <case>/scaffold.sh    stages the project the command runs against
  <case>/grading.yaml   which expected.yaml sections become graders (fixture cases)
  <case>/graders/       gen-* generated; others hand-written
  review-*/             /essentials:review's cases, same format
```

- **Never edit a `gen-*` grader or a `change-*` directory.** Edit the expected results —
  `tests/fixtures/<fixture>/expected.yaml` or `tests/fixtures/change-router/cases.yaml` — then run
  `uv run --script essentials-plugin/evals/build.py`. `--check` exits 1 on drift, 2 on a malformed expected or grading file.
- `claude plugin eval` has no custom-code graders and a grader cannot read a file outside the run,
  which is why the expected results are compiled into graders here rather than read at grading time.
- **The model never sees the expected results.** `stage.sh` leaves out `TEST-GUIDE.md`, `expected.yaml` and
  `cases.yaml`, and a run cannot read `evals/`. Fixture sources carry no finding labels either.
- A new fixture case: a directory with `case.yaml`, a two-line `scaffold.sh` calling `_lib/stage.sh`,
  and a `grading.yaml`; then regenerate. A new change-router case: a new entry in `cases.yaml`; then
  regenerate.
- After editing a case, run `claude plugin eval essentials-plugin --max-cost-usd 0 --scaffold
  --trust-plugin --allow-tools Bash Write Edit` once: it loads every case file and starts no run, so a
  malformed case fails there for free (the run exits 2 for the ceiling; a `case file(s) failed to load`
  line is the thing to look for).
