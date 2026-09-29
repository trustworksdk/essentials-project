# `slice-map` fixture — expected output

Oracle for `references/slice/slice-map-template.html` and for the data contract in
`commands/slice-map.md` §6. There is no test runner in this repo, so the ground truth is written out
here and a run is diffed by eye — the same convention as `brownfield-layered/` and `service-entity/`.

`sample-data.json` is a **synthetic** two-context map. It is not a project, it is never built, and it
must stay obviously fake. Like the other fixtures it carries **traps** as well as findings: a template
that renders only the happy path proves nothing.

## How to render it

```bash
python3 - <<'EOF'
import json, pathlib
tpl = pathlib.Path('references/slice/slice-map-template.html').read_text()
data = pathlib.Path('tests/fixtures/slice-map/sample-data.json').read_text().strip()
old = 'const SLICE_MAP = /* __SLICE_MAP_DATA__ */ null;'
assert old in tpl, 'placeholder line drifted — commands/slice-map.md §6 must be updated too'
pathlib.Path('/tmp/slice-map.html').write_text(tpl.replace(old, 'const SLICE_MAP = ' + data + ';'))
EOF
```

Then open `/tmp/slice-map.html` in a browser. Run this after **any** change to the template or to the
data contract.

## What the page must show

| # | Expectation | Why it is here |
|---|---|---|
| 1 | Title `Slice map — shop`; provenance line `…/com/acme/shop @ a1b2c3d` | The stamp is what makes two runs diffable |
| 2 | `7 slices · 2 contexts`, then `command 3 · view 2 · automation 1 · translation 1` | Per-kind census |
| 3 | Two context cards: `orders` (badges: lane decider, kotlin, cqrs-es) and `billing` (lane service-entity, java) | The lane is a **per-BC** property (§R5) — a template showing one project-wide lane is wrong |
| 4 | Seven slice buttons, colour-keyed by kind on the left border | |
| 5 | `billing.acme_gateway` shows a grey `planned` pill; the other six show a green `live` pill | |
| 6 | `orders.cancel_order` carries an amber flag *"declared endpoint not found in source"* | Divergence is an annotation, never a severity — this command grades nothing |
| 7 | **Flow** tab: four rows, the two cross-context ones (`OrderPlaced`, `PaymentSettled`) **first** | Cross-context events sort first because they are the ones nobody remembers |
| 8 | `PaymentSettled` renders with a red border and *"no publisher"* on the left; `InvoiceIssued` renders normally with *"no consumer"* on the right | **Trap pair** — consumed-but-never-published is a dangling edge; published-with-no-consumer is ordinary and must not be flagged as one |
| 9 | **Data** tab: `Invoice` is the first write-target row, in red, flagged *"written from 2 contexts"*; `Order` renders normally **despite having two writing slices** | **Trap pair.** An aggregate written by several command slices *inside one context* is the design on every lane — `place_order` and `cancel_order` both write `Order`, and adding a command adds a slice. Flagging arity put a red badge on every compliant project. What is worth surfacing is ownership crossing a boundary: `Invoice` belongs to `billing` and `orders.place_order` writes it. A renderer that colours by writer count fails this row twice — once by flagging `Order`, once by burying `Invoice` |
| 9b | `order_list_view` (kind `read-model`) renders normally with one owner; a read model with **two** owners would flag `2 owners` | The two target kinds have opposite normal cases — several writers is fine on an aggregate and is §R4 on a read model. One "more than one writer" rule is wrong for one of them whichever way it is written |
| 10 | Cross-reads table: `billing.invoice_list` → `orders` via `OrderReader`; `billing.acme_gateway` → `orders` with a red *"no via: — R4 requires one"* | **Trap** — a declared cross-BC read and an undeclared one must not look the same |
| 11 | **Endpoints** tab: five rows sorted by path, `/api/invoices` first; methods colour-keyed | **Trap** — seven slices but five endpoints: the automation has none by design, and `billing.issue_invoice` is a command slice reached over the command bus rather than HTTP. A renderer that assumes one endpoint per command slice drops or invents a row |
| 11b | `/api/invoices` and `/api/invoices?status=` are adjacent rows on **one** slice, with `?status=` rendered muted after the route | A mapping selected by `params = "status"` is a distinct endpoint over the same read model (§R2). Both belong to `billing.invoice_list`; a renderer that splits them across slices, or that hides the discriminator, loses the distinction that makes them two rows |
| 12 | Footer: the note plus `skipped: module payments (not analysed)` | A silent cap reads as "covered everything" |
| 13 | Clicking any slice opens a modal listing its manifest fields; clicking a slice id in Flow/Data/Endpoints opens the same modal | This is the *"where is X implemented"* path |
| 14 | Hovering a slice id highlights every other occurrence of it across the page, graph nodes included | |
| 15 | Typing `cancel` in the filter leaves one slice button, hides the `billing` card entirely, and narrows Flow/Endpoints/Graph | An emptied card left on screen reads as a context with no slices |
| 16 | The page renders identically with no network, from `file://`, in light **and** dark theme | No CDN, no fonts, no fetch — the template is self-contained by contract |

## What the Graph tab must show

| # | Expectation | Why it is here |
|---|---|---|
| G1 | 15 nodes — 7 slices, 3 commands (`PlaceOrder`, `CancelOrder`, `IssueInvoice`), 4 events, 1 external system — and 13 edges | Every node comes from a declared field; a count that drifts means something is being inferred |
| G2 | Commands and events render as pills (amber / green), external systems dashed, slices as rectangles with the kind colour on the left edge | Message vs. slice must be readable without the legend |
| G3 | The full chain reads left to right: `PlaceOrder → orders.place_order → OrderPlaced → billing.payment_policy → IssueInvoice → billing.issue_invoice → InvoiceIssued` | **The reason this view exists.** Seven ranks, crossing a bounded context in the middle. A layout keying on kind rather than on the edge graph collapses this |
| G4 | `OrderPlaced` fans out to **two** consumers — `orders.order_list` and `billing.payment_policy` | Fan-out is the common real shape; a tree layout that assumes one consumer loses an edge |
| G4b | `orders.order_list` has `consumes: []` and declares its two events on `projections[].from`, and **still** shows two inbound edges | **The trap this fixture exists for most.** That is the shape the view template emits, so a reader consulting `consumes` alone reports every correctly-generated view as reacting to nothing — on manifests that are perfectly correct. Union `consumes` with `projections[].from` |
| G5 | `billing.acme_gateway` has an edge **both to and from** `acme_gateway` | `direction: both` is two edges, not one |
| G6 | Scroll wheel zooms toward the cursor; drag pans; `⤢` re-fits; the graph starts fitted | "Zoom" that only scales about the centre is unusable once a graph is wider than the window |
| G7 | Clicking `orders.place_order` dims everything **not connected** to it — 4 of 15: `billing.invoice_list`, `billing.acme_gateway`, `PaymentSettled`, `acme_gateway`. It is marked selected and its 2 edges highlighted. Clicking again — or `✕`, or the background — restores every node | **Connected means transitively connected.** The whole seven-node chain stays lit, including nodes three hops out: they are part of what this slice participates in. A one-hop isolation dims most of a node's own chain, which answers a question nobody asked |
| G7b | Clicking `InvoiceIssued` — the far end of the same chain — dims the identical 4 nodes | Focus selects a *component*, so any node in it produces the same picture. If the two differ, the walk is following edge direction instead of connectivity |
| G8 | Double-clicking a slice node opens the same detail modal as the Contexts tab | One detail surface, reachable from anywhere |
| G9 | Typing `billing` dims every `orders` node and keeps the four `billing` slices plus `InvoiceIssued` | An event is attributed to the context that **publishes** it, not the one that first mentions it |
| G10 | A graph with a cycle (a saga reacting to an event it ultimately causes) lays out and does not hang | Not exercised by this fixture — see the coverage gaps |
| G11 | `billing.invoice_list` is an isolated node — no edges at all | **Trap** — a view that only `reads` publishes and consumes nothing, so it legitimately has no message edges. A layout that drops unconnected nodes loses a real slice |
| G12 | Columns read as the message-flow grammar: commands · command slices · events + external systems · reactor slices · the dispatched command · its slice · its event | The **role floor** under the rank. Longest-path alone puts every node with no inbound edge in column 0, so `billing.invoice_list` (a view with no `consumes`) and `PaymentSettled` (a dangling event) both sat *left of the commands*, reading as entry points. The floor only lifts, so the seven-node chain is unmoved |
| G13 | `IssueInvoice` sits in a column to the **right** of the reactor slices, not back in column 0 | The grammar is not a straight line: an automation dispatches a command, so the chain **loops back** into the command role further right. This is why the role is a *floor* and not a fixed column assignment — a fixed mapping would have to fold this edge backwards |
| G14 | Hovering `orders.order_list` colours its 2 inbound event edges apart from everything else, and it has no outbound marks | The direct answer to *"which events go into this view"*. Once a graph is wide enough for an edge to cross four columns, tracing it by eye is not possible and the plain edge colouring cannot help |
| G15 | Hovering `orders.place_order` marks 1 inbound (`PlaceOrder`) and 1 outbound (`OrderPlaced`) in different colours | In and out must be distinguishable, not merely highlighted together |

## Hover card, type filters, maximise

| # | Expectation | Why it is here |
|---|---|---|
| H1 | Hovering `orders.cancel_order` shows badges (`command`, `live`, `kotlin`, `cqrs-es`, `orders-team`), the summary, package `com.acme.shop.orders.use_cases.cancel_order`, and its six class names | The package must come from the source declaration, not be reconstructed from the path |
| H2 | The same card shows `DELETE /api/orders/{orderId}  [user]`, the invariant *"A dispatched order cannot be cancelled"* with `→ CancelOrderDecider`, its tests, and the amber divergence flag | `enforcedBy` is the half that makes an invariant actionable — an invariant with no enforcer is a comment |
| H3 | The card hides on mouse-out and never blocks the pointer | |
| H4 | Hovering the `OrderPlaced` event names its publisher and **both** consumers; hovering `PaymentSettled` adds *"Consumed but never published"*; hovering `IssueInvoice` names the dispatching automation and the handling slice | A message node is only meaningful as the slices on either end of it |
| H5 | Seven chips — four slice kinds, three message types — all on at load | |
| H6 | Turning off `view` removes two slice nodes **and** their two edges from the drawing entirely (`display: none`, not dimmed) | **The filter/query distinction.** A type filter says "not part of the graph I asked for"; the query says "still there, not what I'm looking at". Dimming both makes "automations only" unreadable |
| H7 | Turning off `command types` as well removes three more nodes; turning both chips back on restores all 15 nodes and 13 edges | |
| H8 | Focusing a node and then filtering its type away clears the focus rather than leaving an invisible selection | |
| H9 | `⛶` fills the screen; `Esc` or a second press returns. Where fullscreen is refused (common from `file://`) it falls back to a fixed-position layer and behaves the same | A maximise that silently does nothing when the API is refused is worse than none |
| H10 | The graph re-fits after maximising or restoring | A viewBox fitted to a 400px-tall box is wrong at 1080px |
| H11 | Hovering `OrderPlaced` shows `identity: orderId: OrderId` **first**, then the rest of the payload, then `declared in: orders/events/OrderPlaced.kt` and a `v1` badge | On an event the identity property is what the message is *about*; on a command it is what makes a retry idempotent. It is the field to lead with, not one of several |
| H12 | Hovering `PaymentSettled` — consumed from a system we do not own — shows the note that no producer is in scope and **no** empty identity or payload rows | **Trap** — blank rows read as "this message carries nothing", which is a different and wrong claim from "the declaration is not in scope" |
| H13 | Double-clicking `orders.order_list` opens the modal with its read model as a **table**: 5 rows, `order_id` marked primary key, `event_order` noted as projection idempotency | A view's identity *is* its read model (§R2), and column types read off a comma-joined string are what a table fixes |
| H14 | Double-clicking a **command** slice shows no read-model table | Only views own a read model; an empty table is a claim about shape |
| H15 | Double-click opens the modal at all, and a drag that ends over a node does **not** select it | Both regressions of the same root cause — see the trap below |

## Trap: the unrendered template

Opening `references/slice/slice-map-template.html` **directly** must show
*"This template has not been rendered."* and nothing else — not a broken page and not a JS error. The
placeholder is `null`, and every render path is behind that guard.

## Trap: pointer capture silently kills every node interaction

The pan handler must **not** call `setPointerCapture` on the `<svg>`. Capture retargets the following
`pointerup`, so the browser resolves the `click` on the `<svg>` rather than on the `<g>` the user
pressed, and **every node handler stops firing** — no focus, no dimming, no double-click. The page looks
alive (it pans and zooms) while nothing selectable responds, which is why this reads as three unrelated
missing features rather than one bug.

A DOM-level test cannot catch it: `dispatchEvent(new Event('click'))` on the node fires the handler
directly and never exercises the browser's click-target resolution. **Verify this one in a browser** —
click a node, confirm it dims the unconnected ones; double-click a slice, confirm the modal opens; drag
across a node, confirm the release does *not* select it.

Panning starts only after ~4px of movement, and the click a drag emits is suppressed.

## Trap: the temporal dead zone

The template's bootstrap call sits at the **bottom** of its `<script>`, after the graph section's
`const` declarations. Moving it back up to where `SLICE_MAP` is read — which looks tidier — throws
`ReferenceError: Cannot access 'NW' before initialization` and renders a blank page. Expectation 1
failing with an empty body is the symptom; check the call site before anything else.

## Coverage gaps, stated rather than papered over

- **No cycle.** G10 has no fixture. The layout is cycle-guarded (longest-path ranking with a visited
  set), but nothing here proves it. A saga consuming an event its own dispatched command ultimately
  causes would close it, and would be the first fixture element that is a *behaviour* of the layout
  rather than a shape in the data.
- **No `supersedes` twin.** The versioned-view pair (`_v2` + `status: deprecated`) renders through the
  same code path as any other slice, but the pairing flag from `commands/slice-map.md` §4 has no fixture.
- **Scale is untested.** Fifteen nodes lay out legibly by construction. Nothing here says what a
  two-hundred-node estate looks like, and the barycentre ordering is two passes, not a real crossing
  minimisation — expect a denser graph to read worse, and treat that as known rather than as a bug.
- **`package` and `files` are not manifest fields**, so nothing here proves the command reads them
  correctly off a real slice directory — only that the renderer shows them. Same split as below.
- **The divergence checks themselves are not exercised here.** This fixture is *data*, so it tests the
  renderer, not the manifest-reading that produces the data. `tests/fixtures/service-entity/` carries
  real manifests and is the oracle for that half.
