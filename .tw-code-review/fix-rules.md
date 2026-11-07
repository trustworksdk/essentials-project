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
4. **Fix only what is real.** A finding is fixed only when a test reproduces it, a performance run showed it, or it
   is plainly wrong (a wrong value, a wrong statement in a doc, a crash on bad input). Everything else is reported, not
   fixed: return `needs-you` with the risk described, or a note.
5. **No speculative hardening of timing code.** Do not add bounded waits, fallbacks, retries, stall flags, probes or
   other "what if it happens now" handling to locks, waits or replays unless a test or a performance run shows the
   problem. Prefer bounding the operation that holds a lock over adding fallbacks for everyone who waits on it.
6. **Design questions go to the user, once.** When a finding is about what an API should promise or how a trade-off
   should fall, document the current behaviour as a risk or trade-off and return `needs-you` with a recommendation.
   Do not try several shapes in successive rounds. If a re-review raises new findings in the fix itself, stop and
   report them; do not fix the fix.
