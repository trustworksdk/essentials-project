# Admin UI mockup

A static mockup of the planned Thymeleaf + vanilla-JavaScript admin UI (Phase 3 of the roadmap in
[`../README.md`](../README.md)). **Not production code and not wired to anything** — its only job is to
settle the look and feel before any templates exist.

Open [`index.html`](index.html) directly in a browser. No server, no build step, no dependencies.

## What it covers

Seven views, replacing the removed Vaadin UI. Every one of the 27 operations in the committed contract
is reachable — 24 explicitly, 3 only implicitly (`getTotalExecutorJobs`,
`getTotalPgCronJobRunDetails`, `getQueueNameFor`, which sit behind pagers and the entry-id filter).

| View | Replaces | Endpoints |
|------|----------|-----------|
| Dashboard | `AdminView` | KPI roll-up across all tags |
| Fenced locks | `LocksView` | `/fenced-locks` |
| Durable queues | `QueuesView` | 12 × `/durable-queues/**` |
| Scheduler | `SchedulerView` | 6 × `/scheduler/**` |
| Subscriptions | `SubscriptionsView` | `/event-store/subscriptions`, highest-global-event-order |
| CDC | *(new — replaces the `EventProcessorsView` stub)* | `/event-store/cdc/status` |
| PostgreSQL stats | `PostgresqlStatisticsView` | query statistics + 3 × `/event-store/statistics/**` |

`EventProcessorsView` was a `"Coming soon"` placeholder with no backing SPI, so its nav slot went to
CDC instead — the richest payload in the contract, and one the Vaadin UI never surfaced.

## Fixtures

Hand-written against `../../../components/admin-api-spec/openapi/essentials-admin-api.yaml`. Field names
and nullability match the DTO schemas exactly, so the layout is judged against data of the real shape.
Three contract subtleties are deliberately represented:

- `ApiQueuedMessage.payload` is `null` for one row — the caller lacks `essentials_queue_payload_reader`,
  and the UI shows *redacted* rather than an empty cell.
- `ApiCdcStatus.tailer` is `null` — only the instance holding the slot lock runs it, so the card renders
  an explanatory empty state instead of blanks.
- A fenced lock with no holder has `null` token and timestamps.

## Message detail drawer

Queue rows open a right-hand drawer — deliberately not a modal, because triaging dead letters means
stepping through messages one after another and a modal forces a close/reopen cycle each time. The
table stays visible and the drawer re-renders. It gives
`GET /durable-queues/messages/{queueEntryId}` a proper home; previously that operation was only
implicitly used by the entry-id filter.

Opened from the entry id or the payload cell (both real buttons, so keyboard reachable) or by clicking
anywhere on the row except the action buttons. `Esc` or the scrim closes it.

Three behaviours worth keeping if this is reimplemented:

- **Payload is not assumed to be JSON.** The contract types it as `String`. The drawer pretty-prints
  when it parses and shows it verbatim when it does not, labelling which it did plus the byte size.
- **A withheld payload is explained, not blanked.** When `payload` is `null` the drawer names the roles
  that would return it (`essentials_queue_payload_reader` / `essentials_admin`) and states that every
  other field is still readable.
- **Stack traces do not wrap.** `lastDeliveryError` renders with `white-space: pre` and horizontal
  scroll — wrapping breaks mid-package-name and destroys the one structure that makes a trace
  readable. The payload block *does* wrap, since pretty-printed JSON has sane line lengths.

Both blocks are height-capped and scroll internally, verified against a 673-byte payload and a
multi-frame trace rather than one-line fixtures.

## Operational states

`Component states` in the sidebar (mockup-only, would not ship) renders every component and state on one
page, so a look-and-feel regression shows up in a single screenshot instead of being found view by view.

Error states render **inside the card whose request failed**, so one failing panel never blanks the page.
They follow the contract's `Error` schema, where `status` and `error` are required and `message` is
nullable — the `500` state therefore has to read sensibly with no message at all, because the adapter
deliberately withholds 5xx detail so internals cannot leak to an HTTP caller. `401`, `403`, `500` and an
unreachable-service state are all covered; the `403` names the role that would satisfy the operation.

`Refresh` shows the skeleton where data will land and then re-renders, which is the shape a real fetch
takes.

## Destructive confirmations

The three destructive operations confirm before acting, and the dialog states the consequence rather
than asking "are you sure":

| Action | Treatment |
|--------|-----------|
| Release lock | Warns that another instance may acquire immediately, so in-flight work is no longer fenced |
| Delete message | Notes the payload is unrecoverable, suggesting a copy from the drawer first |
| Mark as dead letter | Non-destructive — states that it is reversible via resurrect |
| Resurrect | A form, not a confirm: picks `deliveryDelay`, sent as an explicit `PT0S` for "immediately" since the contract requires the field |
| **Purge queue** | Typed confirmation — the name must be entered. The only irreversible bulk operation in the contract, and it takes dead letters with it |

**Cancel holds focus on a destructive dialog**, so the safe option is the default one; reversible
dialogs focus the confirm button instead. `Esc` closes the innermost layer only — a dialog opened over
the detail drawer does not dismiss both.

## Design rules it follows

The data is operational and almost entirely tabular, so the form choices are tables, stat tiles, status
badges, meters and inline magnitude bars — **no time-series charts, because no endpoint returns
history**. Trends would need new endpoints, not new UI.

- One sequential hue for magnitude (inline bars, meters); the number is always shown beside the bar, so
  the bar is never the sole carrier of the value.
- Status colour never carries meaning alone — every badge is icon + label + colour.
- Dark mode is a separately stepped set under both the OS media query and the toggle, not an inverted
  flip.
- Numeric table columns use `tabular-nums`; standalone tile figures use proportional figures.

## Tracking look and feel

`screenshots/` holds a captured reference of every view. Regenerate after a change and compare:

```bash
npm i playwright-core            # once, anywhere outside the project
npx playwright install chromium  # once
node screenshots/capture.mjs screenshots
```

`capture.mjs` clicks through all seven views plus dark mode and writes one PNG each, reporting any
console errors. Note that on **Linux ARM64 there is no Google Chrome build**, so the `chrome` channel is
unavailable — use bundled `chromium`, as the script does.
