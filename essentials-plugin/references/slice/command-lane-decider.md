# Command slice — the decider lane

Read by `skills/essentials-command-slice/SKILL.md` at Step 3 when `lane` is `decider`, and only then.
It holds this lane's part of Steps 3 and 4; the skill's own steps still apply in full.

## Step 3 — the bounded context

The decider-lane BC scaffold does not create `use_cases/_shared/`. A brand-new BC has no deciders, so a
shared `State` there would have zero consumers — premature promotion baked into the scaffold;
`_shared/` is created by hand later, when the third consumer appears (Step 2, Evolver placement).

## Step 4 — what the render writes

The family is `command`, from `templates/<language>/command/`:

| Template | Destination |
|---|---|
| `__Slice__.<ext>` | `<bc>/use_cases/<slice>/<Command>.<ext>` |
| `__Slice__Decider.<ext>` | `<bc>/use_cases/<slice>/<Slice>Decider.<ext>` |
| `__Slice__API.<ext>` | `<bc>/use_cases/<slice>/<Slice>API.<ext>` |
| `events/__Event__.<ext>` | `<bc>/events/<Event>.<ext>` |
| `test/__Slice__Test.<ext>` | test tree, mirroring the slice package |
| `slice.yaml`, `CLAUDE.md.template` | `<bc>/use_cases/<slice>/` |

**Conditional — `--with-state`, only if this Decider must fold state to decide** (Step 2, Evolver
placement). This applies to the decider lane only:

| Template | Destination |
|---|---|
| `__Aggregate__State.<ext>` | `<bc>/use_cases/<slice>/<Aggregate>State.<ext>` |
| `__Aggregate__StateEvolver.<ext>` | `<bc>/use_cases/<slice>/<Aggregate>StateEvolver.<ext>` |

They land **in the slice**, never in `use_cases/_shared/`. Leave the flag off when an idempotency
check over the raw event list suffices — the shipped `place_order` example needs no state at all. If a
`_shared/<Aggregate>State` already exists **and** this would be its third consumer with no new field
required, leave the flag off and import that instead; say so in the report.
