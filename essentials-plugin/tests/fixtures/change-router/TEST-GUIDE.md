# Test guide — `change-router`

The oracle for the `essentials-change` skill (`skills/essentials-change/SKILL.md`), which routes a change
request written in prose through `references/slice/change-procedure.md`. Each case is one sentence a
user might type; the expectation is the class the skill must name (§2), the slice that owns the change
(§3), and what it must and must not do. Never shipped to a user.

```
cases.yaml                 the cases, machine-readable — the eval graders are generated from it
essentials-not-on-law/     a layered Spring/JPA service that depends on Essentials and has no manifests
```

Most cases run in a copy of `../worked-example/` (the `orders` bounded context on the decider lane:
`place_order`, `cancel_order`, `order_list`, `screen_order`, `warehouse`), with `{{packagePath}}`
rendered to `com.acme.shop`. Three cases walk the gate ladder of §1, each in its own tree:

| Case | Tree | Expected |
|---|---|---|
| `gate-not-essentials` | `../brownfield-layered/` (no Essentials, no manifests) | Silent: the request is handled as ordinary work and nothing about slices is said |
| `gate-essentials-not-on-law` | `essentials-not-on-law/` (Essentials in the pom, layered code, no manifests) | Says so once, offers `/essentials:slice-discover`, and makes the change in the layered style |
| `gate-service-entity` | `../service-entity/` (on the law, service-entity lane) | Proceeds: a new command slice on the service-entity lane, confirmed before scaffolding |

The worked-example cases cover every class and the §5 decision points:

| Case | Request (abridged) | Class | Owner | The point |
|---|---|---|---|---|
| `b-filter-by-sku` | list orders for one SKU | B | `orders.order_list` | another query over the same read model is the same slice (§5.2) |
| `ask-support-cancel` | let support staff cancel an order | ask | `orders.cancel_order` | A and B both arguable: put §R1 to the user |
| `a-amend-quantity` | change an order's quantity | A | new command slice | a second intent is a second slice, never a second method (§5.1) |
| `a-sku-totals-view` | total quantity per SKU | A | new view slice | a different read-model shape is a different view (§R2 test 1) |
| `c-rename-sku` | rename `sku` to `productCode` | C | `place_order`, `order_list`, `warehouse` | decompose, producer first (§5.9); the stored event's JSON changes |
| `d-cancelled-at` | show when an order was cancelled | D (or C) | `orders.order_list` | in-place rebuild is the default, a `_v2` twin the exception (§5.3) |
| `e-debug-logging` | DEBUG logging for orders | E | none | not a slice change: no manifest edit |
| `b-screening-inclusive` | the screening limit is inclusive | B | `orders.screen_order` | a one-line fix needs no confirmation round |
| `retire-screening` | remove order screening | — | `orders.screen_order` | retirement deletes directory, manifest, tests and wiring together (§5.10) |
| `c-rejected-event` | a distinct `OrderRejected` event | C (or A) | `orders.screen_order` | one variant per file; consumers are declared changes (§5.4) |
| `b-warehouse-cancel` | tell the warehouse about cancellations | B | `orders.warehouse` | extend the translation, do not fork it |
| `helper-pushback` | a shared `OrderValidationHelper` | — | none | the only sanctioned sharing is `_shared/` State + Evolver (§5.5) |
| `dto-pushback` | a request DTO and a mapper | — | `orders.place_order` | the command is the contract (§5.7) |
| `b-order-by-id` | one order by id | B | `orders.order_list` | a new endpoint path is quoted in the manifest (§6) |

Where the skill must confirm before scaffolding (§9, SKILL.md Step 3), the case forbids every Write and
Edit: the run ends at the confirmation question, and that question is what gets graded. Cases that must
change files (`e-debug-logging`, `b-screening-inclusive`, the two gate cases that do the work) check the
edit itself.

Editing rules:

- A new decision point in `change-procedure.md` gets a case here.
- Requests are phrased the way a user would type them; never name the class, the section or the slice.
- Never put a finding or trap label in `essentials-not-on-law/`'s sources; the model under test reads them.

Run: `claude plugin eval essentials-plugin --scaffold --case 'change-*' …` — the full command, and how to
read the result, are in `evals/README.md`.
