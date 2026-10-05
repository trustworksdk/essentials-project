# Manifest reconciliation — bringing `slice.yaml` and `CLAUDE.md` back in step with the code

Read by `/essentials:slice-check --fix-manifests`, by `skills/essentials-change`, and directly when a
project's manifests have drifted. The field semantics are `references/slice/manifest-guide.md`; this
file is the *procedure* for repairing a pair that no longer matches its slice.

Drift is the normal state of an unmaintained manifest, not a sign of a badly run project. Nothing in a
Maven or Gradle build reads `slice.yaml`, so nothing fails when it goes stale — it simply stops being
true, silently, and every tool that reads it inherits the lie. Reconciliation is therefore a routine
maintenance task, and it should feel like one.

---

## 1. What is derivable, and what is not

The single most important distinction. Getting it wrong destroys work.

| Class | Fields | Rule |
|---|---|---|
| **Machine-derived** | `handles`, `publishes`, `consumes`, `dispatches`, `serves`, `endpoints`, `writes`, `owns`, `reads`, `projections` (including `from` **and `aggregateTypes`**), `schedule`, `language`, `lane`, `tests.*.present` | Read from the code. On a conflict the **code wins** — it is what actually runs |
| **Human-owned** | `summary`, `owner`, `status`, `stability`, `invariants[].text`, `notes`, `forbidden`, `supersedes`, `tier`, `profile`, `sla`, `statutory`, `observability`, `x-*` | Never overwritten. Preserved byte-for-byte, including comments and ordering where the writer can manage it |
| **Derived, but a judgement** | `invariants[].enforcedBy`, `reads[].via`, `projections[].consistency`, `provides`, `endpoints[].capability` | Proposable from code, but confirm rather than assume: `consistency` follows the processor type, `enforcedBy` follows the class that throws |

A field that is machine-derived is safe to regenerate on every run. A human-owned field that disagrees
with the code is a **finding**, not a repair: `status: live` on a slice with no wiring is something to
report, not to quietly rewrite.

**Never-overwritten is not the same as never-written.** A human-owned field that is **absent** has no
human value to protect, so a one-time backfill is not a rewrite. `tier` is the case this matters for:
it is human-owned, so a project that adopted manifests before the field existed would never gain it
and would sit at the project default forever with no record of why. `/essentials:slice-check
--adopt-tier` stamps it **only where absent**, from the value resolved for the owning BC, and reports
every slice it touched. A `tier` that is present and disagrees with the resolved default stays a
finding.

**A manifest another generator owns is not drift to reconcile.** When `generator` is not `essentials`,
the tool that wrote the manifest may derive `tier` from its own project data and overwrite it on every
refresh — it has a source to derive from, and this plugin does not. That manifest is its generator's
to maintain; leave its `tier` alone. Nothing else on the human-owned row is backfillable — `summary`, `notes` and
`invariants[].text` are prose that a tool inventing content would only corrupt.

**Two of the judgement fields are seam metadata and need the pairing checked, not just the value.**
`provides` on the owning slice and `reads[].via` on the consumer are two halves of one declaration
(`manifest-guide.md` §3). Derive each from the code — an implemented interface registered as a bean,
an injected interface type — and report a half without its counterpart rather than inventing the
other side. `endpoints[].capability` is derivable only where authorisation is declarative
(`@PreAuthorize`, a security annotation, a route-table entry); where it is enforced in a method body,
propose nothing and leave the field to a human.

## 2. Where each inbound-event list lives

The drift that motivated this file. A view's events are **not** in `consumes`:

| Kind | Field | Read it from |
|---|---|---|
| view (event-sourced lanes) | `projections[].from` | Every event type handled by the slice's `ViewEventProcessor` / `InTransactionEventProcessor` — the parameter types of its `@MessageHandler` / `@Handler` methods, resolved to the declared type through imports, Kotlin `import … as` aliases, typealiases and fully-qualified names. A `@Handler` method on an `AnnotatedCommandHandler` is a command handler, not an event handler (`reactive/…/AnnotatedCommandHandler.java:91-92`) |
| view (service-entity lane) | — | No projector exists; `projections: []` is correct and complete |
| automation | `consumes` | The `EventProcessor`'s handler parameter types, resolved the same way; on the service-entity lane, the Spring `@EventListener` methods' parameter types |
| translation | `consumes` | The inbound handler's external message types, and every internal event the outbound publisher's `@MessageHandler` methods handle (gate 11(b)) |
| command | `handles` (in) / `publishes` (out) | The decider's command parameter and returned event types |

**Union the two when reading, write only the one that belongs to the kind.** A view carrying both has
two lists to keep in step and will drift again by the next release.

**The stream is a third fact, and it is not on this table.** `projections[].aggregateTypes` records
which `AggregateType` stream(s) the projector subscribes to; the table above is only about which
*events* it handles. Reconcile the two from different places in the code — the subscription call for
the stream, the handler signatures for the events — and never populate one from the other. A stream
name written into `from` is worse than an absent field, because every reader then draws an inbound
edge for an event type that does not exist.

## 3. The procedure, per slice

1. **Parse the manifest.** If it does not parse, fix that first — quoting repairs are a raw-text edit
   (`manifest-guide.md` §3), because there is no parsed side to merge until it does.
2. **Read the slice directory's facts from the script, not by eye.**
   `uv run --script ${CLAUDE_PLUGIN_ROOT}/scripts/slice-source.py <root> --json` gives, per slice:
   handler methods with their resolved message types, `handles`, `dispatches`, `publishes`,
   `subscriptions`, request `mappings` (routes, `params`, required `@RequestParam`s), `schedules`
   (ISO-8601 durations), `package`, `files` and a view's `readModels`. Everything it could not read is
   listed under `unparsed` and `notAnalysed`. Read exactly those files by hand, and read repository
   methods and anything a judgement field needs. Nothing outside the slice, except the BC's `events/` for
   resolving an event type name.
3. **Derive each machine-derived field** per §2 and the extraction rules below, from those facts.
4. **Three-way merge**: derived value, current value, and human-owned text. Machine-derived fields take
   the derived value; human-owned fields keep theirs; anything in class three is proposed and confirmed.
5. **Report every field that changed**, with the old and new value. A silent rewrite of a manifest is
   indistinguishable from corruption when the next person reviews the diff.
6. **Then the `CLAUDE.md`** — §4.

### Extraction rules

The syntactic rules below (`handles`, `publishes`, `consumes`, `dispatches`, `schedule`,
`projections[].from`, `projections[].aggregateTypes` from a literal or constant, `endpoints`) are what
`slice-source.py` computes. Take its facts and do not recount them. The column says what it reads, so a
fact it marks `unresolved` can be finished by hand. `publishes` is partial by design: on the aggregate
lane the events are applied inside the aggregate and are not attributed to the slice, so read them there.
The judgement fields (§1, third row) stay yours.

| Field | Derived from |
|---|---|
| `handles` | The decider's / handler's command parameter type |
| `publishes` | Event types returned by the decider, or published on the `EventBus` |
| `consumes` | Handler parameter types on an `EventProcessor` (automation) or inbound translator (translation) |
| `dispatches` | Command types passed to `commandBus.send` / `sendAsync` / `sendAndDontWait` |
| `schedule` | The automation's `@Scheduled` (or equivalent) — `cron` verbatim, `fixedDelay`/`initialDelay` as ISO-8601 durations. Carry the `note` over from a comment if one explains the cadence; never invent one |
| `provides` | Interfaces declared in the BC's `types/` that this slice implements **and** registers as a bean. `consumedBy` is derived by finding the slices that inject the interface — advisory, so a stale entry is reported rather than trusted |
| `projections[].from` | Handler parameter types on the view's projector, resolved to the declared type through imports, Kotlin `import … as` aliases, typealiases and fully-qualified names |
| `projections[].aggregateTypes` | The `AggregateType` the projector's subscription is opened against — the argument to the `EventStoreSubscriptionManager` subscribe call, or the `AggregateType` constant the BC's `config/` declares. **Do not derive it from `from`**: an event type is not a stream, and guessing one from the other is how the two axes got conflated in the first place. Where the subscription is wired outside the slice and the stream is genuinely ambiguous, leave the field absent and report it — absent means *unknown* |
| `projections[].consistency` | `ViewEventProcessor` → `eventual`; `InTransactionEventProcessor` → `strong` |
| `serves` | The query methods on the view's API file — names, not routes |
| `endpoints` | The API file's request mappings; **quote the path**, and a mapping selected by `params = "x"` is `"<route>?x="` (`manifest-guide.md` §3) |
| `writes` | The aggregate or entity the slice's write path mutates |
| `reads` | Read models and query interfaces the slice reads; `via:` names the interface, and is **mandatory** when the model belongs to another BC |
| `tests.*.present` | Whether the matching test file exists |
| `lane` | `/essentials:slice-check` gate 14's detected write style for the owning BC. Machine-derived because the code decides it: deciders → `decider`, `<bc>/aggregates/` → `aggregate`, `<bc>/entities/` → `service-entity`. A declared `lane` that disagrees with the detected one is a **finding** — it usually means the BC is mid-drift between two write styles, which gate 14 reports as Blocking on its own |

## 4. The `CLAUDE.md` half

The per-slice `CLAUDE.md` is prose written for a human and for the next session. It is **not**
regenerable from code, and treating it as generated output is how a team's accumulated context gets
deleted by a tool run.

| Section | On reconciliation |
|---|---|
| `# Slice: <bc>.<slice>` heading, `Files` list | Regenerate — mechanical, and a stale file list is actively misleading |
| `Invariants` | Preserve the prose. Append a note where the code now enforces something the file does not mention; never delete a line because you cannot find its enforcement |
| `Boundaries` | Preserve. Where code now violates a stated boundary, that is a `slice-check` finding, not an edit to the boundary |
| `Data` | Update the read-model / event names, preserve the commentary |
| Anything else the team added | Preserve verbatim |

If the manifest and the `CLAUDE.md` disagree about the same fact, the manifest is the machine-readable
one and gets the derived value; the `CLAUDE.md` gets a note that it was reviewed, and the disagreement
is reported so a human can decide which was the intent.

## 5. Reconciling a whole project

Order matters, because early steps make later ones cheaper and each is separately reviewable:

1. **Parse gate.** Run `scripts/slice-lint.py` and fix every manifest it reports as unparseable. Commit
   that alone — it is mechanical and a reviewer should not have to read it alongside semantic changes.
   Re-run the linter afterwards: it is the authority for this step, not your reading of the diff. After the
   kind-by-kind pass below, `scripts/slice-source.py <root> --check` is the same kind of authority for the
   derived fields: a manifest still reported under `11(b)` or `6 endpoint route` was not reconciled.
2. **Kind-by-kind, not slice-by-slice.** Do all views, then all automations, then translations, then
   commands. One extraction rule at a time is far easier to review than one slice at a time, and a
   mistake in a rule shows up as a consistent pattern rather than as scattered noise.
3. **Diff before writing.** Print the per-field changes for a whole kind, then apply.
4. **Re-run `/essentials:slice-check`.** Reconciliation fixes the manifest half; the gates say whether
   the *code* also has a problem the manifest was hiding.
5. **`/essentials:slice-map`** last, as the visible confirmation — a view that reacted to nothing before
   should now show its inbound events.

**Commit the reconciliation on its own.** It touches many files, changes no behaviour, and mixing it
with a feature change makes both unreviewable.

## 6. What reconciliation must never do

- **Never edit source to match a manifest.** The code is the truth; the manifest is the description. A
  disagreement is repaired in the description, or reported.
- **Never invent a field to make a gate pass.** An absent `invariants` list means nobody wrote one, and
  a fabricated one is worse than none.
- **Never delete a human-owned field** because the code has no counterpart. `forbidden`,
  `supersedes` and `statutory` are declarations of intent, and intent does not have to be inferable.
- **Never regenerate a `CLAUDE.md` wholesale.** See §4.
- **Never run over a dirty working tree.** Reconciliation touches many files at once; the diff is the
  only review it gets.
