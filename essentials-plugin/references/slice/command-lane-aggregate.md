# Command slice — the aggregate lane

Read by `skills/essentials-command-slice/SKILL.md` at Step 3 when `lane` is `aggregate`, and only then.
It holds this lane's part of Steps 3 and 4; the skill's own steps still apply in full.

## Step 3 — the bounded context

**Emit `command_aggregate` — but only in Java, and one file is still the user's.** The lane is
templated: command type, thin handler (load → call one method → done), API file, event variant,
manifest and both tests. Two caveats belong in the report every time:

- **The aggregate method is an edit to an existing file, so it is the user's call.** The handler the
  template emits calls `<Aggregate>.applyPlaceholder(...)`; the real invariant method has to be
  written on `aggregates/<Aggregate>.java`, which another slice also owns. Never silently add a
  method to an existing aggregate — name the method the handler expects, state the signature, and
  let the user write it. This is the lane's equivalent of the service-entity lane's "the entity is
  yours to write".
- **Java only.** There is no `templates/kotlin/command_aggregate/`, deliberately: the aggregate family
  (`AggregateRoot`, `StatefulAggregateRepository`) is Java-native and `/essentials:slice-check` treats
  an `aggregates/` directory in a Kotlin BC as **Advisory** — reachable through interop, but not a
  shape this plugin generates. If the BC is Kotlin and on this lane, emit nothing and say so: the
  right move is a conversation about whether the BC belongs on the decider lane, which Kotlin has
  first-class.

## Step 4 — what the render writes

The family is `command_aggregate`, from `templates/java/command_aggregate/`:

| Template | Destination |
|---|---|
| `__Slice__.java` | `<bc>/use_cases/<slice>/<Command>.java` |
| `__Slice__Handler.java` | `<bc>/use_cases/<slice>/<Slice>Handler.java` |
| `__Slice__API.java` | `<bc>/use_cases/<slice>/<Slice>API.java` |
| `events/__Event__.java` | `<bc>/events/<Event>.java` |
| `test/__Slice__Test.java` | test tree — the **aggregate** unit test, pure, no Spring |
| `test/__Slice__IT.java` | test tree — through the `CommandBus`, asserting the appended event |
| `slice.yaml`, `CLAUDE.md.template` | `<bc>/use_cases/<slice>/` |

**Not emitted, and reported instead:** the invariant method on `aggregates/<Aggregate>.java`. The
handler names it; the user writes it.

No decider, no evolver, no `GivenWhenThenScenario` on this lane — all three presuppose a stream. The
`--with-state` `State`/`Evolver` block applies to the decider lane only, so leave the flag off here.
