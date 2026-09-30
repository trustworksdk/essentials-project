# Slice Authoring — shared procedure

Read by `/essentials:add-slice`, the four per-kind commands, and the four slice skills. It exists so
the detection, resolution, placeholder, and emission logic is written **once**.

The structural law is `rules/slice-design.md`; the anatomy is `references/slice/slice-model.md`.

---

## 1. Language detection

Decide the target module's language before anything else. Check, in order:

| Signal | Conclusion |
|---|---|
| `kotlin-maven-plugin` or `org.jetbrains.kotlin` in the module's `pom.xml` / `build.gradle.kts` | Kotlin |
| `src/main/kotlin/` exists and contains `.kt` files | Kotlin |
| `src/main/java/` exists with `.java` files and no `src/main/kotlin/` | Java |

Kotlin is checked first because Kotlin projects use the same build files and the same `src/main/java`
layout is legal in them.

**Language-pure rule:** if the *target bounded context* contains both `.kt` and `.java` sources,
stop and report it. Do not guess and do not emit a mixed slice. A project may be polyglot across
modules; a bounded context may not be.

## 1b. Lane detection

Language chooses the template *directory*; the **§R5 write style** chooses the template *family*. Both
must be settled before emitting, and for the same reason: the same three artefacts differ between
them, so guessing produces a slice that compiles against nothing.

Detect per bounded context, never per project — the lane is a per-BC property:

| Signal in `<sourceRoot>/<packageDir>/<bc>/` | Lane | Template family |
|---|---|---|
| A `Decider` / `EventStreamDecider` implementation, or neither marker directory | decider | `command/`, `view/`, `bc-scaffold/` |
| `aggregates/` exists | aggregate | `command_aggregate` — **Java only**, see below |
| `entities/` exists, the write path uses the Essentials command bus, and no `EventStore` / `AggregateType` is referenced | service-entity | `command_service_entity/`, `view_service_entity/`, `bc-scaffold-service-entity/` |

**Lane-pure rule**, mirroring the language-pure rule: if the target BC shows **two** lanes — any two of
per-slice deciders, `aggregates/`, `entities/` — **stop and report it.** That is two write designs over
one consistency boundary, Blocking under §R5, and emitting into it would deepen the violation. A
*project* may run different lanes in different BCs; a bounded context may not.

**The aggregate lane is scaffolded in Java only.** On a Java BC, emit the `command_aggregate` family
and report the one file that is not generated: the invariant method on `aggregates/<Aggregate>`,
which is an edit to a file other slices own. On a **Kotlin** BC on that lane, emit nothing and say
why — `AggregateRoot` / `StatefulAggregateRepository` are a Java-native family, and
`/essentials:slice-check` treats `aggregates/` in a Kotlin BC as Advisory interop rather than a
supported shape.

**A new bounded context has no lane to detect**, so the dispatching command elicits it (see
`commands/add-slice.md` Step 3b) and passes it in as the `lane` input. Never re-elicit it in a skill.

## 2. Project resolution

1. **Project root** — the directory holding `pom.xml` (or `backend/pom.xml` in the two-module
   layout `/essentials:init` produces).
2. **Confirm it is an Essentials project** — `dk.trustworks.essentials` appears in the build file.
   If not, stop and point the user at `/essentials:init`.
3. **Source root** — `<module>/src/main/kotlin` or `<module>/src/main/java`; test root is the
   matching `src/test/...`.
4. **Package path** — read it from the `package` declaration of `Application.kt` / `Application.java`.
   **Never ask the user for it**; it is a property of the project, and asking invites a mismatch.
5. **Package dir** — the package path with `.` replaced by `/`.

## 3. Bounded context

Discover candidates: directories directly under `<sourceRoot>/<packageDir>/` that contain a
`use_cases/` or `views/` child. Offer those plus "new bounded context".

If the bounded context does not exist, emit the BC scaffold **first**, then the slice — and only with a
**command** slice. Every view, automation and translation template imports `<bc>/events/<Event>`, and
only a command slice supplies an event variant, so a new BC started by any other kind cannot compile;
`render-slice.py` refuses `--new-bc` for those kinds. Which scaffold depends on the lane (§1b):

- **decider** — `bc-scaffold/`: sealed event parent, routing interface, id type, and a config class
  holding only this BC's aggregate-type configuration and its decider beans. The command routing
  itself — the one `…DeciderAndAggregateTypeConfigurator` — is **application-level**, in
  `<packagePath>.DeciderWiring` (`app-wiring/`), written by the first decider BC and never again: a
  second configurator registers every decider twice and the first command sent fails with
  `MultipleCommandHandlersFoundException`.
- **service-entity** — `bc-scaffold-service-entity/`: sealed event parent, id type, a near-empty
  config class, and `entities/CLAUDE.md`. **No `routing/` and no `use_cases/_shared/`** — both are
  absent by construction on that lane (§R5), so do not create them "for symmetry".

**`entities/` gets its orientation file but no entity.** The entity and its write repository are the
only files on that lane that must name a persistence flavour, and no template ships for them
(`slice-model.md` §3.5). Emit `entities/CLAUDE.md`, which states the contract, and tell the user in
the report that those two files are theirs to write. Do not invent an entity from the placeholders.

**Java:** a `permits` clause may not be empty, so `events/<Aggregate>Event.java` is emitted with the
BC's first command slice, whose event is the first entry. This applies on **every** scaffolded lane: a
service-entity command slice still supplies an event variant, because its events are declared exactly
as §R3 prescribes and merely delivered on the `EventBus` rather than stored.

## 4. Placeholders

Two namespaces, mirroring the project template's established contract:

| Form | Applies to | Example |
|---|---|---|
| `__Name__` | path segments — directory and file names | `__Slice__Decider.kt` |
| `{{name}}` | file content | `{{packagePath}}` |

| Placeholder | Source | Example |
|---|---|---|
| `{{packagePath}}` | read from the project (§2.4) — never elicited | `com.acme.shop` |
| `{{bc}}` / `{{Bc}}` | elicited | `orders` / `Orders` |
| `{{slice}}` / `{{Slice}}` / `{{sliceCamel}}` | elicited; the camel form is derived | `place_order` / `PlaceOrder` / `placeOrder` |
| `{{aggregate}}` / `{{Aggregate}}` | elicited, or derived from the BC | `order` / `Order` |
| `{{entity}}` / `{{Entity}}` | **service-entity lane only** — derived from `{{aggregate}}`/`{{Aggregate}}` unless the BC's `entities/` already names one, in which case read it from there | `order` / `Order` |
| `{{Aggregates}}` | **aggregate lane only** — the repository wrapper's type name, i.e. the plural of `{{Aggregate}}`. Ask rather than derive: English plurals are irregular (`Company` → `Companies`, `Person` → `People`), and this name appears in every handler on the lane. If the BC's `aggregates/` already declares one, read it from there | `Orders` |
| `{{Command}}` | command kind | `PlaceOrder` |
| `{{Event}}` | command kind | `OrderPlaced` |
| `{{View}}` / `{{view}}` / `{{viewCamel}}` | view kind | `OrderList` / `order_list` / `orderList` |
| `{{AggregateType}}` | the Essentials `AggregateType` name — PascalCase plural | `Orders` |
| `{{externalSystem}}` / `{{ExternalSystem}}` | translation kind | `billing` / `Billing` |
| `{{ExternalEvent}}` | translation kind | `InvoiceIssued` |
| `{{apiPath}}` | derived — `/api/{{bc}}` | `/api/orders` |
| `{{owner}}` | elicited, defaults to `{{bc}}-team` | `orders-team` |
| `{{lane}}` | the `lane` input `/essentials:add-slice` already resolved (Step 3b) — passed through verbatim | `decider` |
| `{{tier}}` | **derived from `{{lane}}` by the table below** — never elicited, and never equal to `{{lane}}` except on one lane | `cqrs-es` |

**`{{tier}}` and `{{lane}}` are two axes, and the mapping is not the identity.** `tier` is the
manifest's `architectureTier` vocabulary — *how is the backend organised* — and `lane` is §R5's write style —
*where the decision lives inside it*:

| `{{lane}}` | `{{tier}}` | Why |
|---|---|---|
| `decider` | `cqrs-es` | Event-sourced: state is a fold over a stream |
| `aggregate` | `cqrs-es` | Also event-sourced — the aggregate is rebuilt from the stream |
| `service-entity` | `service-entity` | The one value the two axes share |

**Never write `tier: aggregate`.** `aggregate` is not an `architectureTier` value, and the damage is
silent rather than loud: the schema's contract for an unrecognised tier is *behave as if it were
`custom`* (`slice-yaml.schema.json`, `tier`), which makes a tier-aware reader skip the slice's per-tier
structure sections and its manifest generation altogether. A wrong `tier` is worse than an absent one.
This is exactly the conflation the two fields exist to prevent — it is easy to miss because
`service-entity` is a legal value on both axes.

**No `<!-- IF -->` conditionals in slice templates.** Language *and lane* are selected by choosing the
template directory, not by rendering a conditional. Do not wire `/essentials:init`'s conditional
renderer in. That is why the lane produces a sibling family (`command_service_entity/`) rather than a
flag inside `command/`: two templates that share 40% of their text but differ in what they *are* age
better apart than as one file full of branches.

`{{Entity}}` is emitted **only** by the service-entity families. A decider-lane template containing it
is a drift bug, and the fail-loud check below catches it.

**Fail loudly:** after substitution, a rendered file containing `{{` or `__` means the template has
drifted from this table. Abort and name the file — do not write it. `render-slice.py` enforces this in
code (§4b), together with an unknown placeholder name and a placeholder with no value.

## 4b. Rendering is a script

Everything in §3–§6 that is substitution, placement or an anchored edit is done by
`${CLAUDE_PLUGIN_ROOT}/scripts/render-slice.py` (standard library only). The skill resolves the inputs
and makes the judgements; the script writes the files; the skill then fills the TODOs, prunes what the
slice does not need, and reports. Never substitute a template by hand — the committed goldens under
`tests/slice-golden/` prove what the script writes, and a hand render is not what they prove.

```bash
python3 ${CLAUDE_PLUGIN_ROOT}/scripts/render-slice.py requires --lang <language> --kind <kind> \
    --lane <lane> [--new-bc] --build <projectRoot>/<pom.xml|backend/pom.xml|build.gradle.kts>
python3 ${CLAUDE_PLUGIN_ROOT}/scripts/render-slice.py render --lang <language> --kind <kind> \
    --lane <lane> [--new-bc] [--with-state] --wire --json \
    --project-root <projectRoot> --main-root <sourceRoot> --test-root <testRoot> \
    --set packagePath=… --set bc=… --set Aggregate=… …
```

- Pass the elicited inputs only; the script derives `Bc`, `Slice`/`sliceCamel`, `View`/`viewCamel`,
  `ExternalSystem`, `aggregate`, `entity`/`Entity`, `apiPath`, `owner` and `tier` (the §4 table).
  `AggregateType` and `Aggregates` are plurals and are always passed. A derived value may be
  overridden with `--set` (an existing BC's entity name, a non-default `apiPath`).
- **`requires`** exits 1 and names each module the slice needs that the build file does not declare
  (`eventsourced-aggregates` is optional in the event-store starter, and no starter brings
  `postgresql-document-db`). Report it and offer to add it before rendering; a slice rendered into a
  project without it does not compile.
- **`render` exits 2 and writes nothing** on a bad input, a template drift, an existing slice
  directory or file, a lane that does not match the BC's directories (§1b), a service-entity view whose
  entity does not exist yet, or a refused combination (§1b Kotlin aggregate lane; automation and
  translation on the service-entity lane; `--new-bc` on a non-command kind). Relay the message.
- **`--json`** returns `written` (every file), `wiring` (each edit as `applied`, `present` or
  `manual` with a reason — `manual` means the anchor comment is gone and the edit is yours to make by
  hand; on the decider lane the first entry counts the application's decider configurators, and
  `manual` there means none or more than one, which you report) and `todos` (`path:line: text` of every TODO the templates left).

**`slice.yaml` is the one rendered file that must also be *parsed* before it is written**, and it has
a rule that no other template has: **every `path:` value is quoted**, in the template and in anything
that later edits it. The manifests use flow mappings (`- { method: POST, path: "…", auth: user }`), and
inside one an unquoted scalar containing `{` opens a nested mapping, so a path variable makes the file
invalid YAML (`manifest-guide.md` §3). Nothing in a JVM build reads `slice.yaml`, so the damage is
silent until a tool parses it — at which point the slice disappears from a map or an audit. The
templates already quote; keep it that way when adding one, and never render a path unquoted because
"this one has no variable yet".

## 5. How slice templates differ from the project template

The project template is a **tree copied then patched down**. Slice templates are **fragments inserted
into a tree that already exists**. Consequences:

1. **No `__PACKAGE__`, no directory rename.** The package is resolved from the project; the slice
   directory is created directly at its final path.
2. **Idempotency is mandatory.** `/essentials:init` refuses a non-empty target; slice authoring runs
   *into* one. Check before every write. An existing slice directory is an abort, never a merge.
3. **Some emissions are edits, not writes** — the `@Bean` registration, and in Java the `permits`
   clause. The project template never edits.
4. **Files land in up to three trees**: the slice directory, the BC's `events/`, and the test tree —
   each at the package its `package` line declares, which is how the service-entity entity test lands
   in `<bc>/entities/`. **No template emits a migration.** The event-sourced view and automation
   templates persist through DocumentDB, which creates its own table; a JDBI read model, and the
   service-entity lane's write table, need a migration the user writes.

## 6. Emission order

1. BC scaffold, if the bounded context is new — the family chosen by the lane (§1b, §3).
2. The slice's own files, into `<bc>/<roleDir>/<slice>/`.
3. The event variant into `<bc>/events/` (command slices, **both** scaffolded lanes).
4. The test into the matching test tree — on the service-entity lane a command slice emits **two**
   (the pure entity unit test and the through-the-bus IT).
5. `slice.yaml` and `CLAUDE.md` into the slice directory.
6. **Wiring** — add the `@Bean` to `<bc>/config/<Bc>Configuration` (decider lane), confirm the
   application has exactly one decider configurator (decider lane), and in Java append the new event to
   the sealed parent's `permits` clause. `render-slice.py --wire` makes both edits at
   the anchor comments the BC scaffold carries.

Steps 1–5 are one `render-slice.py render` call. Wiring is step 6 and not optional:
`rules/slice-design.md` § Wiring is part of done.

**The service-entity lane differs in two ways:**

- **No migration.** There is no read-model migration, because there is no separate read model — a
  view slice queries the entity's own table. What *does* need a migration is the **write** table, in
  both languages, and that belongs to `entities/` rather than to any slice. Say so in the report.
- **Step 6 becomes a check, not an edit.** There is no `@Bean` to add: handlers are auto-registered by
  `ReactiveHandlersBeanPostProcessor` and Spring Data repositories by scanning. Confirm the handler is
  a `@Component` in a scanned package and that `reactive-bean-post-processor-enabled` (default `true`)
  is not switched off — disabling it silently unwires every handler in the application. The Java
  `permits` append still applies.

## 7. Report

Close with:

- A table of every file written or edited, with its path.
- The wiring performed, named explicitly.
- What the user must still fill in — invariants in the decider, fields on the event, the query in the
  view repository.
- The self-check: re-read `rules/slice-design.md` § Red flags and confirm none of the structural
  entries applies to what was just emitted.

## 8. Project rules pointer

Before emitting, check `.claude/rules/essentials-slices.md` in the target project against
`references/slice/project-rules-pointer.md.template`. Compare the `<!-- essentials-slices-rules: vN -->`
stamps by the number after `v`, read as an integer — `v10` is newer than `v9` — never as a string:

- **Missing** → offer to write it (default yes).
- **Stamp older than the plugin's** → offer to refresh, offer to show a diff first, or keep theirs.
  **Never overwrite silently** — the user may have edited it.
- **Stamp newer than the plugin's** → advisory only: their project was written by a newer plugin.
