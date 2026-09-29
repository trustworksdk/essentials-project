# essentials — maintainer instructions

Applies to any change under `essentials-plugin/`. The repository-root `CLAUDE.md` governs the
Essentials framework build and git hygiene; everything specific to the plugin lives here and only here.

The plugin carries **no version** — `plugin.json` has none. Every commit that reaches the marketplace
ref is a release, so land plugin changes on `main` in release-sized batches.

This file loads when Claude reads a file in this directory — but nested `CLAUDE.md` files are
**not** re-injected after a `/compact`. If you are resuming a compacted session and about to edit
anything here, read this file first.

## `/essentials:intro` is the surface index — keep it in sync

`commands/intro.md` is the one place a user sees the plugin's *whole* surface. It rots silently:
nothing fails when it goes stale, no test covers it, and the drift is invisible until a user runs
a command it never mentioned or hunts for one that no longer exists.

**Whenever you add, remove, or rename a command, skill, or agent, update `commands/intro.md` in
the same commit.**

| Change | What to update in `intro.md` |
|---|---|
| Command added / removed / renamed | Its line in the relevant `━━━` block, and `━━━ GET STARTED ━━━` if it is an entry point |
| A planned command ships (the anti-pattern hook) | Move it out of `━━━ NOT YET SHIPPED (planned) ━━━` into a real block, and check the `Rules` section for any line asserting it does not exist |
| `essentials-docs` or `essentials-change` behaviour changes (routing, proactive advisory, self-check, the change classes) | The `━━━ SKILLS THAT FIRE ON THEIR OWN ━━━` block |
| Template gains/loses a stack element, DB profile, or the worked example | The `━━━ WHAT /essentials:init SETS UP ━━━` block |
| A doc is added to or removed from `LLM/` (and so from `references/llm/`) | The bundled-docs count in the key-facts line — **the number is spelled out, verify with `ls references/llm \| wc -l`** |
| A framework opinion changes (intra-service scope, injection surface, slicing) | The `━━━ CORE PRINCIPLES ━━━` block |

Do **not** grow `intro.md` into a reference. One line per command, no worked examples, no
file-by-file enumeration of `references/llm/` — that is the skill's module index's job.

## Companion documents — what must move together

| Change | Also update |
|---|---|
| Command / skill added, removed, or re-scoped | `commands/intro.md`, `README.md` (the "What it does" list and the Layout block) |
| A doc added to or removed from `LLM/` | The skill's Module Index in `skills/essentials-docs/SKILL.md`, the doc count in `README.md`'s Layout block, and the intro's key-facts line |
| Template change (stack, profile, example BC, `CLAUDE.md.template`) | The matching step in `commands/init.md` — the command and the template are one contract |
| A file `/essentials:init` copies into a project, or a capability it offers to install | `commands/upgrade.md` Group A — an existing project reaches that capability **only** through `/essentials:upgrade`. A capability added to init alone ships to new projects and to nobody else, which is the gap `upgrade` exists to close |
| A requirement (S1–S11) added or re-scoped such that an existing project could now be non-conformant | The Group C table in `commands/upgrade.md`, with its severity and the symptom the failure presents as. A requirement no upgrade check names is one existing projects cannot discover except by booting and misreading a stack trace |
| A slice template added, removed, or changed | `references/slice/api-provenance.md` (re-run its verification snippet) and the owning kind skill's emission table |
| A new placeholder in a slice template | The placeholder table in `references/slice/slice-authoring.md` §4 — rendering fails loudly on an unknown one |
| The pointer template's body changed | Bump its `<!-- essentials-slices-rules: vN -->` stamp by one **in the same edit** — the counter continues from its current value and never restarts at `v1`, because existing projects carry the values already issued and a lower number would never be offered a refresh. `/essentials:init` copies the file straight from `references/slice/project-rules-pointer.md.template`; there is no second copy |
| A section added/renamed in `rules/slice-design.md` | Every skill and command citing it by section name, and the intro's `━━━ CORE PRINCIPLES ━━━` block |
| A change class, decision point, or guard rail in `references/slice/change-procedure.md` | `skills/essentials-change/SKILL.md`, which routes by those §-numbers and breaks silently if they move |
| A manifest field's meaning, or which field a kind declares its inbound events in | `references/slice/manifest-guide.md` §3, `references/slice/manifest-reconciliation.md` §1–§2 (derivable/human-owned split + extraction rules), `commands/slice-check.md` gate 11 and its `--fix-manifests` field list, and **every reader** — `commands/slice-map.md` Steps 2–4 and the graph builder in `references/slice/slice-map-template.html` |
| `references/slice/slice-map-template.html` or the data contract in `commands/slice-map.md` §6 | The other one — they are one contract — then re-render `tests/fixtures/slice-map/sample-data.json` and diff against its `TEST-GUIDE.md` |
| A heuristic added/changed in `references/slice/discovery-heuristics.md` | `tests/fixtures/brownfield-layered/` — add a source element that exercises it and a row to that fixture's `TEST-GUIDE.md`. A heuristic with no fixture element is unexercised, and the guide is the only oracle this plugin has |
| `references/slice/slice-yaml.schema.json` changed | Keep it backward compatible: manifests written by another tool — a `generator` other than `essentials`, the optional event-model linkage — must still validate. Then re-run `scripts/slice-lint.py` over `tests/fixtures/` **and** the template-render check in the pre-commit block: a schema tightening that the plugin's own artifacts fail is a defect in the tightening or in the artifacts, and you cannot tell which without running both |
| A gate added, removed, or re-scoped in `commands/slice-check.md` | Whether it belongs in `scripts/slice-lint.py` instead. If it is decidable from manifests alone — parsing, schema, id sets — it is the script's, it is deterministic, and Step 1.5 takes its output verbatim. If it needs a method body, it stays a gate. Never both |
| `scripts/slice-lint.py` changed | `commands/slice-check.md` Step 1.5 (its contract and exit codes), `references/slice/manifest-guide.md` § Validating a manifest, and `commands/init.md` Step 12.5 — projects hold an installed copy that gate 12 diffs against this one |
| A requirement (S1–S11) added, renumbered, or re-scoped in `references/stack/stack-contract.md` | Every citer — they cite **by number**, so a renumber breaks them silently: `skills/essentials-docs/SKILL.md`, `commands/init.md`, and the three sibling files in `references/stack/` |
| A pin needs changing | `references/stack/stack-pins.md` — the only place. Nothing mirrors it, so nothing will catch a stale one for you |
| A requirement's evidence changes | Its `Proof:` line in `stack-contract.md`. Every requirement cites a `references/llm/` doc or inlines the configuration that *is* the fact; a requirement with neither is an opinion |
| A `references/llm/` doc states an obligation a generated project must meet (a non-transitive dependency, a required registration, a package move) | `stack-contract.md` — **restate it as a requirement**. `/essentials:init` never reads `references/llm/`, so a `Proof:` line alone does not ship it |
| Java or Kotlin lane capability changes (a command starts or stops supporting a language) | The **Status paragraph** of the affected `references/stack/<language>-spring-boot.md`, `commands/intro.md`, **and the roadmap block in `commands/init.md` Step 14** — the last one is the copy users actually see |
| A doc changed in `LLM/` | Run `scripts/sync-plugin-llm.sh` from the repository root (the pre-commit hook installed by `scripts/install-git-hooks.sh` does it for you) and commit both. Never edit `references/llm/` — the hook refuses the commit and the CI drift gate fails it |
| Roadmap item started, shipped, or dropped | The intro's `━━━ NOT YET SHIPPED (planned) ━━━` block, `README.md`'s *Not yet shipped* list, and the roadmap block in `commands/init.md` Step 14 |

There is no `references/architecture.md` in this plugin. Do not invent one — `README.md` carries
that load.

## Design invariants — do not drift from these

- **`LLM/` (repository root) is the source; `references/llm/` is generated by
  `scripts/sync-plugin-llm.sh`; never edit it.** The copy is generated from `LLM/`, with links out of
  `LLM/` rewritten to GitHub URLs. A doc fix goes into `LLM/` and reaches the plugin through the sync. `SKILL.md` routes and decides; it must not restate doc content, and the docs must
  never be copied into a scaffolded project. Nothing in those docs is authored from memory — every
  claim traces to the Essentials source. The design guide, `references/design/essentials-design.md`,
  is the one plugin-authored doc: Trustworks modelling guidance that links into `references/llm/` for
  every framework fact rather than restating it.
- **Exactly two copies leave the plugin, and the second was an explicit decision.** The first is
  `.claude/rules/essentials-slices.md` (below). The second is the lint gate — `scripts/slice-lint.py`
  plus a copy of `slice-yaml.schema.json` beside it — installed by `/essentials:init` Step 12.5 or
  `/essentials:upgrade` Group A2, and **only on the user's yes**. It exists because a gate that lives only in the plugin cannot run in the
  project's CI or pre-commit hook, and that was the whole point: nothing in a JVM build reads
  `slice.yaml`, so a broken manifest ships and the slice silently vanishes from every audit. Both
  copies are governed by the same discipline — `/essentials:slice-check` gate 12 detects a stale
  project copy and **offers** a refresh, never overwriting silently. **No third file joins them
  without an explicit decision**, and the pressure to add one is exactly the pressure this invariant
  resists: each copy is a thing that can go stale in a repository nobody here can see.
- **The lint script is the deterministic half of `slice-check`, and must stay deterministic.** Gates
  1, 3 and 4 are parsing, JSON Schema validation, and set intersection over manifests — mechanical
  work where a model produces confident wrong answers. `slice-check` Step 1.5 delegates to the script
  and takes its output verbatim. Do not add a heuristic, a source-reading check, or an LLM-shaped
  judgement to `slice-lint.py`; anything needing to read a method body belongs in a `slice-check`
  gate. The script also **reports, never writes** — repair is `--fix-manifests`'s job, and a linter
  that edits is a linter nobody dares wire into CI.
- **The first copy — `.claude/rules/essentials-slices.md`.** It is a **pointer**,
  capped at 35 lines, carrying only the directory vocabulary, the four slice kinds, the boundary
  rule, and an `<!-- essentials-slices-rules: vN -->` stamp. It restates no framework API and no
  worked example — the law stays in `rules/slice-design.md` so plugin updates propagate live, and
  the stamp is what lets `/essentials:add-slice` and `/essentials:slice-check` detect a stale project
  copy and *offer* a refresh (never overwrite silently). **Growth of that file past a pointer is
  exactly the drift this invariant exists to prevent.** Source of truth is
  `references/slice/project-rules-pointer.md.template`, and there is exactly one copy of it in this
  plugin — `/essentials:init` copies that file directly into the generated project. The stamp is a
  counter that only ever goes up; see the pointer row in the companion table above.
- **`references/stack/stack-contract.md` is the application law, and it is cited by number, never
  restated.** It is to the application what `rules/slice-design.md` is to a bounded context's
  interior, and it follows the same convention: S1–S11 are quoted by number so a change propagates
  to every citer without rewriting them. Two rules keep it from rotting into prose. **Only
  `stack-pins.md` may name a version** — a requirement that names one rots the day the version
  moves, and a stale requirement is worse than none. And **every requirement carries a `Proof:`
  line** naming a `references/llm/` doc or inlining the configuration that *is* the fact; a
  requirement with neither is an opinion, and this plugin does not ship invented framework facts.
  **Be honest about what a proof is worth here: nothing in this plugin compiles or runs an
  Essentials application** outside `/essentials:init` Step 13.7 on a user's machine. The
  repository's Maven build and CI cover the framework, not the plugin's contract or templates. The
  evidence is documentary,
  and a claim of the form "this is verified because the template does it" was never true — the
  template was never built either. Do not reintroduce that claim in another form.
  **And the corollary: a fact that lives only in `references/llm/` does not reach a generated
  project.** `/essentials:init` reads the contract; it never opens the LLM docs, and a `Proof:` line
  is a maintainer's trace, not an include. Every requirement a generated project must satisfy has
  to be *stated in S1–S11* — citing the doc that knows it is not enough. That is why S2.1 restates
  the non-transitive dependency set from `LLM-spring-boot-starter-modules.md` § Dependencies
  instead of stopping at a citation of its *Starter Selection* section. When you add a `Proof:`
  line, check whether the proof contains an obligation the contract has not restated.
- **`tests/fixtures/worked-example/` is what the slice tooling reads, and that is its only job.**
  The plugin ships no project scaffold — a checked-in one rots (pins in several files, a committed
  generated API client, a Dockerfile) and nothing here builds it. The worked `orders` bounded
  context stays because it is the only {Kotlin, decider-lane} tree carrying manifests, so it is the "project already has manifests" case
  for `slice-map` and `slice-discover` and the decider-lane input for `slice-check`
  (`service-entity` covers {Java, service-entity}). It is sample **input**, never expected
  **output**: nothing here verifies the slice templates against it, and no "differential rendering"
  procedure exists. The generators
  in `references/slice/templates/` are more current than this example — never invert that.
  It carries **no version pins**, which is
  precisely why it is cheap to keep. Do not grow it back into a scaffold: if it acquires a
  `pom.xml`, a `package.json` or a `Dockerfile`, the rot is back.
- **`rules/slice-design.md` is the slice law, and it is standalone.** It depends on no other
  plugin — an Essentials project gets the whole law from this plugin. Skills and commands cite it
  **by section name and never restate it**. `rules/` is a plugin convention, not a Claude Code
  extension point: it costs nothing until something `Read`s it.
- **The four slice skills write files, and that is deliberate.** `essentials-docs` stays
  model-invoked because its auto-trigger *is* the product; the slice skills are the opposite —
  `disable-model-invocation: true`, entered by path from a command that has already elicited a
  bounded context and a name. They carry `Write`/`Edit` so the emission logic exists once instead of
  five times. Do not "fix" either of these to match the other.
- **Never let a template name an unproven Essentials symbol.** Every `dk.trustworks.essentials.*`
  import in `references/slice/templates/` must appear in `references/slice/api-provenance.md` with
  the `references/llm/` doc that proves it. The ledger carries its own verification snippet — run it
  when you touch a template.
- **`essentials-docs` stays model-invoked** (`user-invocable: false`). The auto-trigger on imports
  and framework type names *is* the product; users mute it with `skillOverrides`, not by us
  weakening the trigger description.
- **`essentials-change` is model-invoked and enters the kind skills by `Read`, not by invocation.**
  This is the one place the two skill policies above meet, and it is easy to "simplify" wrongly. The
  router must fire on prose, so it is model-invoked; the four kind skills must not fire on prose, so
  they stay `disable-model-invocation: true`. The router therefore *reads* a kind skill and follows
  its procedure with inputs it has already resolved — exactly the contract `/essentials:add-slice`
  honours. **Do not make the kind skills model-invocable to "let the router call them".** That would
  put file-writing skills one ambiguous sentence away from firing, which is what the flag prevents.
- **`essentials-change` gates on the project before it says anything.** `change-procedure.md` §1 ends
  in *stop silently* for a non-Essentials repository, and that row is load-bearing: a change-request
  trigger firing in unrelated repositories gets the skill muted globally, which costs exactly the
  projects it was built for. Widening the trigger description without widening the gate is the drift
  to watch for.
- **Every `path:` this plugin emits into a `slice.yaml` is quoted — templates, examples, fixtures and
  guides alike.** The manifests use flow mappings, and inside one an unquoted scalar containing `{`
  opens a nested mapping, so a single path variable makes the file invalid YAML. Nothing in a JVM build
  reads `slice.yaml`, so it compiles, tests green and ships broken; the failure only appears when a tool
  parses it, and then the slice **vanishes** from the map or the audit rather than erroring. That is why
  the rule is *always quote* and not *quote when it has a brace*: the second rule is correct until
  someone adds a path variable to an endpoint that did not have one, which is a routine edit. An
  unquoted template renders valid YAML only while `{{apiPath}}` happens to be derived as `/api/<bc>` —
  luck, not design, and it models the wrong pattern to anyone copying it.
- **A slice's inbound events are the union of `consumes` and `projections[].from`.** Views declare the
  events their projector handles on the projection; automations and translations use `consumes`. Any
  reader that consults `consumes` alone reports every correctly-generated view as reacting to nothing,
  on manifests that are entirely correct. When adding a
  reader, union the two; when adding a writer, write only the field that kind owns, never both.
- **`/essentials:slice-map` renders, never grades.** It reads manifests and six cheap divergence
  checks — directory exists, kind matches location, endpoint appears in source, id uniqueness, twin
  pairing, dangling consume — and stops there. Every R1–R5 gate, every severity, and every `--fix`
  belongs to `/essentials:slice-check`; a map that starts scoring becomes a second, weaker audit and
  the two then disagree in front of the user. Its counterpart rule is `slice-discover`'s: `slice-map`
  requires manifests and redirects without them, `slice-discover` redirects *with* them.
- **The template ships in `pg-event-sourced` form and is patched *down*.** `/essentials:init`
  strips to `pg-crud`/`mongo`; do not add a second template tree.
- **`/essentials:init` builds what it generates (Step 13.7), and that step is not optional.** It is
  the only executable check anywhere in this plugin, and it exists because the contract's most
  expensive failures — S2.1, and S3.1's leftover Jackson 2 module jar — compile cleanly and fail at
  context startup. Do not weaken
  it to a compile, and do not let it become advisory: an unbuilt scaffold reported as success is
  exactly the failure this step exists to prevent. When Step 13.7 turns up a
  dependency S2.1 does not list, the fix goes in the **contract** first.
- **`/essentials:init` creates, `/essentials:upgrade` catches up, and neither does the other's job.**
  Init runs once and never revisits a project, so a capability offered only by init reaches new
  projects only — the lint gate offered at Step 12.5 would reach nobody who already had a project,
  and `slice-check` gate 12 cannot see the absence of a gate that was never installed. The split is: **init elicits and generates, upgrade detects and
  offers.** Upgrade re-asks nothing (language, profile, frontend mode and coordinates are facts of
  the project — read them), regenerates no skeleton, touches no slice source, and **moves no version
  pin** — a pin move is an upgrade decision wanting an ADR, and folding it in here would make
  "catch up with the plugin" mean "change every dependency in the application". Upgrade's Group C is
  derived from the contract read live, never from a diff against a template tree: this plugin ships
  no project tree, so there is nothing to diff, and any check that assumes otherwise is fiction.
  **Both halves of a new capability ship together or the capability is half-shipped.**
- **The version stamp is a comment, not state.** `<!-- essentials-init: essentials <ESSENTIALS_VERSION> -->`
  lives in the project's own `CLAUDE.md`, a file init was writing anyway. `ESSENTIALS_VERSION` is the
  `essentials.version` pin in `references/stack/stack-pins.md` — the Essentials release the plugin
  targets; nothing reads `plugin.json`, which carries no version. The stamp exists so upgrade can
  *report* which Essentials version a project was scaffolded against — nothing branches on it, and
  every check reads current files instead. An absent stamp or an old `v<semver>` one (a project
  scaffolded before the plugin's first release, plus the `skip`/`merge` paths) is reported as such,
  degrades the report by one line, and must never gate a check. This is what keeps the statelessness invariant intact: the
  moment a check needs the stamp to be correct, the stamp has become a `.essentials/` directory with
  extra steps.
- **The placeholder contract is load-bearing.** `{{projectName}}`, `{{groupId}}`, `{{artifactId}}`,
  `{{packagePath}}`, `{{packageDir}}`, `{{db_label}}` (from the Step 4 DB profile),
  `{{sourceLang}}` (`kotlin` | `java`) and `{{appFile}}` (`Application.kt` | `Application.java`),
  both from the Step 3 language answer,
  `{{essentialsVersion}}` (the only one not sourced from an elicitation — it comes from the
  `essentials.version` pin in `stack-pins.md`), the `__PACKAGE__` source directories,
  and the *nesting* `<!-- IF var[=value] -->…<!-- END var -->` blocks (`stack`, `frontend`, `db`,
  `language`, `docker-compose`) are consumed by `commands/init.md` — which fails
  loudly on an unknown placeholder by design. Add one to the template and its substitution row to
  init in the same commit.
- **Never invent an Essentials API.** Type names, signatures, starter artifactIds, and config keys
  must be verified against the framework before shipping in a doc, the skill, or the template — a
  fabricated API looks authoritative and fails at the user's compiler.
- **Nothing Trustworks-internal ships in the template** (scratch scripts, internal hosts,
  credentials). Init deletes known strays as a safety net, not as a licence to add them.
- **Stateless.** No `.essentials/` runtime directory and no hooks today. Persistent state is a
  design decision to raise, not to add quietly. This is why `/essentials:slice-discover --write`
  takes a **user-supplied** path and defaults to printing: a plugin-owned directory starts as one
  report and accretes config, cache, and last-run state, which is how statelessness actually dies.
- **`slice-discover` never writes source, at any depth, and gains no `--fix` mode.** It *infers*
  structure where `slice-check` *audits* declared structure; an inferred boundary applied wrongly is
  worse than the layering it replaced. Its findings rank by **payoff**, never by the law's
  Blocking/Should-fix/Advisory severities — that code never opted into the law.
- **`tests/fixtures/` is never shipped as user-facing content and never built.** It exists so the
  discovery heuristics have an oracle. Fixtures must stay obviously synthetic so nobody mistakes one
  for a template, and must contain **traps** as well as findings — a fixture that only contains
  findings proves nothing about false positives.

## Before committing

```bash
cd essentials-plugin
ls references/llm | wc -l                                    # matches the spelled-out count in commands/intro.md
grep -n '^## ' rules/slice-design.md                         # matches intro's CORE PRINCIPLES block
# the HTML template's substitution point must match commands/slice-map.md §6 verbatim
grep -c 'const SLICE_MAP = /\* __SLICE_MAP_DATA__ \*/ null;' \
  references/slice/slice-map-template.html commands/slice-map.md      # 1 and 1
# No unquoted endpoint path in any manifest this plugin ships — one hit is a file that is not YAML.
# This is the same one-liner slice-check gate 1 and slice-map Step 2 give users, so it stays honest.
# Scope it to slice.yaml: the guides deliberately show the broken form as a counter-example.
grep -rn "path: [^\"']*{" --include=slice.yaml .                      # must print nothing
# Everything this plugin ships must satisfy its own schema. The linter is the oracle — run it on the
# fixtures, and render every template with a BRACED apiPath before validating. Both must come back
# clean; `pip install pyyaml jsonschema` if the script exits 2.
python3 scripts/slice-lint.py tests/fixtures --require-schema
python3 - <<'EOF'
import re, pathlib, yaml, json
from jsonschema import validators
schema = json.loads(pathlib.Path("references/slice/slice-yaml.schema.json").read_text())
V = validators.validator_for(schema); V.check_schema(schema); v = V(schema)
lower = {"bc", "slice", "view", "externalSystem", "owner"}
# tier/lane are the two write-style axes and must render to LEGAL values, not tokens:
# `lane` is a closed enum, and a wrong `tier` is silently downgraded to `custom` by any
# reader that does not recognise it. Render both lanes that map to cqrs-es and the one that does
# not.
fixed = {"apiPath": "/api/orders/{orderId}", "tier": "cqrs-es", "lane": "decider"}
def sub(m):
    k = m.group(1)
    if k in fixed: return fixed[k]
    return ("sub_" + k.lower()) if k in lower else "Sub" + k[0].upper() + k[1:]
bad = 0
for p in sorted(pathlib.Path("references/slice/templates").rglob("slice.yaml")):
    doc = yaml.safe_load(re.sub(r"\{\{(\w+)\}\}", sub, p.read_text()))
    for e in v.iter_errors(doc):
        bad += 1; print(p, "/".join(map(str, e.absolute_path)) or "(root)", e.message)
print("templates:", "FAIL" if bad else "ok")
EOF
# must be empty — the design guide lives at references/design/essentials-design.md, and no
# command of another plugin is routed to. This file is excluded: it names the patterns it guards against.
grep -rnE "LLM-essentials-design|LLM-opinionated|/sdd:" . \
  --include=*.md --include=*.json --include=*.template --include=*.yaml | grep -vE "^(\./)?CLAUDE\.md:"
# Both halves of the init/upgrade split are present: everything init copies into a project must
# have a Group A counterpart in upgrade, or existing projects can never receive it.
grep -c 'slice-lint\.py' commands/upgrade.md                 # >= 1
grep -c 'essentials-slices-rules' commands/upgrade.md        # >= 1
# The stamp placeholder must be substituted by init, never shipped literally.
grep -l '{{essentialsVersion}}' references/init-assets/CLAUDE.md.template commands/init.md   # both files

# references/llm/ is what the sync generates from LLM/ — the same check as the CI drift gate.
sh ../scripts/sync-plugin-llm.sh && git status --porcelain -- references/llm   # must print nothing

# references/stack/: only stack-pins.md may name a version — one hit elsewhere is drift.
grep -rnE '\b[0-9]+\.[0-9]+\.[0-9]+\b' references/stack/ --include=*.md \
  | grep -v 'stack-pins.md' | grep -vE '0\.50\.0|0\.60\.0|4\.0\.x'   # Essentials/Boot majors are allowed as prose
# every requirement carries its evidence
grep -c '^> Proof:' references/stack/stack-contract.md                # >= 9
# Everywhere else cites stack-pins.md / stack-contract.md, never restates them: flags a pinned value
# outside those two, and a MUST/SHOULD sentence copying an S-requirement's text. Exit 0 = clean;
# mark a deliberate exception `<!-- cite-ok: reason -->`. Stdlib only; rules and exemptions in --help.
python3 scripts/check-citations.py && python3 scripts/check-citations.py --self-test
```
