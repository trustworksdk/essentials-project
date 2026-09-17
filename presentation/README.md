# Presentations

Two talks, each self-contained in its own directory: one HTML deck that needs no server and no build
step, speaker notes in English and Danish, a demo runbook, and the plan that produced it.

| Directory | Talk | Slot | The app behind it |
|---|---|---|---|
| [`instrument/`](instrument/) | **The Event Is The Record** — DDD, event modeling and slicing, shown through one instrument and eleven components with their trade-offs | 36 min + questions | [`examples/essentials-trading-demo`](../examples/essentials-trading-demo/) — aggregate style, snapshots, closing books |
| [`module6/`](module6/) | **Thirteen Concepts, Thirteen Answers** — the training module *Simplifying with Event Modeling, Event Sourcing and CQRS*, concept by concept, each followed by the code that implements it | 36 min + questions | [`examples/essentials-webshop-demo`](../examples/essentials-webshop-demo/) — decider style on `kotlin-eventsourcing`, three bounded contexts |

Both decks share one shell: a 16:9 container-query canvas, `data-lang` spans for the language toggle,
`data-note-en` / `data-note-da` speaker notes, and `data-act` / `data-min` budgets that the on-screen timer
reads — so the budget in the notes and the budget in the deck cannot drift apart.

Open a `deck.html` in any browser. `?` lists the keys; the ones worth knowing are `N` for the speaker note,
`L` for English/Dansk, `H` for handout mode on a bright projector, and `T` to start the talk timer.

Code panels are trimmed excerpts of real code from the app behind each talk — licence header and KDoc removed
to fit a slide, nothing else changed. After refactoring one of those demos, skim the slices the deck quotes;
each `NOTES.md` rehearsal checklist names them.

`module6/` also carries an `images/` directory: six diagrams taken from the source module's own pptx, so the
concept half of each pair shows the artwork the room already knows. The deck needs that directory beside it.
