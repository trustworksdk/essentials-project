# Bounded Context: inventory

Write style: **service-entity** (`rules/slice-design.md` §R5). A stock item is a row, mutated in place.

**No history.** Inventory never reconstructs a stock level from events; the current count is the
record. Events are published on the `EventBus` as integration facts.
