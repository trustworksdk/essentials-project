# slice-compile: the slice templates and fixtures, built inside a real project

`render-slice.py check` proves the slice templates render to the committed goldens; it cannot prove the goldens
compile, or that the wiring they add starts. This directory closes that gap. Each case overlays rendered slice code
(or a fixture) onto a **host**: a project rendered by `scripts/init-render.py --host <lang>-<db>` from
`tests/golden/init/hosts.json`, so the slices compile against exactly the build users receive. The repo-root
`scripts/plugin-scaffold.sh` renders the host, copies the overlays and runs Maven; nothing here runs Maven itself.

```
overlay.py                  the case table: which composition or fixture goes on which host, with which goal
boot/{java,kotlin}/src/     TwoBoundedContextsBootIT, added to the two-BC compositions (see below)
behaviours/<dir>/src/       the behaviour checks: one integration test per `implemented` catalog entry (see below)
stubs/worked-example/src/   a test-only adapter for the worked example's outbound WarehouseClient port
```

| case | host | goal | what it proves |
|---|---|---|---|
| `java-decider`, `java-aggregate`, `kotlin-decider` | `<lang>-pg-event-sourced` | `verify` | every template of the lane (BC scaffold, two command slices, view, automation, translation) compiles against the installed reactor, the host's context starts with them in it (`ApplicationContextIT`, `OpenApiContractIT` against Testcontainers), the slices' own tests pass, and the untouched skeleton does what the catalog calls `implemented` for the lane (the behaviour checks) |
| `java-service-entity`, `kotlin-service-entity` | `<lang>-mongo` | `verify` | the same for the service-entity lane (command, view, the seeded entity) |
| `java-decider-two-bc`, `kotlin-decider-two-bc` | `<lang>-pg-event-sourced` | `verify` | two decider BCs start in one Spring context against Testcontainers PostgreSQL, the application has exactly one decider configurator, and `PlaceOrder` and `RequestPayment` each reach their own BC's decider through the `CommandBus` (no `MultipleCommandHandlersFoundException`, no duplicate bean) |
| `fixture-multi-lane`, `fixture-aggregate-lane` | `java-pg-event-sourced` | `test-compile` | the Java fixtures compile, so the imports and API shapes they hold are real (their packages sit outside the host's, so a context start would not load them) |
| `fixture-worked-example` | `kotlin-pg-event-sourced` | `verify` | the worked example, with `{{packagePath}}` rendered to the host's `com.example.shop`, `orders/` and `DeciderWiring.kt` placed under it, compiles and starts |

Not compiled, and why, is `NOT_COMPILED` in `overlay.py` (`service-entity` is a JPA application and no host declares
JPA; the rest hold no application sources). `overlay.py check` fails on a fixture directory that is in neither list.

Overlay rules (enforced by `plugin-scaffold.sh build-host`): an overlay is a directory holding a `src/` tree, copied
into the host's `backend/src/`; a file the host already has is never overwritten (exit 2), so an overlay cannot mask
a defect in the generated wiring. A case that fails because the host lacks a module is a host or template finding,
not something to patch here.

## Behaviour checks

`references/slice/templates/behaviours.json` tells the sdd plugin which behaviours a rendered skeleton already has, and
its test author writes no failing test for an `implemented` one, so a false `implemented` is a behaviour nobody tests.
Every `implemented` entry is therefore asserted here, against the rendered, untouched skeleton: a test under
`behaviours/<dir>/src/` carries a `// behaviour: <language>/<family> <id>` line directly above its `@Test`, and
`BEHAVIOUR_OVERLAYS` in `overlay.py` adds each directory to the compositions it serves (`java-event-processors`, the
Java view and automation checks, goes on both `java-decider` and `java-aggregate`). The tests are `*IT` classes
extending the host's `IntegrationTestBase`, so they run in the case's `verify` against Testcontainers.

`tests/scripts/test_behaviours_catalog.py` fails an `implemented` entry no marker names, a marker naming a
`placeholder` or unknown entry, a marker in a directory whose compositions do not render its family, and a composition
whose directories leave an `implemented` entry of a family it renders unasserted. A composition whose directories
leave such an entry unasserted is listed in `BEHAVIOUR_EXEMPT` in `overlay.py` with the reason: today the two
`*-decider-two-bc` compositions, which run the boot overlay instead. `kotlin-decider-manifest`, the `--manifest` render
path, has its own directory because `behaviours/kotlin-decider` sends `CancelOrder`, which that render does not have.
When a template change makes a behaviour real, add its test in the same commit as the status; when a test cannot pass
on the untouched skeleton, the entry is a `placeholder` whose summary says what is missing.

## Running

Needs a JDK, Maven, Python 3 and the reactor installed in the Maven repository the build uses (`DEV-SNAPSHOT` is not
on Central). `verify` also needs Docker. The subset the hosts and slices need:

```bash
scripts/plugin-scaffold.sh install       # the modules `scripts/plugin-scaffold.sh modules` lists, -am; CI runs the same

scripts/plugin-scaffold.sh slices                          # every case, its own goal
scripts/plugin-scaffold.sh slices --compile-only           # test-compile only: no Docker, no context start
scripts/plugin-scaffold.sh slices --case kotlin-decider-two-bc
python3 essentials-plugin/tests/slice-compile/overlay.py check   # the mapping alone: no Maven, well under a second
```

Each case leaves its project, overlays and `build.log` in `<work>/<case>/` (default work directory
`${TMPDIR:-/tmp}/essentials-scaffold`, never inside the repository).
