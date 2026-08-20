# Snippet Manifest

Every code excerpt on a slide maps to a file here, and every file here was copied verbatim from the
repository by `extract.sh`. Nothing on a slide is hand-typed approximation.

Re-run `presentation/snippets/extract.sh` before the talk. It fails loudly if a source has moved.

| Snippet | Slide | Source of truth |
|---|---|---|
| `01-register_instrument.slice.yaml` | Act 1 — the slice contract | `market_data/use_cases/register_instrument/slice.yaml` |
| `02-RegisterInstrument.java` | Act 1 — the slice on disk | same slice |
| `03-RegisterInstrumentHandler.java` | Act 1 — the slice on disk; Act 3 #2 (UnitOfWork) | same slice |
| `04-RegisterInstrumentAPI.java` | Act 1 — the slice on disk | same slice |
| `05-Instrument.java` | Act 2 — the aggregate in full | `market_data/aggregates/Instrument.java` |
| `06-InstrumentRegistered.java` | Act 2 — what an event looks like | `market_data/events/` |
| `07-InstrumentId.java` | Act 3 #1 — semantic types | `market_data/types/` |
| `08-Symbol.java` | Act 3 #1 — semantic types | `market_data/types/` |
| `09-Instruments.java` | Act 3 #4 — repository and `AggregateType` | `market_data/aggregates/` |
| `10-InstrumentPrice.java` | Act 3 #6 — snapshot policy | `market_data/aggregates/` |
| `11-MarketDataConfiguration.java` | Act 3 #10 — the declaration gotcha | `market_data/config/` |
| `12-InstrumentDetailsProjection.java` | Act 3 #5 — eventually consistent read model | `market_data/views/instrument_details/` |
| `13-LatestPriceQuery.java` | Act 3 #5 — the strongly consistent exception | `market_data/views/latest_price/` |
| `14-TradingAccountGenerationId.java` | Act 3 #7 — closing the books | `brokerage/types/` |
| `15-MarketDataAggregateTypes.java` | Act 1 — bounded-context boundary | `market_data/types/` |
| `90-framework-snippets.md` | Act 3 #2, #4, #8, #9 | `LLM/LLM-foundation.md`, `LLM/LLM-eventsourced-aggregates.md` — **hand-maintained, verify manually** |

## Where the deck shows code that does not exist

Two places, both labelled on the slide itself.

**Slides 7 and 8** present a **second requirement** — no two instruments may trade under the same symbol —
and the `INV-RI-3` YAML block and the extended `RegisterInstrumentHandler` on those slides are
**proposed, not extracted**. Nothing matching them is in the repository.

**Slide 9 and appendix slide A1** present the external-approval answer: `InstrumentRiskCheck extends
EventProcessor`, the `PENDING_RISK_APPROVAL` state, and the delayed deadline command. Also proposed. The
`EventProcessor` API it uses is real and documented in `LLM/LLM-postgresql-event-store.md` § EventProcessor
Framework — the *class* is the invention, not the mechanism. Worth being precise about that if someone asks:
`getInboxRedeliveryPolicy()`, `getCommandBus()`, `reactsToEventsRelatedToAggregateTypes()` and delayed
`sendAndDontWait(cmd, Duration)` are all framework API as documented.

This is deliberate and it is labelled on the slide itself: the handler panel's caption reads *proposed —
not in the repository yet*, and `NOTES.md` tells the presenter to say so out loud before showing it. The
point of those slides is to show how the model absorbs a new business rule, which needs a rule that has
not been absorbed yet.

If either rule is ever actually implemented, add the real files to `extract.sh` and drop the *proposed*
caption. Until then, do not let these snippets drift into `snippets/` — everything in that directory is
extracted, and mixing the two would cost the manifest its whole purpose.

## Trimming for slides

Several files carry long javadoc. That javadoc is excellent material for the speaker notes but will not
fit on a slide. The deck shows the code with javadoc reduced to a one-line comment where needed, and
`NOTES.md` carries the full reasoning. Two files are shown in full because they fit and because their
completeness is the point:

- `05-Instrument.java` — 108 lines including licence header; the body is ~60 lines
- `01-register_instrument.slice.yaml` — 30 lines

When trimming, cut javadoc and imports only. Never edit a statement, an identifier, or an annotation: the
audience may be looking at the same file in the IDE afterwards.
