# Golden output of `/essentials:init`

`scripts/init-render.py` renders a project from `references/init-assets/project/`. The files here pin
what it renders, so a change to a template, a pin in `references/stack/stack-pins.md`, or the S2.1
table of `references/stack/stack-contract.md` shows up as a reviewable diff instead of reaching users
unseen.

| File | What it is |
|---|---|
| `cells.json` | The 9 golden answer sets (package `com.example.golden`, which the CI overlays in `tests/init-overlay/` assume): a pairwise covering array over language × db × web × frontend × compose (every pair of values appears in some cell). `defaults` holds the shared, obviously synthetic coordinates. |
| `<cell>.tree` | The rendered project of one cell, one file per cell. Generated — never edit by hand. |
| `hosts.json` | The 6 slice-compile hosts, language × db, all backend-only WebFlux with Compose off and no lint gate. Rendered on demand (`--host`), not golden; the slice templates compile against exactly what a user receives. Package `com.example.shop`, which the slice compositions assume. |

`lintGate` is spread over the cells so each value is rendered in three of them; it is not part of the
covering array (the pairs embedded × none and standalone × hook have no cell — the lint-gate files do
not depend on the frontend).

## Commands

Run from `essentials-plugin/`; stdlib Python 3.11+, no Docker, no JDK:

```bash
python3 scripts/init-render.py --self-test          # grammar, serializer, write rules
python3 scripts/init-render.py --all-combinations   # all 72 combinations + cells + hosts, static invariants
python3 scripts/init-render.py --check              # goldens equal a fresh render (exit 1 + diff otherwise)
python3 scripts/init-render.py --update-golden      # after an intended change; review the diff before committing
python3 scripts/init-render.py --host java-pg-event-sourced --out /tmp/host   # prints the directory
```

## `.tree` format

```
# init-render golden: <cell> — regenerate with init-render.py --update-golden
=== FILE <path> (644|755) ===
<content, verbatim>
=== COPY <path> <- <plugin-relative source> (644|755) ===
=== HOOK <id> (cwd <dir>) ===
<command init runs after the render>
```

Entries are sorted by path; the workspace pointer (`root: "workspace"` in the manifest) appears as
`@workspace/<path>` after the project entries. A file copied verbatim from elsewhere in the plugin
(`scripts/slice-lint.py`, its schema, the rules pointer) is one `COPY` line, so changing that file
does not touch the goldens. Content that does not end in a newline is followed by
`\ No newline at end of file`. The manifest's `hooks` (the Maven wrapper, the npm lockfile) are never run by the
renderer and cannot be rendered as files; they follow the files in manifest order, so a pin they use still shows up
in the diff.

## What `--all-combinations` checks on every render

- no `{{…}}` placeholder, `__PACKAGE__` or `IF`/`END`/`PATHS` directive is left;
- every `.xml` parses, every `.json` parses (`tsconfig*.json` as JSON with comments), no tab indents YAML;
- every path inside a `<!-- PATHS -->` region (the layout and code-location lists of the rendered
  `CLAUDE.md`/`README.md`) exists in the render or is listed in the manifest's `generatedPaths`;
- a Java render names no `src/…/kotlin` directory or `.kt` file, and a Kotlin render no `src/…/java`
  directory or `.java` file (the POM is exempt);
- every text file ends with a newline; no two manifest entries write one path.
