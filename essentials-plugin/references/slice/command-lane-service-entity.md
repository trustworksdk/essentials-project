# Command slice — the service-entity lane

Read by `skills/essentials-command-slice/SKILL.md` at Step 3 when `lane` is `service-entity`, and only
then. It holds this lane's part of Steps 3 and 4; the skill's own steps still apply in full.

## Step 3 — the bounded context

**`entities/` gets its orientation file but no entity.** The entity and its write repository are the
only files on this lane that must name a persistence flavour, and no template ships for them — the
Essentials JPA integration is EXPERIMENTAL upstream and its semantic-id story is unsettled
(`slice-model.md` §3.5). Emit `entities/CLAUDE.md`, and state in the report that `<Entity>` and
`<Entity>Repository` are the user's to write, against the contract that file carries. Never invent an
entity from the placeholders.

The scaffold never creates `use_cases/_shared/` on this lane: there are no evolvers to promote, and a
`_shared/` here is a service class in disguise (§R5).

## Step 4 — what the render writes

The family is `command_service_entity`, from `templates/<language>/command_service_entity/`:

| Template | Destination |
|---|---|
| `__Slice__.<ext>` | `<bc>/use_cases/<slice>/<Command>.<ext>` |
| `__Slice__Handler.<ext>` | `<bc>/use_cases/<slice>/<Slice>Handler.<ext>` |
| `__Slice__API.<ext>` | `<bc>/use_cases/<slice>/<Slice>API.<ext>` |
| `events/__Event__.<ext>` | `<bc>/events/<Event>.<ext>` |
| `test/__Slice__Test.<ext>` | test tree — the **entity** unit test, pure, no Spring |
| `test/__Slice__IT.<ext>` | test tree — through the `CommandBus`, asserting the event |
| `slice.yaml`, `CLAUDE.md.template` | `<bc>/use_cases/<slice>/` |

`--with-state` does not apply on this lane: there is no Decider to fold state for.
