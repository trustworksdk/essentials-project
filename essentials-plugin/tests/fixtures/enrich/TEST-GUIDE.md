# `enrich` — what it is and what it is for

A small library application: the `lending` bounded context in Java on the decider lane, and an
`accounts` context in Kotlin. Its `slice.yaml` manifests predate schema 1.4, so they lack the
human-owned fields `/essentials:slice-enrich` drafts — `flow`, `scenarios`, `summary`, `invariants`,
`tier`, projection `consistency`, `featureFlag` and a translation's `maps` — while the sources and tests
carry the evidence for most of them, and the traps.

## What reads it

| Consumer | What it checks |
|---|---|
| `tests/scripts/test_slice_enrich.py` | The deterministic half: `slice-enrich.py propose` byte-diffed against `tests/slice-enrich/golden/propose.json` and `propose.txt`; `apply` of `tests/slice-enrich/accepted.json` against `golden/apply-dry-run.diff`, plus its refusals, `--dry-run` and idempotence |
| `evals/slice-enrich-library/` | The model's half — `commands/slice-enrich.md` Steps 4–7: which drafts it makes from the proposal, which it refuses, and how it asks. Graders generated from `expected.yaml` by `evals/build.py`; how to run and read it: `evals/README.md` |

The machine-readable version of the expectations below is `expected.yaml` next to this file.
`uv run --script tests/fixtures/check-expected.py` (from `essentials-plugin/`) checks it against the
tree and that no source carries an answer label.

## How the eval runs it

`/essentials:slice-enrich . --dry-run`, non-interactively. The prompt tells the model to print each
batch of questions it would have sent to AskUserQuestion and then to answer Yes to every journey and
every slice. So every draft the proposal supports goes into the accepted set, `apply --dry-run` prints
what it would write, and nothing is written (`gen-no-write`, `gen-no-edit`). The staged copy is a git
repository with one commit, so Step 1's dirty-tree check has nothing to say.

## Expected results

Line numbers are those in `expected.yaml`; ids in brackets are its entry ids.

### What the report must contain

| Expectation | Evidence |
|---|---|
| Three journeys, named. `reminders` extends the flow `schedule_reminder` already declares; members `borrow_book` (entry), `loan_reminders`, `loans_on_shelf`, `schedule_reminder` [E1] | `schedule_reminder/slice.yaml:11` `flow: reminders` |
| `catalogue-feed` (`catalogue_feed`, `register_copy`) and `return-book` (`return_book`, `loans_on_shelf`); the view sits in two journeys, so its flow is a list [E2] | the proposal's `journeys[]` |
| Invariants only from guard messages: `borrow_book` two [E3], `open_account` three [E4], `return_book` and `schedule_reminder` one each [E5] | `BorrowBook.java:16`, `BorrowBookDecider.java:24`, `OpenAccountHandler.kt:12,13,15`, `ReturnBookDecider.java:16`, `ScheduleReminderDecider.java:17` |
| `renew_loan` (`generator: sdd`) and `waitlist` (does not parse) skipped, with their reasons [E6] | `renew_loan/slice.yaml:8`; `waitlist/slice.yaml:11`, an unquoted path with `{isbn}` inside a flow mapping |
| `borrow_book`'s scenarios from its mapped tests, each linked to its test; the two with a null part completed from that method alone, or left out [E7] | `BorrowBookDeciderTest`, `ReturnBookAfterLoanTest` |
| `schedule_reminder`'s two scenarios from `ScheduleReminderTest`, a top-level test class mapped by its name [E8] | `ScheduleReminderTest.java:12,20` |
| Five slices no test maps to get a suggested test and no scenario [E9] | `register_copy`, `catalogue_feed`, `loan_reminders`, `loans_on_shelf`, `open_account` |
| `ShelfCountProjection`'s consistency reported refused by `apply` — the projection is a flow mapping [E10] | `loans_on_shelf/slice.yaml:20` |
| Questions grouped by context, at most four per batch [E11] | `lending` and `accounts` |

### Traps — what the report must not contain

| Trap | Where | Why it is not a draft |
|---|---|---|
| Invariant "memberId is required" [E-T1] | `BorrowBook.java:15` `requireNonNull` | a null guard, not a rule |
| Invariant "copyId is required" [E-T2] | `BorrowBookDecider.java:14-15` `if (… == null) throw` | a null check written out |
| Invariant "A reserved copy cannot be lent" [E-T3] | `BorrowBookDecider.java:23` | the throw is commented out |
| Invariant from Javadoc prose [E-T4] | `BorrowBook.java:11` | no code enforces it |
| Invariant "memberId is required" [E-T5] | `OpenAccountHandler.kt:10` `?: throw` | Kotlin null guard |
| Invariant "tier is required" [E-T6] | `OpenAccountHandler.kt:11` `requireNotNull` | null guard |
| Any draft for `renew_loan` [E-T7] or `waitlist` [E-T8] | their manifests | skipped by `propose` |
| Scenarios for `return_book` [E-T9] | `return_book/slice.yaml:17` `scenarios: []` | present, so the human's |
| Invariants for `register_copy` [E-T10] | `register_copy/slice.yaml:14` `invariants: []` | present, so the human's |
| `ReturnBookAfterLoanTest` attributed to `return_book` [E-T11] | its class name mentions `ReturnBook` | it lives in the `borrow_book` test package and drives `BorrowBookDecider`; `propose` maps it to `borrow_book` |
| A scenario from `LendingSmokeTest` or from production code [E-T12] | `LendingSmokeTest.java` | scenarios come from mapped tests only |
| A `with` on `zeroDayLoan`'s when [E-T13] | `BorrowBookFixtures.java:5` | the method passes a call, not a literal; values outside the method are not read |
| A `with` on `plansAReminder`'s `ReminderScheduled` [E-T14] | `ScheduleReminderTest.java:16` `channelFor("c-1")` | extracted without a `with`, and that part is not null |
| `ShelfCountProjection`'s consistency written, or hand-edited in [E-T15] | `loans_on_shelf/slice.yaml:20` | `apply` refuses it; the command never writes a refused value by hand |
| A drafted `notes`, `forbidden`, `sla`, `statutory`, `observability`, `profile` or `stability` [E-T16] | anywhere | `notInferable`: no code evidences them |

## Keeping it in step

A source or test edit that moves a cited line: `uv run --script tests/fixtures/check-expected.py` names
the stale `expected.yaml` entries; fix them and this file together, then `uv run --script
evals/build.py`. A change to what `propose` reports is a golden change first
(`uv run --script tests/scripts/test_slice_enrich.py`), and only then an expectation change here.
