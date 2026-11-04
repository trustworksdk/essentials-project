# Phase 8 design - demo control

**Status:** decided (2026-11-04) - every proposal accepted, and Q-8 added: the page explains what it shows. Decisions
are marked **(Q-n)** in the text and collected in §8.
**Plan:** [`../EXPANSION_PLAN.md`](../EXPANSION_PLAN.md) phase 8 ("Demo control").
**Builds on:** the exchange simulator's clock (operator and test clock endpoints, already in the generated client), the
phase 6 load driver's behaviour mix, the phase 7 customer UI and its page conventions.

Today a demo of a trading day needs a terminal and good timing: the exchange simulator's clock runs at its own pace,
traffic comes from `./run-load.sh` (a separate JVM that runs a fixed number of days and then reports), and nothing shows
the operator what the system is doing as a whole. Phase 8 gives the operator one page that runs the show:

- **play a trading day whenever it suits the audience** - move the exchange to just before the open and run the day at a
  chosen speed, through the open, trading, the close and the end-of-day processing that follows, whatever the wall-clock
  time is;
- **keep the system busy** - simulated customers trading and moving money continuously in the background, at a rate the
  operator can change while it runs, so Alice's screens and the operator views have something to show.

It is a demo tool. Measurements stay with `run-load.sh`, which this phase does not change.

---

## 1. What exists

- **The exchange clock is the system's clock for business time.** Business days, the books roll at each close,
  settlement (T+1), day-order expiry and deposit clearing all follow the exchange's calendar and close events, not the
  wall clock. The simulator's clock reports `{mode, now, running, speed}` and can be set (`to`), advanced
  (`days/hours/minutes/seconds`), frozen, resumed and sped up - through the operator endpoints and the test endpoints of
  the generated client. The load driver already advances it day by day.
- **Traffic generators.** The phase 6 load driver (external, finite, reports pass/fail) and the older in-app generators
  under `_demo_harness` (funding, order flow, ledger, queue) - short measured runs, started through `/api/admin/...`, not
  continuous traffic.
- **Operator UIs.** The Essentials admin console (`/essentials/admin`) shows the framework - queues, subscriptions,
  locks. Nothing shows the demo itself: the exchange's time and session, the traffic, the order and payment flow.

## 2. The page (Q-1)

**Proposed:** an operator page at `/ops`, built like the customer UI (Thymeleaf shell, vanilla JS over JSON endpoints,
polling while visible; phase 7 §2, §4), in the `_demo_harness` package's web side or a sibling `_ops_ui` package (§7).
Three panels:

1. **Exchange clock** - the exchange date and time, the session (pre-open, open, closed; holiday or weekend), speed and
   whether it runs; the controls of §3.
2. **Background traffic** - running or not, the rate and mix, live counts (orders placed, filled, cancelled, rejected;
   payments requested and completed) over the last minute; start, stop, change rate, fire a burst (§4).
3. **System at a glance** - read from existing views: orders by status, payments by status, open reconciliation cases,
   last close processed. Links to the Essentials admin console for the framework detail.

Operator role only (Q-6).

**Explanations on the page (Q-8).** The page is shown to audiences, so it explains itself rather than assuming the
operator narrates:

- each panel opens with a short paragraph on what it shows and why it matters (the exchange clock is simulated and is
  the system's business time; the traffic is simulated customers using the same commands as real ones);
- each control says what it will do before it is used (for example: "Skip to next open - moves the exchange to 5
  minutes before the next session opens; every close in between is processed first");
- what is happening now is put in words, not only figures: "Market open - 14:32 exchange time, 60x: an hour passes
  every minute", "After the close: books rolling, trades settling", "Fast-forwarding: day 2 of 3, waiting for the
  close to be processed";
- a short "what happens during a trading day" section: pre-open, the open and its burst, continuous trading, the close,
  and what the system does after it (books roll, T+1 settlement, day orders expire, deposits clear, reconciliation runs),
  each step linked to where it can be seen - Alice's screens, the panels, the Essentials admin console.

## 3. Clock control (Q-2, Q-3)

**Forward only (Q-2).** The ledger keeps one generation per business day and rolls it at each close; settlements,
expiries and clearings are tied to the days they happened on. A clock moved backwards would contradict recorded facts.
Going back means starting over (`reset-stack.sh`, which also resets the simulators); the page says so instead of
offering it.

**Proposed controls (Q-3):**

| Control | Does |
|---|---|
| Pause / resume | Freezes or resumes the exchange clock. |
| Speed | 1x, 10x, 60x, 300x - a trading day in 6.5 h, 39 min, 6.5 min or 78 s. |
| Skip to next open | Moves the clock to a few minutes before the next session opens, so the open is seen happen. |
| Skip to next close | Moves to a few minutes before the close. |
| Advance N business days | Fast-forward through each day in turn (§3.1). |

Jumps are implemented as **fast-forward, not a set**: every close between now and the target happens, so the books roll,
settlements settle, day orders expire and deposits clear exactly as they would have. A single `setClock` past a close
would skip that day's end-of-day processing. The page shows progress while it runs and disables the other controls.

### 3.1 To verify while building

- Fast-forwarding several days quickly: the load driver does it already, but with its own pacing; the page's version must
  wait for each close to be processed (the last close the app has processed, read from a view) before the next.
- Starting the application while the exchange is closed, on a weekend or a holiday, and after a long pause: what the app
  does on its first close event, and that nothing waits for a close that was skipped.
- Orders placed outside the session (by the background traffic or Alice): accepted and held until the open, or rejected
  - whatever the exchange does, the UI must show it understandably.

## 4. Background traffic (Q-4, Q-5)

**Proposed (Q-4): in the application**, as a `_demo_harness` component started and stopped from the page, not the
external load driver launched from the page. One button, no second process to manage, survives a page reload; the cost
is that it shares the application's JVM and database pool - fine for a demo, which is why measurements stay with
`run-load.sh`.

It reuses the load driver's model (phase 6 §2): a population of simulated customers, each with an account opened and
funded on first use, choosing market and limit buys and sells, resting orders and cancels, deposits and withdrawals in
the driver's proportions - through the command bus, the way the in-app generators already do, rather than over HTTP.
Unlike the driver it never ends and keeps no report: it runs until stopped.

**Rate and market hours (Q-5).** The rate is set in orders per *exchange* minute, so speeding the clock up speeds the
traffic with it and a day looks like a day at any speed (capped at what the instance takes). Outside the session the
customers only move money; orders resume at the open, with a burst at the open like the driver's market-open burst.

Its customers are separate from the seeded demo users (an id prefix of their own), so Alice's screens show only her
account.

## 5. Endpoints

Under `/api/admin/demo/**` (operator, CSRF as the operator API, phase 7 §5):

- `GET clock`, `POST clock/pause`, `POST clock/resume`, `PUT clock/speed`, `POST clock/skip {to: NEXT_OPEN|NEXT_CLOSE}`,
  `POST clock/advance {businessDays}` - wrapping the simulator's operator clock endpoints; the skip and advance run as
  one job at a time, with `GET clock` reporting its progress.
- `GET traffic`, `POST traffic/start {ordersPerExchangeMinute, mix?}`, `PUT traffic/rate`, `POST traffic/burst`,
  `POST traffic/stop`.

## 6. Multiple instances

The clock is the simulator's, so any instance's page sees and moves the same clock; the fast-forward job runs on the
instance that started it. Background traffic is per instance in this proposal (started on the instance whose page
started it). A cluster-wide singleton (fenced lock) is possible later if needed.

## 7. Where the code lives

`_demo_harness` already owns the generators and the operator-side harness endpoints; the traffic generator goes there.
The page itself: proposed in `_demo_harness` too (templates under `templates/ops/`, assets under `static/ops/`), since
it is harness tooling. Imports follow the harness's existing practice: command types to dispatch, and contexts' `types`.

## 8. Decisions

| | Question | Decided |
|---|---|---|
| **Q-1** | Page | Operator page `/ops`: clock, background traffic, system at a glance; Thymeleaf + vanilla JS (§2) |
| **Q-2** | Clock direction | Forward only; going back = reset the stack (§3) |
| **Q-3** | Clock controls | Pause/resume, speed presets, skip to next open/close, advance N business days - jumps fast-forward through every close (§3) |
| **Q-4** | Where traffic runs | In the application, from `_demo_harness`, via the command bus; `run-load.sh` stays for measurement (§4) |
| **Q-5** | Traffic pacing | Rate per exchange minute, follows clock speed; money moves outside the session, orders resume at the open (§4) |
| **Q-6** | Access | Operator role only (§2) |
| **Q-7** | Testing | IT for the endpoints (clock wrapper against the simulator container, traffic start/stop); the page checked by hand in a browser: play a day at 60x outside wall-clock market hours |
| **Q-8** | Explanations | The page explains each panel, each control and the current state in words, plus a "what happens during a trading day" section (§2) |
