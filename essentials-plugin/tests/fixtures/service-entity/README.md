# Fixture — `service-entity`

A **synthetic** Essentials bounded context on the service-entity write-style lane
(`rules/slice-design.md` §R5). Never built, never shipped, not a template — if you are looking for
something to copy, copy `references/slice/templates/` instead.

It exists so `/essentials:slice-check`'s lane-conditional gates have an oracle; `brownfield-layered/`
targets `slice-discover`.

**What makes it on-lane rather than merely entity-shaped:** the Essentials command bus and `EventBus`
are on the classpath and the write path goes through them, `entities/` holds the state-stored entity,
and nothing anywhere references an `EventStore` or an `AggregateType`. Strip the Essentials
dependency and this becomes the `brownfield-layered` case — *nearest* to the lane, not on it.

It is deliberately **not clean**. It carries nine planted findings and fifteen traps, because a fixture
containing only findings proves nothing about false positives. `TEST-GUIDE.md` is the oracle.

One planted finding is a **misplaced** write repository (`persistence/ShippingOrders`, which the law
puts in `entities/`). That is intentional beyond the finding itself: it forces gate 15 to identify a
write repository by type rather than by path, and a path-keyed implementation fails the fixture
loudly instead of passing it silently.
