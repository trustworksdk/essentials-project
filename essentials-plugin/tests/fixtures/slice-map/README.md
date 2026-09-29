# Fixture — `slice-map`

A synthetic, two-context slice map used to exercise the renderer behind
`/essentials:slice-map --html`. It is **data, not a project**: no sources, no build, nothing to run.

| File | What it is |
|---|---|
| `sample-data.json` | One complete instance of the data contract in `commands/slice-map.md` §6 |
| `TEST-GUIDE.md` | The ground truth — what the rendered page must show, including the traps |

The contexts (`orders`, `billing`) and their slices are invented. They deliberately include things a
healthy project would not have — an aggregate written by two slices, an event consumed by nobody's
publisher, a cross-context read with no `via:` — because a fixture containing only well-formed data
cannot tell a correct renderer from one that silently drops the awkward cases.

They also carry one deliberately *healthy* shape: the seven-node chain
`PlaceOrder → orders.place_order → OrderPlaced → billing.payment_policy → IssueInvoice →
billing.issue_invoice → InvoiceIssued`, which crosses a bounded context in the middle and fans out at
`OrderPlaced`. It is what the Graph view exists to draw, and it is the assertion most likely to break
when the layout is touched.

Render it and diff by eye per `TEST-GUIDE.md` after any change to
`references/slice/slice-map-template.html` or to the data contract in `commands/slice-map.md`.

This fixture verifies the **renderer**. The other half — reading real `slice.yaml` files and deriving
this shape — is verified against `tests/fixtures/service-entity/`, which carries real manifests.
