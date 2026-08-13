# Slice: brokerage.archive_generation

**Kind:** command   **Status:** live   **Owner:** brokerage-team
**Purpose:** Move one sealed books generation of a trading account out of the event store and into the archive.

## Invariants
- Archiving off is reported, never faked (enforced here): both `AggregateGenerationArchiver` and
  `AggregateArchiveApi` only exist when `essentials.eventstore.archives.enabled=true`, so they are injected
  as `Optional` and the absence throws with the property that turns it on. Requiring them outright made the
  *whole application* fail to start with archiving off, for the sake of this one slice.
- What is reported back is what the archive says, not what the archiver claimed: the archiver's return is
  ignored and the entry is reloaded through `AggregateArchiveApi`, which throws if it cannot be found.
- Which generations may be archived is the framework's rule, not this slice's — a sealed one, not the open one.

## Boundaries
**Reacts to / reads:** the `ArchiveGeneration` command, plus the archive.
**Publishes:** nothing — this writes to the archive, not to the `TradingAccount` stream.
**Forbidden:** never import another slice's internals; never read the archive file from here (that is a view).

The `@CmdHandler` returns a value, which is allowed. It is used here because the archived entry cannot be
obtained any other way in the same request.

## Endpoint
`POST /api/admin/trading-accounts/{accountId}/generations/{generation}/archive` — no body; both values are
path variables and the command is built inline. Returns `ApiArchivedGeneration`.

## Files
- `ArchiveGeneration.java` — the command; the one command here that is *not* a request body
- `ArchiveGenerationHandler.java` — the slice's one `@CmdHandler`, returning `ApiArchivedGeneration`
- `ArchiveGenerationAPI.java` — the slice's only endpoint
