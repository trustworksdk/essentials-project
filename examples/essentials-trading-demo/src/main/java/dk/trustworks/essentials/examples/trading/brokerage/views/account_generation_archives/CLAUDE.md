# Slice: brokerage.account_generation_archives

**Kind:** view   **Status:** live   **Owner:** brokerage-team
**Purpose:** List the archived books generations of a trading account, and serve the contents of one entry.

## Two queries, one model

Both endpoints interrogate the **archive** — one lists its entries, the other reads one entry's file. Two
questions about one model is one slice (§R2).

The archive is a store this slice *reads* and does not own. Writing to it belongs to
`use_cases/archive_generation`, and `POST /api/admin/trading-accounts/{accountId}/generations/{generation}/archive`
lives there. Do not add it here just because it shares the path prefix — that would put a command in a view
slice.

## Invariants
- Archiving off is reported, never faked: `AggregateArchiveApi` only exists when
  `essentials.eventstore.archives.enabled=true`, so it is injected as `Optional` and its absence throws
  with the property that turns it on. Requiring it outright made the *whole application* fail to start with
  archiving off, for the sake of these two endpoints. The message is shared word-for-word with
  `use_cases/archive_generation`.
- Only `file:` archive locations are readable. Any other scheme fails loudly with the location it got — the
  demo archives to the local filesystem, and object storage would need a reader per scheme. Returning
  nothing would look identical to an empty archive.
- A generation that was never archived fails with `Couldn't resolve archived generation <n> for trading
  account <id>`, not with empty content.

## Boundaries
**Reacts to / reads:** `AggregateArchiveApi`, scoped to `TradingAccounts.AGGREGATE_TYPE`.
**Publishes:** nothing — a view slice never produces events.
**Forbidden:** never import another slice's internals; never archive from here.

## Endpoints
- `GET /api/admin/trading-accounts/{accountId}/archives` → `List<ApiArchivedGeneration>`, the framework's own
  shape. There is no read model of ours to return and a mirror record would be a `…Response` DTO.
- `GET /api/admin/trading-accounts/{accountId}/generations/{generation}/archive-content` → `text/plain`. What
  comes back is the archive file exactly as written; declaring it JSON would invite a client to parse it as
  this application's shape rather than the archive's.

## Files
- `AccountGenerationArchivesQuery.java` — both lookups, the `Optional` handling and the file-scheme guard
- `AccountGenerationArchivesAPI.java` — the slice's two endpoints
