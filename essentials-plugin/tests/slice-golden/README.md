# Slice template goldens

What `scripts/render-slice.py` writes, committed so every template change shows up as a diff of
generated code. Each composition in `compositions.json` replays the steps `/essentials:add-slice` runs
for one language × lane — a new bounded context with its first command slice, a second command slice
(the second `@Bean` and `permits` append), then a view, an automation and a translation — into one
project tree. The `*-decider-two-bc` compositions scaffold two decider bounded contexts and prove the
application ends up with exactly one decider configurator (`com/example/shop/DeciderWiring`); only a
context start that sends a command to each BC proves it at runtime.

```
compositions.json            the compositions: language, lane, compile host, shared inputs, steps
_seeds/<name>/src/…          files a user writes by hand between two steps (the service-entity entity
                             and its repository), copied in by a {"seed": …} step
<composition>/src/main/<lang>/com/example/shop/orders/…   rendered and wired sources, manifests, CLAUDE.md
<composition>/src/test/<lang>/com/example/shop/orders/…   rendered tests
<composition>/render.json    the renderer's --json report for every step (written, wiring, todos)
```

Automation and translation are absent from the service-entity compositions by design: the renderer
refuses them on that lane. The Kotlin aggregate lane is not scaffolded at all.

Each `<composition>/src/` is a drop-in overlay for a host project generated with packagePath
`com.example.shop`; it holds no build file and no `Application` class. `host` in `compositions.json`
names the host it compiles on.

```bash
python3 scripts/render-slice.py check           # re-render and byte-diff; exit 1 on any difference
python3 scripts/render-slice.py update-golden   # after an intended template change — review the diff
python3 -m unittest discover -s tests/scripts   # the renderer's own tests (check runs among them)
```
