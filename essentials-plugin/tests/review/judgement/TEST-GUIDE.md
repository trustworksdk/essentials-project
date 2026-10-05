# Test guide — `/essentials:review` judgement cases

Two realistic changes to two fixtures, each carrying findings a script reports, findings only a reader
can see, candidates that must be dismissed, and traps that must stay silent. The machine-readable version
is `<case>.expected.yaml` beside each patch; this file describes the same expectations for a human. When
they disagree, fix both.

| Case | Fixture | Patch | Commit message |
|---|---|---|---|
| `decider-lane` | `tests/fixtures/worked-example` (Kotlin, decider lane, no build file) | `decider-lane.patch` | Expedite orders |
| `service-entity` | `tests/fixtures/service-entity` (Java, service-entity lane, `pom.xml`) | `service-entity.patch` | Cancel shipments |

## Running a case by hand

```bash
tmp=$(mktemp -d)
rsync -a --exclude TEST-GUIDE.md --exclude expected.yaml essentials-plugin/tests/fixtures/<fixture>/ "$tmp/"
cd "$tmp" && git init -q && git add -A && git commit -qm fixture
git apply <repo>/essentials-plugin/tests/review/judgement/<case>.patch && git add -A && git commit -qm "<commit message>"
```

Then, in Claude Code with the plugin loaded, in `$tmp`: `/essentials:review HEAD~1`. The fixture's own
`TEST-GUIDE.md` and `expected.yaml` stay out of the sandbox: both name findings, and a reviewer that
can read them is graded on reading, not reviewing.

The eval suite builds the same sandbox: `evals/review-<case>/scaffold.sh`, run by `claude plugin eval
--scaffold`. Its graders are generated from `<case>.expected.yaml` by `evals/build.py` (`grading.yaml`
names the sections), plus two hand-written ones per case (`graders/report-shape.md`, and
`graders/pre-existing.md` for `service-entity`). Edit the expected file, never a `gen-*` grader, then
run `uv run --script evals/build.py`.

The deterministic half needs no model: `uv run --script tests/review/judgement/check-patches.py` applies
each patch to a copy of its fixture, checks every anchor, and compares review-scan, stack-lint,
slice-lint and slice-source against the case's `deterministic` block. Run it after changing a fixture,
a patch or one of those scripts.

## `decider-lane` — "Expedite orders"

A new command slice (`use_cases/expedite_order`), a new event, a new view slice
(`views/expedited_orders`), one more handler on `views/order_list`, an HTTP client for the warehouse, and
an `application.yml`.

Findings — all six must appear:

| # | Severity | Id | Where | What | Found by |
|---|---|---|---|---|---|
| 1 | Should-fix | ESS-050 | `src/main/resources/application.yml:7` | lock confirmation interval 4s is not 2× shorter than the 6s timeout | review-scan |
| 2 | Should-fix | ESS-103 | `src/main/resources/application.yml:10` | `transactional-mode` is retired and binds to nothing; mechanical fix | review-scan |
| 3 | Should-fix | ESS-G11b | `views/order_list/OrderListProjection.kt:54` | the projection now handles `OrderExpedited`, which `projections[].from` does not declare | slice-source |
| 4 | Blocking | ESS-G8a | `use_cases/expedite_order/ExpediteOrderDecider.kt:6-7` | imports `cancel_order`'s `OrderState` and `OrderStateEvolver` | judgement |
| 5 | Blocking | ESS-G9 | `use_cases/expedite_order/ExpediteOrderDecider.kt:12` | no `@Bean` in `orders/config/OrdersConfiguration.kt`, so `ExpediteOrder` routes nowhere | judgement |
| 6 | Blocking | ESS-075 (also ESS-G10) | `views/expedited_orders/ExpeditedOrdersProjection.kt:30` | a single-argument `@MessageHandler` saves a versioned read model without the event order | judgement |

Dismissed: **ESS-052** at `external_systems/warehouse/WarehouseHttpClient.kt:21` — `jacksonObjectMapper()`
writes the warehouse's outbound HTTP body; nothing it serializes is persisted by Essentials.

Not run: **the stack contract** — the tree has no `pom.xml`, so `stack-lint.py` exits 2. The report must
say so and must not invent an `ESS-S…` row or call the stack clean.

Traps — none of these may be reported:

| # | Must not be reported | Why |
|---|---|---|
| 1 | Gate 6 on `ExpediteOrderAPI.kt` | one method-level mapping; the class-level `@RequestMapping` is the base route |
| 2 | Gate 6 on `ExpeditedOrdersAPI.kt` | a view may serve several queries over its own read model; both are in `endpoints` |
| 3 | A handler read from `ExpeditedOrdersProjection.kt`'s class comment | the comment names `@MessageHandler`; prose is not a handler |
| 4 | ESS-052 as a finding | it is the dismissed candidate |
| 5 | Gate 4 on `expedite_order` writing `Order` | several command slices over one aggregate is the lane's design |
| 6 | Gate 17 on `ExpediteOrder` | it implements `OrderCommand` |
| 7 | Anything in `ScreenOrderProcessor.kt` or `WarehousePublisher.kt` | unchanged by the patch (and both are sanctioned shapes) |
| 8 | The fixture's pre-existing "view slice with no test" on `order_list` | pre-existing: counted, not listed |

Tolerated: a missing-test Should-fix on the two new slices; an Advisory that `orders/CLAUDE.md` does not
list them; the new branch in `cancel_order`'s evolver named as part of finding 4.

## `service-entity` — "Cancel shipments"

A new command slice (`use_cases/cancel_shipment`), a guarded `cancelShipment()` on the entity, a purge
endpoint on the order-status view, a command-bus bean, a row count in the load harness, a POM
dependency and an `application.properties`.

Findings — all eight must appear:

| # | Severity | Id | Where | What | Found by |
|---|---|---|---|---|---|
| 1 | Blocking | ESS-094 (also ESS-S3.1) | `pom.xml:16` | a 0.50 Jackson 2 module; mechanical fix to `types-jackson3`. **One row** — stack-lint's ESS-S3.1 at `pom.xml:14` is the same dependency | review-scan + stack-lint |
| 2 | Blocking | ESS-089 | `application.properties:2` | `reactive-bean-post-processor-enabled=false` unwires every handler; mechanical | review-scan |
| 3 | Should-fix | ESS-097 | `application.properties:3` | queue statistics are removed; the key is silently unbound; mechanical | review-scan |
| 4 | Advisory | ESS-016 | `config/CommandBusConfig.java:13` | a candidate, **confirmed**: `auto_ship` sends with `sendAndDontWait`, which a `LocalCommandBus` keeps in memory | review-scan + judgement |
| 5 | Should-fix | ESS-G6 | `views/order_status/OrderStatusAPI.java:43` | `DELETE /{orderId}` is not in the view's `endpoints` | slice-source |
| 6 | Blocking | ESS-G15a | `views/order_status/OrderStatusAPI.java:44` | `shippingOrders.deleteById` — a mutation on the write repository from a view | judgement |
| 7 | Should-fix | ESS-G16 | `entities/ShippingOrder.java:53` | `setCancelled` bypasses the guard in `cancelShipment()` | judgement |
| 8 | Advisory | ESS-G6 | `views/order_status/OrderStatusAPI.java:43` | `purge(@PathVariable String orderId)` takes the id raw, beside `get(@PathVariable OrderId …)`. Same line as 5, a different defect (`6 raw id`): its own row | slice-source |

Dismissed: **ESS-064** at `_loadtest/LoadHarness.java:26` — a read-only row count for the load report;
there is no write and no Spring transaction to join.

Pre-existing, counted and never listed: the 13 stack-lint findings the fixture already has (S2.1 and
others), and every finding `tests/fixtures/service-entity/TEST-GUIDE.md` lists — including `setShipped`
and the command-typed constructor in `ShippingOrder.java`, a file the patch edits, and slice-source's raw id on
`ShipOrderAPI.ship`, which the base run reports too.

Traps — none of these may be reported: ESS-S3.1 as a row of its own; gate 6 on `CancelShipmentAPI.java`;
anything on unchanged lines of `ShippingOrder.java`, or in `ShippingOrders.java`, `ShippingCommand.java`
or `OrderStatusQueries.java`; ESS-080 on the JPA `@Id`; gate 4 on `cancel_shipment` writing
`ShippingOrder`; gate 10 anywhere (not on this lane); ESS-064 as a finding; the pre-existing raw id on the unchanged `ShipOrderAPI.java`.

Tolerated: ESS-086 named beside finding 4; gate 9's post-processor note beside finding 2; a missing-test
Should-fix on `cancel_shipment`; an Advisory on the `config/` package inside the bounded context.

### `--fix` (manual)

Not an eval case: `claude plugin eval` runs one prompt per case and has no way to answer the per-fix
`AskUserQuestion`. `check-patches.py` checks the deterministic half — that the eligible rows are
exactly findings 1, 2 and 3, in that order. The rest is a manual run of the `fix` block of
`service-entity.expected.yaml`: `/essentials:review HEAD~1 --fix`, answering *Apply*, *Skip*, *Apply*: the command offers exactly
findings 1, 2 and 3, in that order (Blocking first, then file and line), each with the exact before and
after lines, and never offers 4–8. Afterwards `pom.xml` names `types-jackson3`, `application.properties`
keeps line 2 and loses line 3, and no Java file changed.

## Known gaps

- No `--pr` case: fetching a pull request needs a remote. The fetch and extraction are plain git and are
  exercised by hand.
- No Mongo case in the judgement set; `ESS-088` is covered by `tests/review/signatures/`.
- The `<path>` mode is not a case: it is the same pipeline over an all-added diff.
