# Bounded Context: catalog

Write style: **service-entity** (`rules/slice-design.md` §R5). A product is a row, mutated in place.

**No history.** The catalog never reconstructs a product from events: price history is not a
requirement, and no view is rebuilt by replay. Events are published on the `EventBus` as
integration facts.
