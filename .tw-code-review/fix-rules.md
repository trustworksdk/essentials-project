# Rules every /fix change follows

This branch is mostly performance work. Each fix must hold on to that.

1. **No performance regressions.** Do not add work to hot paths: event append, the WAL/CDC tailer, the
   subscription and processor polling loops, queue pumps, or gap handling. Gap handling is especially
   performance-sensitive. Do not add extra database round trips, per-event queries, locks, synchronous
   waits, retries or allocations there. If a fix needs extra work on such a path, batch it, defer it, or
   keep it off the fast path. Say in `result.json` `risk` what the fix costs at runtime.
2. **Keep test time nearly flat.** A little extra test time is acceptable when it proves a real fix (a
   focused test of a few seconds); a lot is not. Prefer plain unit tests (mocks, no Docker) over new
   integration tests. Do not add `Thread.sleep`, fixed waits, long Awaitility timeouts, large data volumes
   or new Testcontainers setups unless nothing else can prove the fix. Extend an existing IT class rather than adding a new one
   that starts its own containers. Say in `result.json` how long the new tests take.
3. When a finding can only be fixed at a real runtime or test-time cost, return `needs-you` with the
   cost spelled out instead of fixing it.
