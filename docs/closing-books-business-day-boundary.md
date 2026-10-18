# EF-1 — Business-Day Closing-Books Boundary

**Status:** design proposal, not implemented. No code has changed. Questions 1, 2, 4, 5 and 6 of §8 are
decided (see "Decided" there); 3 and 7–9 are still open.
**Module:** `components/eventsourced-aggregates` (package `…aggregates.closingbooks`), with small additive
changes in `spring-boot-starter-postgresql-event-store` and the admin API contract.
**Driven by:** the trading demo expansion, decisions D10, D20 and D22 in
`examples/essentials-trading-demo/docs/EXPANSION_PLAN.md` § "Essentials features this plan needs", and
SR-24..SR-26 in `examples/essentials-trading-demo/docs/SIMULATOR_REQUIREMENTS.md` § 8.

A closing-books time boundary today is "a new calendar period has started on a `java.time.Clock` in a
zone". A broker's books do not roll at midnight. They roll at the clearing cutoff of the exchange's
business day (for example 17:30 Europe/Copenhagen), a trade after the cutoff belongs to the *next*
business day, weekends and holidays are not business days at all, and the authoritative signal that a
day ended is an external `BusinessDayClosed` event (`businessDate`, `cutoffAt`, `nextBusinessDate`)
delivered by webhook — possibly on a virtual clock that runs a simulated day per real minute. This note
proposes an additive way for an application to supply that calendar, so that closing-books evaluation
compares an aggregate's period with the calendar's current business date instead of with
`LocalDate.now(clock)`.

---

## 1. How period boundaries and period ids work today

### 1.1 The declaration

`@AggregateClosingBooksPolicy` (`AggregateClosingBooksPolicy.java:38-54`) carries `triggerMode`,
`defaultPolicy`, `eventThreshold`, `timeBoundary` (default `NONE`, line 47), `zoneId` (default `"UTC"`,
line 49) and `intervalDays`. `ClosingBooksTimeBoundary` (`ClosingBooksTimeBoundary.java:22-52`) has six
values: `NONE`, `END_OF_DAY`, `END_OF_WEEK`, `END_OF_MONTH`, `END_OF_YEAR`, `EVERY_N_DAYS`.

The annotation is *configuration the application reads*, not something the container acts on. The
module's `CLAUDE.md` (line 112) states it directly: the framework never constructs
`BuiltInClosingBooksPolicyEvaluator`; application code builds it. The Spring starter's part is:

| Piece | What it does |
|---|---|
| `AggregateDeclarationPolicyRegistrar` / `AggregateClosingBooksPolicyBeanPostProcessor` | Copy the annotation into `AggregateClosingBooksPolicyRegistry` as an `AggregateClosingBooksPolicyDescriptor` |
| `DefaultAggregateClosingBooksConfigurationResolver.resolve` (`:49-131`) | Merges per-aggregate properties > annotation > global properties into a `ResolvedAggregateClosingBooksConfiguration` record (`ResolvedAggregateClosingBooksConfiguration.java:39-47`). `timeBoundary` at `:93-97`, `zoneId` at `:99-103` |
| `DefaultAggregateLifecycleConfigurationValidator` (`:125-165`, zone check `:225+`) | Fails startup on: `SCHEDULED_SCAN` without a `FencedLockManager`; missing next-generation factory; time-boundary policy with `timeBoundary = NONE`; time-boundary policy without `HasClosingBooksPeriodId` (opt-out: `period-id-provided-externally`); invalid `zoneId` |
| `EssentialsEventStoreProperties` (`:1205-1208` global, `:1294-1302` per aggregate) | `essentials.eventstore.closing-books[.aggregates.<Type>].time-boundary / zone-id / interval-days / period-id-provided-externally` |

### 1.2 The period id

The period id is **not** stored by the framework. It lives in the aggregate's own state and is exposed
either by implementing `HasClosingBooksPeriodId.closingBooksPeriodId()` (`HasClosingBooksPeriodId.java:32-36`)
or by a `currentPeriodIdProvider` function handed to the evaluator. The generation table
(`PostgresqlClosingBooksGenerationRepository`, DDL at `:164-181`) holds generation number, stream id,
state, timestamps and `next_scan_ts` — no period column. `AggregateGeneration` (`AggregateGeneration.java:30-36`)
likewise has no period.

The format is fixed per boundary (`ClosingBooksTimeBoundaryCalculator.java:202-210`):

| Boundary | Period-id format | Distance function |
|---|---|---|
| `END_OF_DAY` | `yyyy-MM-dd` | `dayDistance` (`:275-279`) — calendar days, clamped at 0 |
| `EVERY_N_DAYS` | `yyyy-MM-dd` (anchor = current period start) | `resolveFixedIntervalEvaluation` (`:155-171`) |
| `END_OF_WEEK` | `yyyy-Www` | `weekDistance` (`:281-285`) |
| `END_OF_MONTH` | `yyyy-MM` | `monthDistance` (`:287-290`) |
| `END_OF_YEAR` | `yyyy` | `yearDistance` (`:292-295`) |

A brand-new aggregate gets its first period id from `ClosingBooksTimeBoundaryCalculator.currentPeriodId(...)`
(`:62-78`), so application code never hand-formats it.

### 1.3 Evaluation

`ClosingBooksTimeBoundaryCalculator.evaluate(timeBoundary, zoneId, clock, currentPeriodId, intervalDays)`
(`:113-149`) computes "today" as `LocalDate.now(clock.withZone(zoneId))` (`:122`), validates the stored
period id against the boundary's format (`:180-200`, an `IllegalArgumentException` on mismatch — a runtime
failure, not a startup one), and returns a `ClosingBooksTimeBoundaryEvaluation(resolvedPeriodId, advancedPeriods)`
(`ClosingBooksTimeBoundaryEvaluation.java:27-43`). `boundaryAdvanced()` is `advancedPeriods > 0`;
`gapDetected()` is `advancedPeriods > 1`.

`BuiltInClosingBooksPolicyEvaluator` wraps that:

- `shouldRolloverOnAccess(aggregate)` (`:179-187`) — event threshold and/or `timeBoundaryEvaluation(aggregate).boundaryAdvanced()`.
- `nextPeriodId(aggregate)` (`:196-199`) — the `resolvedPeriodId`, which the application passes as the
  seed of the next generation.
- `timeBoundaryEvaluation` (`:237-264`) — short-circuits on `NONE` (`:239-241`); on a gap it logs at INFO
  and increments `essentials.closing_books.time_boundary_gap_detected` tagged with `aggregate_type`,
  `time_boundary`, `policy_type` (`:249-262`).
- `asDecisionPolicy()` (`:213-217`) — returns `CLOSE_AND_OPEN_NEXT` **only** when the trigger mode is
  `ON_ACCESS`; for `SCHEDULED_SCAN` and `EXPLICIT_COMMAND` it always returns `KEEP_OPEN`.
- `description()` (`:201-211`) — `"time-boundary end-of-day in zone Europe/Copenhagen"`, used in logs.

A "gap" therefore means "more than one boundary was crossed since the aggregate last rolled", e.g. an
account untouched for three days under `END_OF_DAY` jumps straight from `2026-10-14` to `2026-10-17`. The
skipped periods are not materialised as empty generations; the gap is only logged and counted.

### 1.4 Trigger modes

- **`ON_ACCESS`** is not driven by the framework either. In the demo, `TradingAccounts.getAccountForMutation`
  (`TradingAccounts.java:94-114`) loads the account, asks `TradingAccountClosingBooksPolicy.shouldRolloverOnAccess`,
  and if true calls `account.closeBooks(nextPeriodId)` (`TradingAccount.java:188-196`) and
  `ClosingBooksLogicalAggregateRepository.closeAndOpenNextGeneration(id, account, hint, factory)`
  (`ClosingBooksLogicalAggregateRepository.java:212-227`), with the next period id as the hint.
- **`SCHEDULED_SCAN`** runs `ClosingBooksManager` under a fenced lock (`ClosingBooksManager.java:130`);
  `DefaultClosingBooksScheduledScanProcessor.processNextBatch` (`:140-175`) loads the oldest open generations
  and calls `ClosingBooksCoordinator.evaluatePolicy(..., SCHEDULED_SCAN, policy)` (`:202-207`). The
  coordinator evaluates under the generation lock (`ClosingBooksCoordinator.java:171-205`) and, on
  `CLOSE_AND_OPEN_NEXT`, calls `closeAndOpenNextGeneration(logicalAggregateId)` (`:141-166`), which only rolls
  the **generation row**: no next-generation factory runs and no period hint reaches the new generation.

Two consequences matter for this design and exist today, independent of business days:

1. The built-in evaluator cannot drive a scheduled scan as-is, because `asDecisionPolicy()` answers
   `KEEP_OPEN` for `SCHEDULED_SCAN`. `LLM/LLM-eventsourced-aggregates.md:933` says scans are "required …
   for time-boundary policies on idle aggregates", which the built-in evaluator does not currently deliver.
2. A scan-driven rollover produces a new generation whose stream is empty — no carried-forward balance and
   no period id. For a period-bearing aggregate the scan path is only half a rollover.

### 1.5 Where the trading demo is now

`TradingAccount` is annotated `ON_ACCESS`, `EVENT_COUNT_OR_TIME_BOUNDARY`, threshold 100,
**`END_OF_MONTH`**, zone `Europe/Copenhagen` (`TradingAccount.java:76-81`); D20 moves it to `END_OF_DAY`.
`PeriodId` is a `CharSequenceType` (`brokerage/types/PeriodId.java`). `TradingAccountClosingBooksPolicy`
builds a **new** `BuiltInClosingBooksPolicyEvaluator` on every call (`TradingAccountClosingBooksPolicy.java:172-186`)
with the application's `Clock`, which is `Clock.systemDefaultZone()` (`TradingDemoApplication.java:85-86`).
External events arrive through `ExternalEventIntake` (dedup row + `Inbox` message in one unit of work).

---

## 2. Requirements for a business-calendar boundary

| # | Requirement | Notes |
|---|---|---|
| R1 | The **current period comes from the application**, not from `Clock` + zone. | D10: business dates come from simulator events, never our clock. |
| R2 | The calendar is **advanced by an external signal** (`BusinessDayClosed`). | The library must not care how; the demo uses a webhook → `Inbox`. |
| R3 | `ON_ACCESS` compares the aggregate's period with the **calendar's current business date**. | Same contract as today: `shouldRolloverOnAccess`, `nextPeriodId`. |
| R4 | `SCHEDULED_SCAN` works with the same calendar. | Needs the §1.4 gaps addressed, or an explicit decision to leave them. |
| R5 | **Gap detection counts business days, not calendar days.** Friday → Monday is one period, not three. | `advancedPeriods` = number of business days closed between the two periods. |
| R6 | **Idempotent** under a redelivered `BusinessDayClosed`. | Re-applying the same close changes nothing. |
| R7 | **Out-of-order tolerant.** Webhook subscriptions are unordered (D11); a late close for an older day must not move the calendar backwards. | The calendar is monotonic; a late close may still fill a hole in the business-day count. |
| R8 | **Multi-instance agreement** on the current business date, with a stated, bounded disagreement window. | §5. |
| R9 | **Cheap on the hot path.** Evaluation runs on every mutating command. | No I/O per evaluation; §7 says what to measure. |
| R10 | **Strictly additive.** No signature, default, enum, or contract change that breaks a compiled or recompiled consumer, or the admin API compatibility gate. | Stable-API rule in the root `CLAUDE.md`. |
| R11 | Period ids stay compatible with the existing formats. | An aggregate should be able to move from `END_OF_DAY` to business days without re-seeding. |

---

## 3. SPI design options

### Option A — a business calendar behind the existing `END_OF_DAY` boundary (recommended)

Add one SPI and one optional collaborator. The boundary stays `END_OF_DAY`; what changes is *whose day*.

```java
/** The business days a closing-books END_OF_DAY boundary rolls on, supplied by the application. */
public interface ClosingBooksBusinessCalendar {
    /** The business date currently open — the date work done now books into. */
    LocalDate currentBusinessDate();

    /**
     * How many business days closed going from {@code from} to {@code to}: the number of closed business
     * dates d with from <= d < to. 0 when to <= from. Never counts non-business days.
     */
    long businessDaysClosedBetween(LocalDate from, LocalDate to);
}
```

Additive touch points:

| Where | Change |
|---|---|
| `ClosingBooksTimeBoundaryCalculator` | New static overloads `currentPeriodId(ClosingBooksBusinessCalendar)` and `evaluate(ClosingBooksBusinessCalendar, String currentPeriodId)`, returning the existing `ClosingBooksTimeBoundaryEvaluation`. The `yyyy-MM-dd` validation is reused. |
| `BuiltInClosingBooksPolicyEvaluator.Builder` | New `setBusinessCalendar(ClosingBooksBusinessCalendar)`. When set and `timeBoundary == END_OF_DAY`, `timeBoundaryEvaluation` delegates to the calendar overload instead of `Clock`/zone. Any other boundary with a calendar set is rejected in `build()` (see open question Q3). The existing package-private constructors stay. |
| `description()` | `"time-boundary end-of-day on business calendar"` when a calendar is set. |
| Gap metric | Same counter, plus a tag `period_source=business_calendar\|clock` — a new tag on an existing meter is additive for dashboards that do not group by it. |
| Declaration | New annotation attribute `ClosingBooksPeriodSource periodSource() default ClosingBooksPeriodSource.CLOCK` with a new enum `ClosingBooksPeriodSource { CLOCK, BUSINESS_CALENDAR }`; matching property `period-source`. A new annotation member with a default is binary- and source-compatible. |

Pros:

- **Period ids do not change.** A business date is a `LocalDate`, so the id is the same `yyyy-MM-dd` as
  `END_OF_DAY`. An account seeded under `END_OF_DAY` can switch to the calendar with no migration (R11).
- **No change to `ClosingBooksTimeBoundary`.** That enum is exposed in the admin OpenAPI contract
  (`essentials-admin-api.yaml:3078-3086`), and the contract gate's openapi-diff 2.1.7 classifies a value
  added to a *response* enum as `RESPONSE_ENUM_INCREASED` → `INCOMPATIBLE` (verified in `ChangedEnum`'s
  bytecode). Option A leaves that enum untouched; it adds a property instead, which the gate treats as
  compatible.
- The admin API stays truthful: `timeBoundary = END_OF_DAY` is literally correct, `periodSource` says whose day.
- Holidays and weekends are the calendar's business, not the library's. The library learns no calendar rules.

Cons:

- Two attributes to understand where one would read more simply (`END_OF_DAY` + `BUSINESS_CALENDAR`).
- Business *weeks/months* (roll at the last business day of the month) are not covered; they would need
  the calendar to answer more questions. Deferred (Q3).

### Option B — a new boundary value `BUSINESS_DAY` plus a calendar bean

`ClosingBooksTimeBoundary.BUSINESS_DAY`, period id `yyyy-MM-dd`, evaluated against the same
`ClosingBooksBusinessCalendar`, declared as `timeBoundary = BUSINESS_DAY`.

Pros: one attribute, reads naturally in the annotation and in `time-boundary=business-day`. The validator's
"`NONE` never rolls" check and the evaluator's `description()` work unchanged.

Cons — it is not strictly additive:

- **Admin API contract.** `ApiAggregateClosingBooksPolicy.timeBoundary` serialises the enum; the new value
  widens a response enum and fails `OpenApiContractCompatibilityTest`. Per `components/admin-api-spec/CLAUDE.md`,
  passing it means re-seeding the `v2` baseline as a *deliberate break*, and regenerating
  `admin-api-client-java`; an older generated client deserialising `BUSINESS_DAY` may fail.
- **Exhaustive switches.** Adding an enum constant is binary-compatible (JLS 13.4.26), but a consumer's
  exhaustive `switch` expression over `ClosingBooksTimeBoundary` with no `default` stops compiling on
  upgrade, and an already-compiled one throws `MatchException` when it meets the new value. The library has
  four such switches in the calculator alone (`:71`, `:123`, `:185`, `:203`).
- The calculator's `Clock`-based paths have no meaning for `BUSINESS_DAY`; every one has to throw
  "needs a calendar", which is a new runtime failure mode on an old method.

### Option C — a fully external trigger (no boundary)

No period source in the library. The `BusinessDayClosed` handler fans out an explicit close-books command
to every account (`EXPLICIT_COMMAND`), or kicks a scheduled scan.

Pros: zero library change for the trigger itself; books for idle accounts roll on time (useful if the
demo wants a statement per account per day regardless of activity).

Cons:

- Fan-out cost scales with accounts × days. On the accelerated clock (a day per real minute) 10 000
  accounts is 10 000 rollovers per minute whether or not anything happened — the opposite of ON_ACCESS's
  "roll only what is touched".
- It does **not** fix the boundary for activity in the window between the cutoff and the fan-out finishing:
  a trade processed before its account's close command is booked into the old period. ON_ACCESS
  evaluation against a calendar closes that window for every account at once, the moment the calendar moves.
- The scan path cannot do it today anyway (§1.4: `asDecisionPolicy()` never rolls on a scan, and a scan
  rollover seeds no period id).

Option C is a complement, not an alternative: a demo that wants daily statements for idle accounts can run
it *on top of* A.

### Rejected variant — a generic `ClosingBooksPeriodSource` returning arbitrary period ids

`String currentPeriodId(); long periodsBetween(String from, String to)`. Maximally general (fiscal
periods, shifts), but it severs the period-id format from anything the library can validate, and every
check in §1.3 (`validateCurrentPeriodId`) becomes "trust the source". Nothing in the demo needs more than
business *days*. If a second use case appears, A's calendar can be adapted into such a source later
without breaking A.

### Recommendation

**Option A.** It satisfies R1–R11 with only new types, new builder setters, a new annotation member with a
default, new properties and a new response field. It keeps the period-id format, so the demo's move from
`END_OF_DAY` (D20) to business days (D22) is a configuration change, not a data migration. B buys a
slightly nicer declaration at the price of an admin-contract break and a source break for exhaustive
switches; that is a next-major trade.

---

## 4. Period-id derivation and compatibility

- **Derivation.** `periodId = calendar.currentBusinessDate().format(ISO_LOCAL_DATE)`. The business date is
  the one *opened by* the last close (`nextBusinessDate`), not the calendar date of `now`. Friday's trades
  after the 17:30 cutoff carry Monday's period id.
- **Evaluation.** `advancedPeriods = calendar.businessDaysClosedBetween(parse(currentPeriodId), currentBusinessDate)`;
  `resolvedPeriodId = currentBusinessDate`. With closes Fri→Mon: an account at `Fri` evaluated on `Mon`
  gets `advancedPeriods = 1` (no gap). An account at `Wed` evaluated on `Mon` gets 3 (gap, two skipped
  business days: Thu, Fri).
- **Robustness.** Counting *closed business dates in `[from, to)`* rather than ordinal subtraction means a
  period id that is not itself a business date (an account seeded on a Saturday from the old calendar-day
  boundary) still evaluates sensibly: it counts every business day closed since.
- **Clock going backwards / virtual clock reset.** As today (`dayDistance` clamps at 0, `:277`), a
  `to <= from` yields 0 and never closes books. A test-only `reset` of the exchange clock (SR-24) would
  leave accounts "in the future"; they stay put until the calendar passes them. That matches today's
  semantics.
- **Compatibility.** The format is the existing `END_OF_DAY` format, so `HasClosingBooksPeriodId`,
  `PeriodId`, persisted events (`AccountBooksClosed.nextPeriodId`) and the validator's format rules are
  unchanged. Switching back from `BUSINESS_CALENDAR` to `CLOCK` is also safe.
- **Seeding a new aggregate.** `ClosingBooksTimeBoundaryCalculator.currentPeriodId(calendar)` — the
  business-calendar counterpart of `:62-78` — so a new account starts in the open business day, not today's
  calendar date.

---

## 5. Multi-instance agreement

### 5.1 What the calendar's state is

Two facts per closed day: `businessDate` → `nextBusinessDate` (and `cutoffAt` for diagnostics).

- **Current business date** = max(`nextBusinessDate`) over all recorded closes. Monotonic by construction,
  so an out-of-order close never moves it backwards (R7), and re-recording a close is a no-op (R6).
- **Business days closed between** = count of recorded `businessDate`s in `[from, to)`. A late, older close
  fills a hole in the count when it arrives.
- **Chain integrity.** Every recorded close except the newest should have its `nextBusinessDate` recorded
  as a later close's `businessDate`. A break means a close was never received; the current date is still
  right, the gap count under-reports until the missing close arrives. Expose it as a gauge.

A conflicting redelivery (same `businessDate`, different `nextBusinessDate`) is a source error: keep the
first, log at WARN, count it.

### 5.2 Where it lives

| Option | Consistency | Hot-path cost | Notes |
|---|---|---|---|
| **a. DB table, read on every evaluation** | Strict w.r.t. commit order: a command that reads after the close committed sees it. | One PK/aggregate query per mutating command. | Simplest correct; the cost is what R9 is about. |
| **b. DB table + in-memory snapshot per instance, invalidated by `LISTEN/NOTIFY`, with a periodic poll backstop** (recommended for the demo) | Eventually consistent; window = commit → NOTIFY delivery (normally ms), worst case = poll interval if a notification is lost. | A `volatile` read. | Foundation already has `ListenNotify` / `MultiTableChangeListener`. Writer updates the row in the same unit of work as the `Inbox` dedup. |
| **c. Fenced singleton owns the calendar, others ask it** | Strict at the owner. | A remote call per evaluation, or (b) anyway. | Adds a network hop or reduces to (b). No gain. |
| **d. Per-instance projection from an event-store subscription** (non-exclusive `subscribeToAggregateEventsAsynchronously`) | Eventually consistent; window = subscription lag (poll-based, typically 10s–100s ms). | A `volatile` read. | Needs `BusinessDayClosed` stored as an event, and one resume point per instance. |

The library should **not** own the store. It ships the SPI and, at most, an in-memory reference
implementation (`InMemoryClosingBooksBusinessCalendar` with `recordClose(businessDate, nextBusinessDate)`,
thread-safe, `volatile` snapshot) that (b) or (d) can feed. Persisting `BusinessDayClosed` is the
application's integration concern and depends on how it ingests external events.

### 5.3 The disagreement window

Between "instance A has seen the new day" and "instance B has": a mutation on B evaluates the old date,
does not roll, and books into the closed day. The next access after B refreshes rolls the account. So the
window costs *some post-cutoff mutations booked into the closing day*. It should never cost a lost or
doubled rollover: the generation row change runs under the generation lock
(`ClosingBooksCoordinator.java:141-166`), and two instances that both decide to roll from the same
generation both append `AccountBooksClosed` to the same stream, so optimistic concurrency should reject
the second. The demo calls the rollover outside `evaluatePolicy`, so this depends on the generation-row
write and the event append sharing one unit of work. That is not verified here; the contention IT in §7.2
must prove it.

That window is **not unique to multiple instances**, and it is not the biggest one. Even with one instance,
the exchange emits `BusinessDayClosed` at the cutoff and we receive it after webhook latency, and because
deliveries are unordered (D11), a post-cutoff `TradeExecuted` can arrive *before* the close. NOTIFY lag
(ms) is small next to that. The only way to make period assignment exact is **event time**: let a
mutation that carries its own business date evaluate against *that* date instead of the calendar's
current one. That needs (i) the exchange to stamp trades with their business date — SR-26 does not say it
does (Q1) — and (ii) an evaluator overload such as `shouldRollover(aggregate, LocalDate asOfBusinessDate)`.
Both are additive and orthogonal to the calendar; this note recommends designing the SPI so the overload
fits, and deciding after Q1.

### 5.4 Cold start

An instance starting with an empty cache must load the table before serving commands, or every
evaluation sees "no business date". The SPI should allow `currentBusinessDate()` to signal "unknown"
(throwing a dedicated, retryable exception), and the evaluator should then **not** roll — treating
"unknown" as "unchanged" (Q5). The demo additionally reads `GET /api/clock` at startup (SR-24), but that
gives time, not the business date; a "current business date" on the exchange's API would close this (Q1).

---

## 6. Declaration surface

### 6.1 Annotation and properties

```java
@AggregateClosingBooksPolicy(aggregateType = "TradingAccounts",
                             triggerMode   = ClosingBooksTriggerMode.ON_ACCESS,
                             defaultPolicy = ClosingBooksDefaultPolicyType.EVENT_COUNT_OR_TIME_BOUNDARY,
                             eventThreshold = 100,
                             timeBoundary  = ClosingBooksTimeBoundary.END_OF_DAY,
                             periodSource  = ClosingBooksPeriodSource.BUSINESS_CALENDAR)
```

```properties
essentials.eventstore.closing-books.aggregates.TradingAccounts.period-source=business-calendar
```

The application still builds the evaluator (unchanged principle) and passes its calendar:

```java
BuiltInClosingBooksPolicyEvaluator.<TradingAccount>builder()
        ...
        .setTimeBoundary(ClosingBooksTimeBoundary.END_OF_DAY)
        .setBusinessCalendar(businessCalendar)
        .build();
```

### 6.2 Starter changes (all additive)

| Item | Change |
|---|---|
| `EssentialsEventStoreProperties` | `periodSource` on the global and per-aggregate closing-books properties, default `CLOCK`. Metadata comes from the configuration processor (`-proc:full`). |
| `DefaultAggregateClosingBooksConfigurationResolver` | Resolve `periodSource` with the same per-aggregate > annotation > global ladder. |
| `ResolvedAggregateClosingBooksConfiguration` | New record component `periodSource`. Adding a component changes the canonical constructor, which **breaks** callers — the demo's own test constructs this record (`TradingAccountClosingBooksPolicyTest.java:187`). Keep the old 7-argument constructor as an explicit secondary constructor defaulting to `CLOCK`. |
| `DefaultAggregateLifecycleConfigurationValidator` | Append (after the existing checks — order-sensitive, per the module `CLAUDE.md`): `BUSINESS_CALENDAR` requires `timeBoundary = END_OF_DAY`; with `BUSINESS_CALENDAR` the zone check is skipped. Optionally: a `ClosingBooksBusinessCalendar` bean must exist when any aggregate resolves to `BUSINESS_CALENDAR` — but the framework does not build the evaluator, so it cannot prove the calendar is wired *into* it. |
| Auto-configuration | None required. Optionally a `@ConditionalOnMissingBean` `InMemoryClosingBooksBusinessCalendar` — not recommended, because an empty calendar silently never rolls (Q5). |

### 6.3 Admin API and UI

- `ApiAggregateClosingBooksPolicy` (`ApiAggregateClosingBooksPolicy.java:22-45`) is built from the
  **annotation** descriptor (`from(...)`, used at `DefaultAggregateLifecycleApi.java:76`), served by
  `GET /aggregate-lifecycle/closing-books-policies` (`EssentialsAdminApiSpec.java:602-606`). Add a
  `periodSource` component, keeping the old canonical constructor as a secondary one. In OpenAPI this is a
  new response property — compatible under the gate. The `essentials-admin-api.yaml` must be regenerated
  (`-Dopenapi.regenerate=true`) and the Java client regenerated in the same change.
- Optionally expose the calendar's state for operators (current business date, last close, chain breaks)
  — this would be a new admin operation, which per the root `CLAUDE.md` means three synced places (the
  `*Api` SPI, the spec mapping table, a controller). Defer unless the demo needs it (Q6).
- `spring-boot-starter-admin-ui` shows no closing-books policy today (no match for the policy endpoint in
  its sources), so nothing to change there. The demo's own admin page has a time-boundary dropdown
  (`static/admin/index.html:524-534`); it needs a period-source control if the demo wants to switch at runtime.
- Pre-existing quirk worth knowing: the admin endpoint reports the annotation, not the resolved
  configuration, so a property override is not visible there. Unchanged by this proposal.

---

## 7. Test plan and measurements

### 7.1 Unit (no Docker)

| Class under test | Cases |
|---|---|
| `ClosingBooksTimeBoundaryCalculatorTest` (calendar overloads) | Same day → 0; next business day → 1; Fri→Mon with weekend → 1, no gap; Wed→Mon → 3, gap; `to < from` → 0; period id not a business date (Saturday) counts closes since; malformed id → existing `IllegalArgumentException` |
| `BuiltInClosingBooksPolicyEvaluatorTest` | Calendar path used only when set and boundary is `END_OF_DAY`; other boundary + calendar rejected at `build()`; `nextPeriodId` = current business date; gap counter carries `period_source`; `description()`; unknown business date → no rollover |
| `InMemoryClosingBooksBusinessCalendarTest` | Idempotent re-record; out-of-order closes (current never regresses, hole filled); conflicting redelivery kept-first + counted; chain-break detection; concurrent `recordClose` vs readers |
| `DefaultAggregateClosingBooksConfigurationResolverTest` | `periodSource` resolution ladder |
| `DefaultAggregateLifecycleConfigurationValidator` tests | New check, placed after the existing ones (they are order-sensitive); zone check skipped under `BUSINESS_CALENDAR` |
| Compatibility | Old 7-arg `ResolvedAggregateClosingBooksConfiguration` constructor and old `ApiAggregateClosingBooksPolicy` constructor still compile and default to `CLOCK`; `OpenApiContractCompatibilityTest` stays green |

### 7.2 Integration (Docker)

- **Demo or a module IT with two Spring contexts on one database:** record a close on instance A; assert
  B rolls the next accessed account after the NOTIFY arrives; kill the NOTIFY (or delay) and assert the
  poll backstop catches up within its interval.
- **Rollover under contention across instances:** the same account mutated concurrently on A (new day) and
  B (old day) — exactly one rollover, no double generation, no lost event.
- **Accelerated clock soak:** simulator at a day per real minute, N accounts, 30 simulated days — every
  touched account's generation count equals the number of business days it was touched across; gap counter
  matches the expected skipped days; no rollover on weekends.
- **Scan path (if R4 is taken on):** see Q4.

### 7.3 What to measure

The evaluation sits on every mutating command, so the number that matters is **added latency per
`shouldRolloverOnAccess` call**, before vs after, on the same JVM:

| Measurement | Method | Target |
|---|---|---|
| `evaluate` with clock + zone vs with calendar (in-memory snapshot) | JMH (the reactor already has JMH in `postgresql-event-store`) | Calendar path no slower than the clock path; both well under 1 µs |
| Same, option (a) — a DB read per evaluation | Latency IT under `-Dbenchmark.run=true` | Quantify, to justify (b) |
| Allocation per evaluation | JMH `-prof gc` | Note: the demo builds a fresh evaluator per call (`TradingAccountClosingBooksPolicy.java:172-186`); measure that separately so the calendar is not blamed for it |
| Command p50/p99 in the demo's load harness, `END_OF_DAY`-clock vs business calendar | Existing harness, before/after | No measurable regression |
| Disagreement window | Timestamp close commit on A vs first sight on B | Report distribution; NOTIFY ≪ poll interval |

---

## 8. Open questions for the maintainer

**Decided** (recorded as D23 and D24 in the expansion plan):

- **Q1:** the exchange stamps every execution with `tradeDate` and `intendedSettlementDate` (SR-29), because the
  domain needs them anyway, not for this feature. The current business date is **not** added to `GET /api/clock`;
  cold start is covered by the catch-up reader replaying the latest `BusinessDayClosed` (§5.4).
- **Q2:** a new annotation member and a new `ClosingBooksPeriodSource` enum in a minor release is acceptable.
- **Q4:** the scheduled-scan gaps are a separate change, EF-1b.
- **Q5:** an unknown business date means no rollover, with a warning and a metric.
- **Q6:** the library ships only the SPI and an in-memory implementation; the application owns the store.

The original questions follow; 3 and 7–9 are still open.

1. **Does the exchange stamp trades (and other events) with their `businessDate`, and can it expose the
   current business date on `GET /api/clock`?** The first enables exact event-time period assignment (§5.3);
   the second fixes cold start (§5.4). Both would be new SR items.
2. **Is a new annotation member + new enum (`ClosingBooksPeriodSource`) acceptable, or is Option B's
   breaking enum value preferred and deferred to the next major?** This note assumes the stable-API rule
   rules B out for a minor.
3. **Scope: business days only, or business weeks/months too?** A calendar-driven `END_OF_MONTH` ("roll after
   the last business day of the month") needs the calendar to answer more than A's two methods. Proposal:
   days only now; reject other boundaries with a calendar at build time.
4. **`SCHEDULED_SCAN` (R4): fix the existing gaps or leave them?** Today `asDecisionPolicy()` never rolls on a
   scan and a scan rollover seeds no period id or carried-forward state (§1.4). A business-calendar scan is
   only useful if the scan path can run a `ClosingBooksNextGenerationFactory` with the next period as hint.
   That is a separate, larger change — should it be EF-1b?
5. **Unknown business date:** treat as "no rollover" (proposed), fail the command, or block startup until the
   calendar is loaded?
6. **Should the library own a persistent calendar store** (a `SchemaChange` contributor table with
   NOTIFY), or only the SPI plus an in-memory reference implementation (proposed)? Owning it makes the
   multi-instance story turnkey but adds a table and schema-harness surface for a feature few users need.
7. **Naming:** `ClosingBooksBusinessCalendar` (proposed) vs the plan's `ClosingBooksPeriodSource`. The
   plan's name fits the rejected generic variant better than a day-only calendar.
8. **Should the calendar's state be an admin-API operation** (three synced places), or is the
   `periodSource` field on the policy enough for now?
9. **Disagreement window tolerance:** is booking a handful of post-cutoff mutations into the closing day
   acceptable for the demo until event-time evaluation exists, or must the demo use option (a) (a DB read per
   evaluation) and accept its latency?
